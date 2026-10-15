package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SharedMemory
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
class SpatialCameraRepository(private val context: Context) {
    fun scan(purpose: ScanPurpose, board: CalibrationBoard?): Flow<ScanUpdate> = flow {
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Camera permission denied. Use manual connection." }
        if (purpose == ScanPurpose.DEPTH) {
            check(context.checkSelfPermission("com.picovr.permission.SPATIAL_DATA") == PackageManager.PERMISSION_GRANTED) { "Spatial-data permission is required for screen depth." }
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
        // Device intrinsics confirm square pixels at 4:3 (fx == fy). A 16:9 request
        // stretches the camera image and prevents QR finder-pattern detection.
        val width = 1280; val height = 960
        val session = requireNotNull(instance.createSession(SpatialMLSession.InitInfo(
            imageWidth=width, imageHeight=height, containerWidth=0, containerHeight=0, containerDepth=0,
            containerType=SpatialMLSession.ContainerType.DISABLED))) { "PICO camera session unavailable" }
        val tensors = mutableListOf<GlobalTensor>()
        val pipelines = mutableListOf<Pipeline>()
        fun tensor(info: Tensor.InitInfo) = session.newGlobalTensor(info).also(tensors::add)
        try {
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
            val detector = if (purpose == ScanPurpose.DEPTH) ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)) else null
            var stability = StabilizeScreenSizeUseCase()
            var lastMarkers: Set<Int>? = null
            var frames = 0
            var previousHash: Int? = null
            Log.i("BridgeCamera", "pipeline configured ${width}x$height")
            withTimeout(45000) {
                while (currentCoroutineContext().isActive) {
                    // Serialize capture, CPU detection and depth: never overwrite same-frame tensors.
                    if (frames == 0) emit(ScanUpdate.Status("Waiting for first camera frame…"))
                    val captureTask = capture.submit(captureBindings,null,null)
                    val rgb = withTimeout(5000) { left.readbackContentSuspend() }.use { content ->
                        val buffer = content.buffer.duplicate().apply { rewind() }
                        require(buffer.remaining() == width*height*3) { "Unexpected camera image format" }
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    frames++
                    if (frames == 1) {
                        intrinsics.readbackContentSuspend().use { content ->
                            val bytes = content.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
                            val values = FloatArray(9).also { bytes.asFloatBuffer().get(it) }
                            Log.i("BridgeCamera", "intrinsics fx=${values[0]} fy=${values[4]}")
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
            // Clear the public PhantomReference before explicit destruction to avoid a second GC cleanup.
            pipelines.asReversed().forEach { it.destructor.clear(); it.destructor.destroy() }
            tensors.asReversed().forEach { it.destructor.clear(); it.destructor.destroy() }
            session.destructor.clear(); session.destructor.destroy()
        }
    }.flowOn(Dispatchers.IO)

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
