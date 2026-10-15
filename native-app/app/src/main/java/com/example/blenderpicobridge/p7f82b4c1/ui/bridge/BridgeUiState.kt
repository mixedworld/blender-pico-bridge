package com.example.blenderpicobridge.p7f82b4c1.ui.bridge
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

data class BridgeUiState(
    val phase: String = "disconnected", val endpoint: String = "", val token: String = "",
    val modelName: String = "Your Blender scene, beside your desktop", val hasModel: Boolean = false,
    val status: String = "Disconnected", val note: String = "Enter the bridge URL and pairing token to begin.",
    val frame: Int = 1, val revision: String = "—", val scale: Float = 1f, val playing: Boolean = false,
    val fitScale: Float = 1f,
    val sourceHidden: Boolean = false, val hideSource: Boolean = true, val hover: Boolean = false, val collapsed: Boolean = false, val connectionPanel: Boolean = false,
    val room: Boolean = false, val autoRoom: Boolean = false, val enteringRoom: Boolean = false,
    val monitor: MonitorCalibration = MonitorCalibration(),
    val mode: InspectionMode = InspectionMode.SCALE, val explosion: Float = 0f, val partCount: Int = 0,
    val scanPurpose: ScanPurpose? = null, val scanRequest: Int = 0, val scanStatus: String = "",
    val measuredScreen: MeasuredScreen? = null,
    val manualConnection: Boolean = false,
    val linkViewport: Boolean = false, val viewportAllowed: Boolean = false,
)
sealed interface BridgeUiEvent {
    data class ScanRequested(val purpose: ScanPurpose) : BridgeUiEvent
    data object ScanAuthorized : BridgeUiEvent
    data class ScanFailed(val message: String) : BridgeUiEvent
    data object StopScan : BridgeUiEvent
    data object UseMeasuredSize : BridgeUiEvent
    data object ManualConnection : BridgeUiEvent
    data class LinkViewport(val enabled: Boolean) : BridgeUiEvent
    data class MoveViewport(val delta: ViewportDelta) : BridgeUiEvent
    data class EndpointChanged(val value: String) : BridgeUiEvent
    data class TokenChanged(val value: String) : BridgeUiEvent
    data object Connect : BridgeUiEvent
    data object Refresh : BridgeUiEvent
    data object Disconnect : BridgeUiEvent
    data object Reset : BridgeUiEvent
    data class SourceHidingChanged(val enabled: Boolean) : BridgeUiEvent
    data class SourceHidden(val hidden: Boolean) : BridgeUiEvent
    data class HoverChanged(val enabled: Boolean) : BridgeUiEvent
    data class CollapseChanged(val collapsed: Boolean) : BridgeUiEvent
    data class ConnectionPanelChanged(val visible: Boolean) : BridgeUiEvent
    data class RoomChanged(val room: Boolean) : BridgeUiEvent
    data class RoomPending(val pending: Boolean) : BridgeUiEvent
    data object Fit : BridgeUiEvent
    data object SourceSize : BridgeUiEvent
    data object AlignMonitor : BridgeUiEvent
    data object LockMonitor : BridgeUiEvent
    data object LeaveMonitor : BridgeUiEvent
    data object StageTake : BridgeUiEvent
    data object ResetTake : BridgeUiEvent
    data class MonitorWidth(val value: Float) : BridgeUiEvent
    data class MonitorHeight(val value: Float) : BridgeUiEvent
    data class MonitorPose(val position: List<Float>, val quaternion: List<Float>) : BridgeUiEvent
    data class PoseSaved(val room: Boolean, val pose: InspectionPose) : BridgeUiEvent
    data class Feedback(val message: String) : BridgeUiEvent
    data class ModelReady(val revision: String, val fitScale: Float) : BridgeUiEvent
    data class FitChanged(val scale: Float) : BridgeUiEvent
    data class SceneError(val message: String) : BridgeUiEvent
    data class InspectionChanged(val scale: Float, val note: String? = null) : BridgeUiEvent
    data class ModeChanged(val mode: InspectionMode) : BridgeUiEvent
    data class ExplosionChanged(val amount: Float) : BridgeUiEvent
}
