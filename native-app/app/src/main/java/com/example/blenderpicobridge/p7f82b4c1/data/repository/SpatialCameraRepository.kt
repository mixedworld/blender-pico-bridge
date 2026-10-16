package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import com.pico.spatial.ml.readback.readbackContentSuspend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.*
import java.nio.ByteOrder

/** Camera frames remain in app memory; no image upload, disk cache or diagnostic permission bypass. */
class SpatialCameraRepository(private val context: Context, private val diagnostics: Boolean = false, private val automaticWorld: Boolean = true) : CameraRepository {
    override fun scan(purpose: ScanPurpose, board: CalibrationBoard?, settings: StateFlow<MarkerSettings>?): Flow<ScanUpdate> = flow {
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Camera permission denied. Use manual connection." }
        if (purpose == ScanPurpose.DEPTH) {
            check(context.checkSelfPermission("com.picovr.permission.SPATIAL_DATA") == PackageManager.PERMISSION_GRANTED) { "Spatial-data permission is required for screen depth." }
        }
        if (purpose != ScanPurpose.PAIRING) {
            requireNotNull(board) { "Show QR and markers in Blender before measuring." }
            check(OpenCVLoader.initLocal()) { "Marker detection unavailable. Use manual alignment." }
        }
        Log.i("BridgeCamera", "scan start purpose=$purpose")
        emit(ScanUpdate.Status(if (purpose == ScanPurpose.PAIRING) "Looking for the Blender QR…" else "Looking for four screen markers…"))
        emit(ScanUpdate.Status("Opening PICO camera service…"))
        val instance = SpatialMLInstance.create(context.applicationContext)
        withTimeout(8000) { while (!instance.ready) delay(100) }
        Log.i("BridgeCamera", "service ready")
        emit(ScanUpdate.Status("Starting camera capture…"))
        // QR scanning was verified at this 4:3 capture size. Use the capture
        // matrix unless a measured, device-scoped marker profile passes validation.
        val width = 1280; val height = 960
        val session = requireNotNull(instance.createSession(SpatialMLSession.InitInfo(
            imageWidth=width, imageHeight=height, containerWidth=0, containerHeight=0, containerDepth=0,
            containerType=SpatialMLSession.ContainerType.DISABLED))) { "PICO camera session unavailable" }
        val tensors = mutableListOf<GlobalTensor>()
        val pipelines = mutableListOf<Pipeline>()
        val head=if(purpose==ScanPurpose.MARKER_POSE && automaticWorld) StationaryHeadRepository() else null
        fun tensor(info: Tensor.InitInfo) = session.newGlobalTensor(info).also(tensors::add)
        try {
            head?.start()
            val worldSolver=HeadCameraCalibrationRepository()
            val worldSamples=mutableListOf<HeadMarkerSample>()
            var worldAlignment: WorldMarkerAlignment?=null
            var previousCapture: ByteArray?=null
            var worldK: FloatArray?=null
            var lastWorldAttempt=0
            val imageInfo = Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8_U, intArrayOf(height,width))
            val left = tensor(imageInfo); val right = tensor(imageInfo)
            val timestamp = tensor(Tensor.TimeStampInitInfo())
            val intrinsics = tensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32,intArrayOf(3,3)))
            val capture = session.newPipeline().also(pipelines::add)
            val captureBindings = listOf(left,right,timestamp,intrinsics).associate { capture.newPlaceholderLike(it) to it }
            val captureRefs = captureBindings.keys.toList()
            capture.rectifiedVSTAccess(rightImageResult=captureRefs[1],leftImageResult=captureRefs[0],timestampResult=captureRefs[2],cameraMatrixResult=captureRefs[3])
            val samplesPerMarker = 25
            val pointCount = samplesPerMarker*4
            val uv = if (purpose == ScanPurpose.DEPTH) tensor(Tensor.Point2ArrayInitInfo(Tensor.DataType.INT32,pointCount)) else null
            val xyz = if (purpose == ScanPurpose.DEPTH) tensor(Tensor.Point3ArrayInitInfo(Tensor.DataType.FLOAT32,pointCount)) else null
            val projection = if (uv != null && xyz != null) session.newPipeline().also(pipelines::add) else null
            val depthBindings = if (projection != null) listOf(uv!!,timestamp,intrinsics,left,right,xyz!!).associate { projection.newPlaceholderLike(it) to it } else emptyMap()
            if (projection != null) {
                val refs = depthBindings.keys.toList()
                projection.uvTo3DInCameraSpace(refs[0],refs[1],refs[2],refs[3],refs[4],refs[5])
            }
            val reader = QRCodeReader()
            val detector = if (purpose != ScanPurpose.PAIRING) ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)) else null
            var stability = StabilizeScreenSizeUseCase()
            val markerStability=StabilizeMarkerEstimateUseCase()
            val markerPose=KnownMarkerPoseRepository()
            var lastSettings: MarkerSettings?=null
            val cameraProfile=MarkerCameraProfileRepository(context).load()
            var cameraK=FloatArray(9)
            var curveHint: KnownMarkerPoseRepository.Fit?=null
            var diagnosticCount=0
            var lastMarkers: Set<Int>? = null
            var frames = 0
            var previousHash: Int? = null
            Log.i("BridgeCamera", "pipeline configured ${width}x$height")
            withTimeout(if (purpose==ScanPurpose.MARKER_POSE) 180000L else 45000L) {
                while (currentCoroutineContext().isActive) {
                    // Serialize capture, CPU detection and depth: never overwrite same-frame tensors.
                    if (frames == 0) emit(ScanUpdate.Status("Waiting for first camera frame…"))
                    val captureStarted=SystemClock.elapsedRealtime()
                    val captureTask = capture.submit(captureBindings,null,null)
                    val rgb = withTimeout(5000) { left.readbackContentSuspend() }.use { content ->
                        val buffer = content.buffer.duplicate().apply { rewind() }
                        require(buffer.remaining() == width*height*3) { "Unexpected camera image format" }
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    frames++
                    if (frames == 1 || purpose==ScanPurpose.MARKER_POSE) {
                        intrinsics.readbackContentSuspend().use { content ->
                            val bytes = content.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
                            val sdkK = FloatArray(9).also { bytes.asFloatBuffer().get(it) }
                            cameraK=if(purpose==ScanPurpose.MARKER_POSE) SelectMarkerIntrinsicsUseCase()(sdkK,width,height,cameraProfile) else sdkK
                            if(frames==1) Log.i("BridgeCamera","cameraModel=${if(cameraK.contentEquals(sdkK)) "sdk" else "measured-local-profile"}")
                            if (frames==1) Log.i("BridgeCamera", "intrinsics fx=${cameraK[0]} fy=${cameraK[4]}")
                        }
                    }
                    if (frames == 1 || frames % 10 == 0) {
                        var min = 255; var max = 0; var sum = 0L; var hash = 1
                        for (i in rgb.indices step 97) {
                            val value = rgb[i].toInt() and 255
                            min = minOf(min,value); max = maxOf(max,value); sum += value
                            hash = 31*hash+value
                        }
                        Log.i("BridgeCamera", "frame=$frames bytes=${rgb.size} range=$min..$max mean=${sum/((rgb.size+96)/97)} changed=${previousHash != hash}")
                        previousHash = hash
                        emit(ScanUpdate.Status(if (purpose == ScanPurpose.PAIRING) "Camera live · frame $frames · looking for QR…" else "Camera live · frame $frames · looking for markers…"))
                    }
                    if (purpose == ScanPurpose.PAIRING) {
                        val pixels = IntArray(width*height) { i ->
                            (0xff shl 24) or ((rgb[i*3].toInt() and 255) shl 16) or ((rgb[i*3+1].toInt() and 255) shl 8) or (rgb[i*3+2].toInt() and 255)
                        }
                        val text = try { reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels))), mapOf(DecodeHintType.TRY_HARDER to true)).text }
                            catch (_: ReaderException) { null } finally { reader.reset() }
                        if (text != null) {
                            val pairing = runCatching { ReadPairingQrUseCase()(text) }.getOrNull()
                            Log.i("BridgeCamera", "QR decoded validPairing=${pairing != null}")
                            if (pairing != null) { emit(ScanUpdate.Paired(pairing)); return@withTimeout }
                        }
                    } else if (purpose==ScanPurpose.MARKER_POSE) {
                        val config=settings?.value ?: MarkerSettings()
                        if (config!=lastSettings) { markerStability.reset();curveHint=null;lastSettings=config;worldSamples.clear();worldAlignment=null;lastWorldAttempt=0 }
                        if(worldK?.contentEquals(cameraK)==false) { worldSamples.clear();worldAlignment=null;lastWorldAttempt=0 }
                        worldK=cameraK.copyOf()
                        val observed=markerPolygons(rgb,width,height,detector!!)
                        val fit=markerPose.fit(observed,board!!,config,cameraK)
                        if (frames%10==0 && observed.size>=3 && (fit?.estimate?.errorPx ?: Float.POSITIVE_INFINITY)>3f) {
                            curveHint=markerPose.suggestCurve(observed,board,config,cameraK)
                            Log.i("BridgeMarker","curveHint=${curveHint?.estimate?.curveDegrees} hintErrorPx=${curveHint?.estimate?.errorPx}")
                        }
                        val hint=curveHint?.estimate?.takeIf { it.errorPx<=3f && it.errorPx+1f<(fit?.estimate?.errorPx ?: Float.POSITIVE_INFINITY) }
                        if (diagnostics && frames%20==0 && observed.size>=4 && diagnosticCount++<3) {
                            Log.i("BridgeMarkerProbe","LEFT "+markerPose.diagnose(observed,board,config,cameraK))
                            val rightRgb=right.readbackContentSuspend().use { content ->
                                val buffer=content.buffer.duplicate().apply { rewind() }
                                ByteArray(buffer.remaining()).also { buffer.get(it) }
                            }
                            val rightObserved=markerPolygons(rightRgb,width,height,detector)
                            if(rightObserved.size>=4) Log.i("BridgeMarkerProbe","RIGHT "+markerPose.diagnose(rightObserved,board,config,cameraK))
                        }
                        val ready=markerStability.add(fit?.estimate)
                        var worldStatus="Hold your head still for a second, then turn or tilt to another angle"
                        if(head!=null) {
                            val stamp=timestamp.readbackContentSuspend().use { value ->
                                val bytes=value.buffer.duplicate().apply { rewind() }
                                ByteArray(bytes.remaining()).also { bytes.get(it) }
                            }
                            val fresh=previousCapture?.let { !it.contentEquals(stamp) } ?: false
                            previousCapture=stamp
                            val headPose=head.steady(captureStarted,SystemClock.elapsedRealtime())
                            if(frames%20==0) Log.i("BridgeWorld","capture ${head.diagnostic} steady=${headPose!=null} fresh=$fresh frameMs=${SystemClock.elapsedRealtime()-captureStarted} views=${worldSamples.size}")
                            if(head.frames==0) worldStatus="Waiting for head tracking · scan in Room mode"
                            if(ready && fit!=null && fit.estimate.markerCount>=3 && fresh && headPose!=null && worldAlignment==null) {
                                val distinct=worldSamples.all { it.globalFromHead.angle(headPose)>Math.toRadians(3.0) || it.globalFromHead.distance(headPose)>.035 }
                                if(distinct && worldSamples.size<40) {
                                    worldSamples+=HeadMarkerSample(headPose,RigidPose(fit.cameraPose.rotation.map(Float::toDouble),fit.cameraPose.translation.map(Float::toDouble)))
                                    if(worldSamples.size!=lastWorldAttempt) {
                                        lastWorldAttempt=worldSamples.size
                                        worldAlignment=worldSolver.solve(worldSamples)?.alignment
                                        Log.i("BridgeWorld","samples=${worldSamples.size} solved=${worldAlignment!=null} positionRms=${worldAlignment?.positionRms} angleRms=${worldAlignment?.angleRms}")
                                    }
                                }
                                worldStatus=worldSolver.status
                            } else if(fit!=null && fit.estimate.markerCount<3) worldStatus="Keep at least three separated markers in view"
                            if(worldAlignment!=null) worldStatus=worldSolver.status
                        }
                        val status=when {
                            !config.confirmed -> "Set the measured black-square side before estimating scale."
                            observed.isEmpty() -> "Searching for Blender markers · keep the center marker visible"
                            fit==null -> markerPose.rejection ?: "Marker fit rejected"
                            fit.estimate.errorPx>3f -> "Fit error %.1f px".format(fit.estimate.errorPx)+(hint?.let { " · try %.0f° curve (%.1f px)".format(it.curveDegrees,it.errorPx) } ?: " · no reliable board pose yet")
                            head!=null && ready -> worldStatus
                            ready -> "Estimate ready · pause to review size and curve"
                            else -> "Hold steady · ${markerStability.acceptedSamples}/8 matching estimates"
                        }
                        // Exact 4:1 resize; outlines remain in original 1280×960 image coordinates.
                        val preview=ByteArray(320*240*3)
                        for (y in 0 until 240) for (x in 0 until 320) {
                            val src=((y*4)*width+x*4)*3;val dst=(y*320+x)*3
                            for (c in 0..2) preview[dst+c]=rgb[src+c]
                        }
                        emit(ScanUpdate.Markers(MarkerScanFrame(config,preview,observed,fit?.projected ?: emptyList(),
                            fit?.outline ?: emptyList(),fit?.estimate,markerStability.acceptedSamples,status,worldRequired=head!=null,worldAlignment=worldAlignment)))
                        if (frames%10==0) Log.i("BridgeMarker","frame=$frames count=${observed.size} stable=${markerStability.acceptedSamples} errorPx=${fit?.estimate?.errorPx} distance=${fit?.estimate?.distance} rejected=${markerPose.rejection} sideMm=${config.sideMm} curve=${config.curveDegrees}")
                    } else {
                        val (centers, found) = markerCenters(rgb,width,height,detector!!)
                        if (found != lastMarkers) {
                            Log.i("BridgeCamera", "markers=${found.sorted().joinToString(",")} count=${found.size}/4")
                            lastMarkers = found
                        }
                        if (centers != null) {
                            SharedMemory.create("screen-marker-uv",pointCount*2*4).use { memory ->
                                val mapped = memory.mapReadWrite().order(ByteOrder.nativeOrder())
                                try { mapped.asIntBuffer().put(centers) } finally { SharedMemory.unmap(mapped) }
                                uv!!.tensorResource = memory
                            }
                            projection!!.submit(depthBindings,null,captureTask)
                            val points = withTimeout(5000) { xyz!!.readbackContentSuspend() }.use { content ->
                                val bytes = content.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
                                require(bytes.remaining() == pointCount*3*4)
                                FloatArray(pointCount*3).also { bytes.asFloatBuffer().get(it) }.toList().chunked(3)
                            }
                            val measured = MeasureScreenSamplesUseCase()(points,samplesPerMarker,board!!)
                            if (frames == 1 || frames % 10 == 0) Log.i("BridgeCamera", "depth valid=${measured != null} samples=$pointCount accepted=${stability.acceptedSamples}")
                            if (measured == null) {
                                emit(ScanUpdate.Status("Depth samples disagree. Hold still; bad readings are being rejected."))
                            } else {
                                val stable = stability.add(measured)
                                emit(ScanUpdate.Status("Stabilizing depth · ${stability.acceptedSamples}/${stability.requiredSamples}"))
                                if (stable != null) { emit(ScanUpdate.Measured(stable)); return@withTimeout }
                            }
                        } else {
                            stability = StabilizeScreenSizeUseCase()
                            emit(ScanUpdate.Status("Markers visible · ${found.size}/4 · step back until all corners fit"))
                        }
                    }
                    delay(150)
                }
            }
        } catch (error: CancellationException) {
            Log.i("BridgeCamera", "scan ended ${error.javaClass.simpleName}")
            throw error
        } catch (error: Exception) {
            // No exception message: decoded QR values and bearer tokens must never enter logs.
            Log.e("BridgeCamera", "scan failed type=${error.javaClass.simpleName} at=${error.stackTrace.firstOrNull()}")
            throw error
        } finally {
            Log.i("BridgeCamera", "releasing camera session")
            withContext(NonCancellable) { head?.stop() }
            // Clear the public PhantomReference before explicit destruction to avoid a second GC cleanup.
            pipelines.asReversed().forEach { it.destructor.clear(); it.destructor.destroy() }
            tensors.asReversed().forEach { it.destructor.clear(); it.destructor.destroy() }
            session.destructor.clear(); session.destructor.destroy()
        }
    }.flowOn(Dispatchers.IO)

    private fun markerPolygons(rgb: ByteArray,width: Int,height: Int,detector: ArucoDetector): List<MarkerPolygon> {
        val image=Mat(height,width,CvType.CV_8UC3);val gray=Mat();val ids=Mat();val corners=mutableListOf<Mat>()
        return try {
            image.put(0,0,rgb);Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
            detector.detectMarkers(gray,corners,ids)
            (0 until ids.rows()).mapNotNull { i ->
                val id=ids.get(i,0)[0].toInt()
                if (id !in 0..4) null else MarkerPolygon(id,(0..3).map { c ->
                    val p=corners[i].get(0,c);ImagePoint(p[0].toFloat(),p[1].toFloat())
                })
            }
        } finally { image.release();gray.release();ids.release();corners.forEach { it.release() } }
    }

    private fun markerCenters(rgb: ByteArray, width: Int, height: Int, detector: ArucoDetector): Pair<IntArray?,Set<Int>> {
        val image = Mat(height,width,CvType.CV_8UC3); val gray = Mat(); val ids = Mat(); val corners = mutableListOf<Mat>()
        try {
            image.put(0,0,rgb); Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
            detector.detectMarkers(gray,corners,ids)
            val detected = (0 until ids.rows()).associate { ids.get(it,0)[0].toInt() to corners[it] }
            val found = detected.keys.intersect((0..3).toSet())
            if (found.size != 4) return null to found
            return (0..3).flatMap { id ->
                val corners = (0..3).map { detected.getValue(id).get(0,it) }
                (0..4).flatMap { row -> (0..4).flatMap { column ->
                    val u=.25+column*.125; val v=.25+row*.125
                    (0..1).map { axis ->
                        ((1-v)*((1-u)*corners[0][axis]+u*corners[1][axis])+
                            v*((1-u)*corners[3][axis]+u*corners[2][axis])).toInt()
                    }
                } }
            }.toIntArray() to found
        } finally { image.release(); gray.release(); ids.release(); corners.forEach { it.release() } }
    }
}
