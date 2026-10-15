package com.example.blenderpicobridge.p7f82b4c1.domain.model

import java.io.File

data class Pairing(val endpoint: String = "", val token: String = "", val expectedSession: String? = null)
data class MarkerRect(val id: Int, val x: Int, val y: Int, val width: Int, val height: Int)
data class CalibrationBoard(val width: Int, val height: Int, val markers: List<MarkerRect>)
data class MeasuredScreen(val width: Float, val height: Float)
data class ViewportDelta(val pan: List<Float> = listOf(0f,0f,0f), val rotation: List<Float> = listOf(0f,0f,0f,1f), val zoom: Float = 1f)
data class ViewportFrame(val pivot: List<Float>, val distance: Float)
enum class ScanPurpose { PAIRING, DEPTH }
sealed interface ScanUpdate {
    data class Status(val text: String) : ScanUpdate
    data class Paired(val pairing: Pairing) : ScanUpdate
    data class Measured(val screen: MeasuredScreen) : ScanUpdate
}
data class NodePose(val name: String, val position: List<Float>, val quaternion: List<Float>, val scale: List<Float>)
data class ExplosionPart(val name: String, val offset: List<Float>)
enum class InspectionMode { SCALE, EXPLODE }
enum class SceneCommand { NONE, FIT, SOURCE_SIZE, STAGE_TAKE, RESET_TAKE }
data class MonitorCalibration(val enabled: Boolean = false, val editing: Boolean = false,
    val locked: Boolean = false, val width: Float = .6f, val height: Float = .34f,
    val position: List<Float> = listOf(0f, 1.2f, -1f), val quaternion: List<Float> = listOf(0f, 0f, 0f, 1f))
data class InspectionPose(val position: List<Float>, val quaternion: List<Float>, val scale: Float,
    val fitScale: Float, val fitPosition: List<Float>)
data class FramePacket(
    val session: String, val sequence: Long, val revision: String?, val modelPath: String?,
    val modelName: String, val frame: Int, val playing: Boolean, val timeSeconds: Float,
    val poses: List<NodePose>, val error: String?, val textureCount: Int, val animationCount: Int,
    val explodeParts: List<ExplosionPart> = emptyList(), val viewQuaternion: List<Float>? = null, val sourceHidden: Boolean = false,
    val board: CalibrationBoard? = null, val viewportControlEnabled: Boolean = false,
    val viewport: ViewportFrame? = null,
)
sealed interface BridgeUpdate {
    data class Status(val phase: String, val message: String) : BridgeUpdate
    data class Frame(val packet: FramePacket) : BridgeUpdate
    data class Model(val packet: FramePacket, val file: File) : BridgeUpdate
}
data class SceneFeed(val file: File? = null, val packet: FramePacket? = null, val reset: Int = 0,
    val mode: InspectionMode = InspectionMode.SCALE, val explosion: Float = 0f,
    val hideSource: Boolean = true, val hover: Boolean = false, val monitor: MonitorCalibration = MonitorCalibration(),
    val command: SceneCommand = SceneCommand.NONE, val commandSequence: Int = 0,
    val previewPose: InspectionPose? = null, val roomPose: InspectionPose? = null, val debugReveal: Float? = null,
    val linkViewport: Boolean = false, val touchAlignment: TouchAlignmentRequest? = null)
