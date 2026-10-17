package com.example.blenderpicobridge.p7f82b4c1.ui.bridge
import com.example.blenderpicobridge.p7f82b4c1.FakeBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeBridgeRepository
    private lateinit var model: BridgeViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBridgeRepository()
        model = BridgeViewModel(repository.pairing, ConnectBridgeUseCase(repository))
    }
    @After fun finish() { model.onEvent(BridgeUiEvent.Disconnect); Dispatchers.resetMain() }
    @Test fun hidingNativeModelSurvivesUpdatesWithoutChangingInteractionOrVideo() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.Connect); runCurrent()
        val packet = FramePacket("session", 4, "a".repeat(64), null, "Robot", 60, false, 2f, emptyList(), null, 1, 1)
        repository.events.emit(BridgeUpdate.Model(packet, File("model.glb"))); runCurrent()
        model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!, .1f))
        model.onEvent(BridgeUiEvent.HoverChanged(true))
        val before = model.scene.value
        assertTrue(model.state.value.modelVisible)
        model.onEvent(BridgeUiEvent.ModelVisibilityChanged(false))
        assertFalse(model.state.value.modelVisible)
        assertEquals(before.copy(modelVisible = false), model.scene.value)
        model.onEvent(BridgeUiEvent.ModeChanged(InspectionMode.EXPLODE))
        model.onEvent(BridgeUiEvent.ExplosionChanged(.4f))
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence = 5, frame = 61))); runCurrent()
        repository.events.emit(BridgeUpdate.Model(packet.copy(sequence = 6, revision = "b".repeat(64)), File("updated.glb"))); runCurrent()
        assertFalse(model.scene.value.modelVisible)
        assertEquals(.4f, model.scene.value.explosion, .0001f)
        val hidden = model.scene.value
        model.onEvent(BridgeUiEvent.ModelVisibilityChanged(true))
        assertTrue(model.state.value.modelVisible)
        assertEquals(hidden.copy(modelVisible = true), model.scene.value)
    }
    @Test fun initLoadsSavedPairingWithoutClaimingConnected() {
        assertEquals(repository.pairing.endpoint, model.state.value.endpoint)
        assertEquals("disconnected", model.state.value.phase)
        assertFalse(model.state.value.hasModel)
    }
    @Test fun connectionStartsScanningAndManualOrCollapseStopsIt() {
        model.onEvent(BridgeUiEvent.ConnectionPanelChanged(true))
        assertEquals(ScanPurpose.PAIRING,model.state.value.scanPurpose)
        assertFalse(model.state.value.manualConnection)
        model.onEvent(BridgeUiEvent.ManualConnection)
        assertNull(model.state.value.scanPurpose)
        assertTrue(model.state.value.manualConnection)
        model.onEvent(BridgeUiEvent.ScanRequested(ScanPurpose.DEPTH))
        model.onEvent(BridgeUiEvent.CollapseChanged(true))
        assertNull(model.state.value.scanPurpose)
    }
    @Test fun viewportLinkRequiresServerOptInAndAggregatesGestureEvents() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.LinkViewport(true))
        assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.Connect); runCurrent()
        val packet = FramePacket("session",4,"a".repeat(64),null,"Model",1,false,0f,emptyList(),null,0,0,viewportControlEnabled=true)
        repository.events.emit(BridgeUpdate.Model(packet,File("${packet.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.LinkViewport(true))
        assertTrue(model.state.value.linkViewport)
        repeat(3) { model.onEvent(BridgeUiEvent.MoveViewport(ViewportDelta(pan=listOf(.1f,0f,0f),zoom=1.1f))) }
        advanceTimeBy(80);runCurrent()
        assertEquals(1,repository.viewportCommands.size)
        assertEquals(.3f,repository.viewportCommands[0].pan[0],.00001f)
        assertEquals(1.331f,repository.viewportCommands[0].zoom,.00001f)
        model.onEvent(BridgeUiEvent.LinkViewport(false))
    }
    @Test fun successfulModelLoadsAndTimelineFollowsIncomingFrame() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.Connect); runCurrent()
        val packet = FramePacket("session", 4, "a".repeat(64), null, "Robot", 60, false, 2.458f, emptyList(), null, 1, 1)
        repository.events.emit(BridgeUpdate.Model(packet, File("${packet.revision}.glb"))); runCurrent()
        model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!, .1f))
        repository.events.emit(BridgeUpdate.Frame(packet)); runCurrent()
        assertEquals(60, model.state.value.frame)
        assertEquals("live", model.state.value.phase)
        model.onEvent(BridgeUiEvent.Disconnect)
        assertTrue(model.state.value.hasModel)
        assertEquals(packet, model.scene.value.packet)
    }
    @Test fun invalidPairingDoesNotStartConnection() {
        model.onEvent(BridgeUiEvent.EndpointChanged("file:///tmp"))
        model.onEvent(BridgeUiEvent.Connect)
        assertEquals("error", model.state.value.phase)
        assertEquals("http://192.168.1.2:8766", repository.pairing.endpoint)
    }
    @Test fun emptySceneOffersRecoveryWithoutInventingModel() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.Connect); runCurrent()
        repository.events.emit(BridgeUpdate.Status("empty", "Select a model in Blender")); runCurrent()
        assertEquals("empty", model.state.value.phase)
        assertFalse(model.state.value.hasModel)
        assertNull(model.scene.value.file)
    }
    @Test fun refreshAndResetRetainAuthoredPoseWhileResettingInspection() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.ModelReady("a".repeat(64), .1f))
        model.onEvent(BridgeUiEvent.InspectionChanged(2f))
        model.onEvent(BridgeUiEvent.Refresh); runCurrent()
        assertEquals("refreshing", model.state.value.phase)
        model.onEvent(BridgeUiEvent.Reset)
        assertEquals(1f, model.state.value.scale, 0f)
        assertEquals(1, model.scene.value.reset)
    }
    @Test fun modeSwitchPreservesSeparationAndResetAssemblesWithoutChangingTimeline() = runTest(dispatcher) {
        model.onEvent(BridgeUiEvent.Connect); runCurrent()
        val packet = FramePacket("s", 4, "a".repeat(64), null, "Car", 60, true, 2.458f,
            emptyList(), null, 1, 1)
        repository.events.emit(BridgeUpdate.Model(packet, File("${packet.revision}.glb"))); runCurrent()
        model.onEvent(BridgeUiEvent.ModeChanged(InspectionMode.EXPLODE))
        model.onEvent(BridgeUiEvent.ExplosionChanged(.65f))
        model.onEvent(BridgeUiEvent.ModeChanged(InspectionMode.SCALE))
        assertEquals(.65f, model.scene.value.explosion, 0f)
        assertEquals(InspectionMode.SCALE, model.scene.value.mode)
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence = 8, frame = 61))); runCurrent()
        assertEquals(.65f, model.scene.value.explosion, 0f)
        model.onEvent(BridgeUiEvent.Reset)
        assertEquals(0f, model.scene.value.explosion, 0f)
        assertEquals(61, model.scene.value.packet!!.frame)
        assertTrue(model.scene.value.packet!!.playing)
    }
    @Test fun quietAndHoverDoNotResetInspectionOrAnimation() {
        model.onEvent(BridgeUiEvent.InspectionChanged(2f))
        model.onEvent(BridgeUiEvent.ExplosionChanged(.7f))
        val reset = model.scene.value.reset
        assertFalse(model.state.value.hover)
        model.onEvent(BridgeUiEvent.HoverChanged(true))
        model.onEvent(BridgeUiEvent.CollapseChanged(true))
        assertTrue(model.state.value.collapsed)
        assertTrue(model.scene.value.hover)
        assertEquals(.7f, model.scene.value.explosion, 0f)
        assertEquals(2f, model.state.value.scale, 0f)
        assertEquals(reset, model.scene.value.reset)
    }
    @Test fun monitorResizeIsIndependentAndRejectsInvalidTracking() {
        model.onEvent(BridgeUiEvent.RoomChanged(true)); model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.MonitorWidth(1.2f))
        assertEquals(.34f, model.scene.value.monitor.height, 0f)
        model.onEvent(BridgeUiEvent.MonitorHeight(Float.NaN))
        assertEquals(.34f, model.scene.value.monitor.height, 0f)
        model.onEvent(BridgeUiEvent.MonitorHeight(8f))
        assertEquals(2f, model.scene.value.monitor.height, 0f)
        model.onEvent(BridgeUiEvent.LockMonitor)
        assertTrue(model.scene.value.monitor.locked)
        assertFalse(model.scene.value.monitor.editing)
    }
    @Test fun resetTakeRequiresLockedMonitorAndDoesNotAssembleParts() {
        model.onEvent(BridgeUiEvent.ExplosionChanged(.6f))
        model.onEvent(BridgeUiEvent.ResetTake)
        assertEquals(SceneCommand.NONE, model.scene.value.command)
        model.onEvent(BridgeUiEvent.RoomChanged(true)); model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.LockMonitor); model.onEvent(BridgeUiEvent.ResetTake)
        assertEquals(SceneCommand.RESET_TAKE, model.scene.value.command)
        assertEquals(.6f, model.scene.value.explosion, 0f)
    }

    @Test fun disablingSourceSettingRestoresAndPersistsWithoutResettingModel() = runTest(dispatcher) {
        var saved: Boolean? = null
        val settingsModel = BridgeViewModel(repository.pairing, ConnectBridgeUseCase(repository), saveSourceOption = { saved = it })
        settingsModel.onEvent(BridgeUiEvent.RoomChanged(true))
        settingsModel.onEvent(BridgeUiEvent.SourceHidden(true)); runCurrent()
        assertEquals(listOf(true), repository.visibility)
        settingsModel.onEvent(BridgeUiEvent.SourceHidingChanged(false)); runCurrent()
        assertEquals(false, saved)
        assertFalse(settingsModel.scene.value.hideSource)
        assertEquals(listOf(true, false), repository.visibility)
        settingsModel.onEvent(BridgeUiEvent.SourceHidden(true)); runCurrent()
        assertFalse(repository.visibility.last())
        settingsModel.onEvent(BridgeUiEvent.Disconnect)
    }

    @Test fun touchCandidateNeedsExplicitAcceptanceAndStaleDisposalCannotCancelReview() = runTest(dispatcher) {
        val request = beginTouch()
        val before = model.scene.value.monitor
        val candidate = before.copy(width=1.1f,height=.62f,position=listOf(.2f,1f,-.8f))
        model.onEvent(BridgeUiEvent.TouchMeasured(request,candidate))
        model.onEvent(BridgeUiEvent.CancelTouchAlignment(request))
        assertEquals(TouchAlignmentPhase.REVIEW,model.state.value.touchPhase)
        assertEquals(before,model.scene.value.monitor)
        model.onEvent(BridgeUiEvent.UseTouchedAlignment)
        assertEquals(candidate,model.scene.value.monitor)
        assertFalse(model.scene.value.monitor.locked)
        assertTrue(model.state.value.touchApplied)
    }
    @Test fun leavingAlignmentRejectsLateTouchesAndPreservesPreviousCalibration() = runTest(dispatcher) {
        val request = beginTouch()
        val before = model.scene.value.monitor
        model.onEvent(BridgeUiEvent.CancelTouchAlignment())
        model.onEvent(BridgeUiEvent.TouchMeasured(request,before.copy(width=1.5f)))
        assertEquals(before,model.scene.value.monitor)
        assertNull(model.scene.value.touchAlignment)
        assertEquals(TouchAlignmentPhase.NONE,model.state.value.touchPhase)
        beginTouch()
        model.onEvent(BridgeUiEvent.RoomChanged(false))
        assertNull(model.scene.value.touchAlignment)
    }
    private suspend fun TestScope.beginTouch(): Int {
        model.onEvent(BridgeUiEvent.Connect);runCurrent()
        val board=CalibrationBoard(1920,1080,listOf(MarkerRect(0,24,24,96,96),MarkerRect(1,1800,24,96,96),MarkerRect(2,1800,960,96,96),MarkerRect(3,24,960,96,96)))
        val packet=FramePacket("touch",1,"a".repeat(64),null,"Model",1,false,0f,emptyList(),null,0,0,board=board)
        repository.events.emit(BridgeUpdate.Model(packet,File("model.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.RoomChanged(true));model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.OpenTouchAlignment);model.onEvent(BridgeUiEvent.StartTouchAlignment)
        return requireNotNull(model.scene.value.touchAlignment).id
    }

}
