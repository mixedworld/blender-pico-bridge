package com.example.blenderpicobridge.p7f82b4c1

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.data.repository.HttpBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.data.repository.WebRtcRenderRepository
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live LAN video check. Reads pairing only; never writes room/calibration preferences. */
@RunWith(AndroidJUnit4::class)
class RenderVideoTransportTest {
    @Test fun threeReceiverSessionsReceiveAndReleaseVideo() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("physical_render")=="true")
        val stereoTest=InstrumentationRegistry.getArguments().getString("stereo_render")=="true"
        val alphaTest=InstrumentationRegistry.getArguments().getString("alpha_render")=="true"
        val context=instrumentation.targetContext
        val pairing=HttpBridgeRepository(context).savedPairing()
        require(pairing.endpoint.isNotEmpty() && pairing.token.isNotEmpty()) { "Pair the app first" }
        repeat(3) { attempt ->
            val thread=HandlerThread("Render video test drain").apply { start() }
            val reader=ImageReader.newInstance(1280,610,if(alphaTest) PixelFormat.RGBA_8888 else ImageFormat.PRIVATE,3)
            val capture=InstrumentationRegistry.getArguments().getString("capture_render")=="true"
            val saved=AtomicBoolean(false)
            val imageReady=CountDownLatch(1)
            val transparent=AtomicBoolean(false);val opaque=AtomicBoolean(false)
            val videoReady=CountDownLatch(1)
            val stereoReady=AtomicBoolean(false)
            val frameCount=AtomicInteger(0)
            val liveSessions=AtomicInteger(0)
            val drainFailure=AtomicReference<Throwable?>(null)
            reader.setOnImageAvailableListener({ source ->
                try { source.acquireLatestImage()?.use { image ->
                    frameCount.incrementAndGet()
                    if(alphaTest) {
                        val plane=image.planes[0];val pixels=plane.buffer
                        for(y in 0 until image.height step 8) for(x in 0 until image.width step 8) {
                            val a=pixels.get(y*plane.rowStride+x*plane.pixelStride+3).toInt() and 255
                            if(a<8)transparent.set(true)
                            if(a>245)opaque.set(true)
                        }
                    }
                    if(!alphaTest || transparent.get() && opaque.get()) {
                        if(capture && saved.compareAndSet(false,true)) {
                            val plane=image.planes[0];val packed=ByteBuffer.allocate(image.width*image.height*4)
                            for(y in 0 until image.height) {
                                val row=plane.buffer.duplicate();row.position(y*plane.rowStride);row.limit(y*plane.rowStride+image.width*4)
                                packed.put(row)
                            }
                            packed.flip()
                            val bitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
                            bitmap.setPremultiplied(false) // GL drawer writes straight RGBA, unlike Android bitmap default.
                            bitmap.copyPixelsFromBuffer(packed)
                            File(context.getExternalFilesDir(null),"render-output-$attempt.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                            bitmap.recycle()
                        }
                        imageReady.countDown()
                    }
                } }
                catch(error: Exception) { drainFailure.set(error);imageReady.countDown() }
            },Handler(thread.looper))
            var session: AutoCloseable?=null
            var lastStatus="Starting"
            try {
                instrumentation.runOnMainSync {
                    session=WebRtcRenderRepository(context).open(reader.surface,pairing,1280f/610) {
                        lastStatus=it.message
                        if(it.streaming) { stereoReady.set(it.stereo);liveSessions.incrementAndGet();videoReady.countDown() }
                    }
                }
                val received=videoReady.await(25,TimeUnit.SECONDS)
                assertTrue("Session ${attempt+1}: $lastStatus",received)
                if(stereoTest)assertTrue("Stereo format must reach receiver status",stereoReady.get())
                assertTrue("Decoded image did not reach output surface",imageReady.await(5,TimeUnit.SECONDS))
                if(alphaTest) assertTrue("RGBA output needs both clear and covered pixels",transparent.get() && opaque.get())
                if(attempt==0) {
                    val hold=InstrumentationRegistry.getArguments().getString("render_hold_ms")?.toLongOrNull()?.coerceIn(0,15000) ?: 0
                    if(hold>0) {
                        val before=frameCount.get();Thread.sleep(hold)
                        assertTrue("Video stopped during Blender's capture pause: $lastStatus",frameCount.get()>before+10)
                        if(InstrumentationRegistry.getArguments().getString("expect_render_reconnect")=="true")
                            assertTrue("Receiver did not reconnect automatically: $lastStatus",liveSessions.get()>=2)
                    }
                }
                drainFailure.get()?.let { throw AssertionError("Output surface drain failed",it) }
            } finally {
                instrumentation.runOnMainSync { session?.close() }
                reader.setOnImageAvailableListener(null,null)
                thread.quitSafely();thread.join(1000);reader.close()
            }
            // Allow the cancellation finalizer to release its matching signaling lease.
            Thread.sleep(1500)
        }
    }
}
