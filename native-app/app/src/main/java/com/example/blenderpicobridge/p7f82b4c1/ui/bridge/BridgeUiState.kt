package com.example.blenderpicobridge.p7f82b4c1.ui.bridge
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

enum class ToolsTab(val title: String) { MODEL("Model"), RENDER("Render"), ALIGN("Align"), CONNECTION("Connection") }
data class BridgeUiState(
    val phase: String = "disconnected", val endpoint: String = "", val token: String = "",
    val modelName: String = "Your Blender scene, beside your desktop", val hasModel: Boolean = false,
    val status: String = "Disconnected", val note: String = "Enter the bridge URL and pairing token to begin.",
    val frame: Int = 1, val revision: String = "—", val scale: Float = 1f, val playing: Boolean = false,
    val fitScale: Float = 1f,
    val modelVisible: Boolean = true,
    val sourceHidden: Boolean = false, val hideSource: Boolean = true, val hover: Boolean = false, val collapsed: Boolean = false, val connectionPanel: Boolean = true,
    val toolsTab: ToolsTab = ToolsTab.MODEL, val advancedAlignment: Boolean = false,
    val palmTools: Boolean = false, val palmStatus: String = "Room only · draggable launcher always available",
    val room: Boolean = false, val autoRoom: Boolean = false, val enteringRoom: Boolean = false,
    val monitor: MonitorCalibration = MonitorCalibration(),
    val renderWindow: RenderWindowState=RenderWindowState(),val renderStatus: String="Off",val renderStreaming: Boolean=false,
    val renderAvailable: Boolean=false,
    val handleError: String? = null,
    val mode: InspectionMode = InspectionMode.SCALE, val explosion: Float = 0f, val partCount: Int = 0,
    val scanPurpose: ScanPurpose? = null, val scanRequest: Int = 0, val scanStatus: String = "",
    val measuredScreen: MeasuredScreen? = null,
    val manualConnection: Boolean = false,
    val touchPhase: TouchAlignmentPhase = TouchAlignmentPhase.NONE, val touchingHand: TouchingHand = TouchingHand.RIGHT,
    val touchCount: Int = 0, val touchStatus: String = "Bring the touching fingertip into view.",
    val touchedMonitor: MonitorCalibration? = null, val touchApplied: Boolean = false,
    val markerPhase: MarkerPhase = MarkerPhase.NONE, val markerSettings: MarkerSettings = MarkerSettings(),
    val markerFrame: MarkerScanFrame? = null,
    val linkViewport: Boolean = false, val viewportAllowed: Boolean = false,
)
sealed interface BridgeUiEvent {
    data class SelectTab(val tab: ToolsTab) : BridgeUiEvent
    data class AdvancedAlignment(val visible: Boolean) : BridgeUiEvent
    data class PalmTools(val enabled: Boolean) : BridgeUiEvent
    data class PalmStatus(val message: String) : BridgeUiEvent
    data object RecenterTools : BridgeUiEvent
    data object OpenMarkerAlignment : BridgeUiEvent
    data class MarkerSideChanged(val value: Float) : BridgeUiEvent
    data class MarkerCurveChanged(val value: Float) : BridgeUiEvent
    data object StartMarkerScan : BridgeUiEvent
    data object PauseMarkerReview : BridgeUiEvent
    data object UseMarkerGeometry : BridgeUiEvent
    data class WorldMonitorApplied(val request: WorldMonitorRequest,val calibration: MonitorCalibration) : BridgeUiEvent
    data object BackFromMarkers : BridgeUiEvent
    data object OpenTouchAlignment : BridgeUiEvent
    data class TouchHandChanged(val hand: TouchingHand) : BridgeUiEvent
    data object StartTouchAlignment : BridgeUiEvent
    data object RestartTouchAlignment : BridgeUiEvent
    data class CancelTouchAlignment(val request: Int? = null) : BridgeUiEvent
    data class TouchProgress(val request: Int,val count: Int,val status: String) : BridgeUiEvent
    data class TouchMeasured(val request: Int,val calibration: MonitorCalibration) : BridgeUiEvent
    data class TouchInvalid(val request: Int,val message: String) : BridgeUiEvent
    data object UseTouchedAlignment : BridgeUiEvent
    data class ScanRequested(val purpose: ScanPurpose) : BridgeUiEvent
    data object ScanAuthorized : BridgeUiEvent
    data class ScanFailed(val message: String) : BridgeUiEvent
    data object StopScan : BridgeUiEvent
    data object UseMeasuredSize : BridgeUiEvent
    data object ManualConnection : BridgeUiEvent
    data class LinkViewport(val enabled: Boolean) : BridgeUiEvent
    data class MoveViewport(val delta: ViewportDelta) : BridgeUiEvent
    data class TrackedCameraFrame(val camera: TrackedViewportCamera) : BridgeUiEvent
    data class EndpointChanged(val value: String) : BridgeUiEvent
    data class TokenChanged(val value: String) : BridgeUiEvent
    data object Connect : BridgeUiEvent
    data object Refresh : BridgeUiEvent
    data object Disconnect : BridgeUiEvent
    data object Reset : BridgeUiEvent
    data class SourceHidingChanged(val enabled: Boolean) : BridgeUiEvent
    data class SourceHidden(val hidden: Boolean) : BridgeUiEvent
    data class ModelVisibilityChanged(val visible: Boolean) : BridgeUiEvent
    data class HoverChanged(val enabled: Boolean) : BridgeUiEvent
    data class CollapseChanged(val collapsed: Boolean) : BridgeUiEvent
    data class ConnectionPanelChanged(val visible: Boolean) : BridgeUiEvent
    data class RoomChanged(val room: Boolean) : BridgeUiEvent
    data class RoomPending(val pending: Boolean) : BridgeUiEvent
    data object Fit : BridgeUiEvent
    data object SourceSize : BridgeUiEvent
    data class MonitorHandleMoved(val index: Int,val delta: List<Float>) : BridgeUiEvent
    data class RenderEnabled(val enabled: Boolean): BridgeUiEvent
    data class RenderFaceUser(val enabled: Boolean): BridgeUiEvent
    data class RenderFitModel(val enabled: Boolean): BridgeUiEvent
    data class RenderHideBorder(val hidden: Boolean?): BridgeUiEvent
    data class RenderLocked(val locked: Boolean): BridgeUiEvent
    data class RenderMode(val mode: RenderWindowMode): BridgeUiEvent
    data class RenderStatus(val value: RenderVideoStatus): BridgeUiEvent
    data class RenderProjection(val valid: Boolean): BridgeUiEvent
    data object RecenterRender : BridgeUiEvent
    data object RetryRender : BridgeUiEvent
    data object AlignMonitor : BridgeUiEvent
    data object LockMonitor : BridgeUiEvent
    data object AlignmentLocating : BridgeUiEvent
    data class AlignmentRestored(val monitor: MonitorCalibration, val handles: MonitorHandleDraft) : BridgeUiEvent
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
