package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.content.Context
import android.util.Log
import android.os.SystemClock
import android.view.Surface
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Receive-only WebRTC. No capture/audio permission, cloud signaling, STUN or TURN service. */
class WebRtcRenderRepository(private val context: Context): RenderVideoRepository {
    override fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit): AutoCloseable {
        synchronized(WebRtcRenderRepository::class.java) {
            if(!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                initialized=true
            }
        }
        return Session(surface,pairing,aspect,status)
    }
    private class Session(surface: Surface,private val pairing: Pairing,aspect: Float,
        private val status: (RenderVideoStatus)->Unit): AutoCloseable {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        private val closed=AtomicBoolean(false)
        private val failed=AtomicBoolean(false)
        private val firstFrame=AtomicBoolean(false)
        private val lastFrame=AtomicLong(0)
        private val egl=EglBase.create()
        private val renderer=EglRenderer("Blender render window")
        private val factory=PeerConnectionFactory.builder().setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        private var track: VideoTrack?=null
        private var peer: PeerConnection?=null
        private val stream=UUID.randomUUID().toString()
        private val localIce=ConcurrentLinkedQueue<IceCandidate>()
        private val sink=VideoSink { frame ->
            if(!closed.get()) {
                lastFrame.set(SystemClock.elapsedRealtime())
                renderer.onFrame(frame)
                if(firstFrame.compareAndSet(false,true)) {
                    val width=frame.rotatedWidth;val height=frame.rotatedHeight
                    scope.launch {
                        Log.i("BlenderBridge","RENDER_VIDEO firstFrame=${width}x${height}")
                        status(RenderVideoStatus("Live render · $width × $height",true))
                    }
                }
            }
        }
        init {
            renderer.init(egl.eglBaseContext,EglBase.CONFIG_PLAIN,GlRectDrawer())
            renderer.setMirror(false);renderer.setLayoutAspectRatio(aspect);renderer.createEglSurface(surface)
            scope.launch {
                try { connect() }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    val reason=error.message?.takeIf { it in setOf("Peer disconnected","Stream lease changed","Mac share stopped","No video received","Offer failed","Description failed","Render signaling unavailable","Oversized signaling response") } ?: "transport_or_decoder"
                    Log.w("BlenderBridge","RENDER_VIDEO stopped: ${error.javaClass.simpleName} $reason")
                    status(RenderVideoStatus("Stream unavailable · check Mac sharing, then Retry stream"))
                } finally {
                    withContext(NonCancellable+Dispatchers.IO) { runCatching { signal(JSONObject().put("type","stop").put("stream",stream)) } }
                }
            }
        }
        private fun emit(message: String) { scope.launch { if(!closed.get()) status(RenderVideoStatus(message)) } }
        private suspend fun connect() {
            status(RenderVideoStatus("Connecting to the Mac render stream…"))
            val observer=object: PeerConnection.Observer {
                override fun onSignalingChange(value: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(value: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(value: Boolean) {}
                override fun onIceGatheringChange(value: PeerConnection.IceGatheringState) {
                    Log.i("BlenderBridge","RENDER_ICE gathering=$value")
                }
                override fun onIceCandidate(value: IceCandidate) {
                    localIce.add(value)
                    Log.i("BlenderBridge","RENDER_ICE localCandidate")
                }
                override fun onIceCandidatesRemoved(value: Array<out IceCandidate>) {}
                override fun onAddStream(value: MediaStream) {}
                override fun onRemoveStream(value: MediaStream) {}
                override fun onDataChannel(value: DataChannel) { value.close() }
                override fun onRenegotiationNeeded() {}
                override fun onConnectionChange(value: PeerConnection.PeerConnectionState) {
                    if(value==PeerConnection.PeerConnectionState.FAILED || value==PeerConnection.PeerConnectionState.DISCONNECTED) {
                        failed.set(true);emit("Video disconnected · Retry stream")
                    }
                }
                override fun onTrack(value: RtpTransceiver) {
                    scope.launch {
                        if(!closed.get()) {
                            track?.removeSink(sink)
                            track=value.receiver.track() as? VideoTrack
                            track?.addSink(sink)
                        }
                    }
                }
            }
            val config=PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val pc=requireNotNull(factory.createPeerConnection(config,observer));peer=pc
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            val offer=createOffer(pc)
            setDescription { pc.setLocalDescription(it,offer) }
            signal(JSONObject().put("type","offer").put("stream",stream).put("sdp",pc.localDescription.description))
            var answered=false;var beat=0;var waiting=false;var publisherSeen=false
            var answerAt=0L;var remoteIceIndex=0
            while(currentCoroutineContext().isActive) {
                check(!failed.get()) { "Peer disconnected" }
                while(true) {
                    val candidate=localIce.poll() ?: break
                    signal(JSONObject().put("type","ice").put("stream",stream).put("side","viewer")
                        .put("candidate",candidate.sdp).put("mid",candidate.sdpMid).put("index",candidate.sdpMLineIndex))
                }
                val state=request(null)
                check(state.optString("stream")==stream) { "Stream lease changed" }
                val publisherReady=state.optBoolean("publisherReady")
                check(!publisherSeen || publisherReady) { "Mac share stopped" }
                publisherSeen=publisherSeen || publisherReady
                if(!publisherReady && !waiting) {
                    waiting=true;status(RenderVideoStatus("On Mac: Share render window → choose the Blender window"))
                }
                if(!answered && !state.isNull("answer")) {
                    setDescription { pc.setRemoteDescription(it,SessionDescription(SessionDescription.Type.ANSWER,state.getString("answer"))) }
                    answered=true;answerAt=SystemClock.elapsedRealtime();status(RenderVideoStatus("Receiving Blender video…"))
                }
                if(answered) {
                    val remote=state.optJSONArray("publisherCandidates")
                    while(remote!=null && remoteIceIndex<remote.length()) {
                        val candidate=remote.getJSONObject(remoteIceIndex++)
                        check(pc.addIceCandidate(IceCandidate(candidate.getString("mid"),candidate.getInt("index"),candidate.getString("candidate")))) { "ICE candidate rejected" }
                    }
                }
                val frameAt=lastFrame.get()
                val now=SystemClock.elapsedRealtime()
                check(!answered || frameAt!=0L || now-answerAt<15000) { "No video received" }
                if(frameAt!=0L && now-frameAt>3000 && firstFrame.compareAndSet(true,false))
                    status(RenderVideoStatus("Video paused · waiting for the Mac"))
                if(++beat%8==0) signal(JSONObject().put("type","viewer").put("stream",stream))
                delay(250)
            }
        }
        private suspend fun createOffer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { c ->
            pc.createOffer(object: SdpObserver {
                override fun onCreateSuccess(value: SessionDescription) { if(c.isActive)c.resume(value) }
                override fun onCreateFailure(error: String) { if(c.isActive)c.resumeWithException(IllegalStateException("Offer failed")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            },MediaConstraints())
        }
        private suspend fun setDescription(action: (SdpObserver)->Unit): Unit = suspendCancellableCoroutine { c ->
            action(object: SdpObserver {
                override fun onCreateSuccess(value: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
                override fun onSetSuccess() { if(c.isActive)c.resume(Unit) }
                override fun onSetFailure(error: String) { if(c.isActive)c.resumeWithException(IllegalStateException("Description failed")) }
            })
        }
        private suspend fun signal(body: JSONObject) { request(body) }
        private suspend fun request(body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
            val connection=URI(pairing.endpoint+"/v1/render-stream").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout=3000;connection.readTimeout=3000
            connection.setRequestProperty("Authorization","Bearer "+pairing.token)
            try {
                if(body!=null) {
                    val bytes=body.toString().toByteArray();connection.requestMethod="POST";connection.doOutput=true
                    connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                check(connection.responseCode in listOf(200,202)) { "Render signaling unavailable" }
                val bytes=connection.inputStream.use { it.readNBytes(600001) }
                check(bytes.size<=600000) { "Oversized signaling response" }
                JSONObject(String(bytes))
            } finally { connection.disconnect() }
        }
        /** Synchronous decoder/sink shutdown must finish before the borrowed Surface is released. */
        override fun close() {
            if(!closed.compareAndSet(false,true))return
            scope.cancel();track?.removeSink(sink);track=null
            peer?.close();peer?.dispose();peer=null
            renderer.release();factory.dispose();egl.release()
            Log.i("BlenderBridge","RENDER_VIDEO released")
        }
    }
    companion object { private var initialized=false }
}
