package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import com.example.blenderpicobridge.p7f82b4c1.FakeBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.data.repository.CameraRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MarkerAlignmentViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val repository=FakeBridgeRepository()
    private val updates=MutableSharedFlow<ScanUpdate>()
    private lateinit var model: BridgeViewModel
    private val board=CalibrationBoard(1920,1080,listOf(MarkerRect(0,24,24,96,96),MarkerRect(1,1800,24,96,96),MarkerRect(2,1800,960,96,96),MarkerRect(3,24,960,96,96)))
    private val settings=MarkerSettings(44f,60f,true)
    private val packet=FramePacket("markers",1,"a".repeat(64),null,"Model",1,false,0f,emptyList(),null,0,0,board=board)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val camera=object: CameraRepository {
            override fun scan(purpose: ScanPurpose,board: CalibrationBoard?,settings: StateFlow<MarkerSettings>?)=updates
        }
        model=BridgeViewModel(repository.pairing,ConnectBridgeUseCase(repository),camera=ScanCameraUseCase(camera),initialMarkerSettings=settings)
    }
    @After fun finish() { model.onEvent(BridgeUiEvent.Disconnect);Dispatchers.resetMain() }
    private suspend fun TestScope.open() {
        model.onEvent(BridgeUiEvent.Connect);runCurrent()
        repository.events.emit(BridgeUpdate.Model(packet,File("${packet.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.RoomChanged(true));model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.OpenMarkerAlignment)
        model.onEvent(BridgeUiEvent.StartMarkerScan);model.onEvent(BridgeUiEvent.ScanAuthorized);runCurrent()
    }
    private fun frame(config: MarkerSettings=settings,stable: Int=8)=MarkerScanFrame(config,ByteArray(0),emptyList(),emptyList(),emptyList(),
        MarkerEstimate(1.2f,.7f,config.curveDegrees,1f,1f,4),stable,"Estimate ready")
    @Test fun geometryRequiresReviewAndPreservesManualPosition()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(frame()));runCurrent();assertNotNull(model.state.value.markerFrame)
        model.onEvent(BridgeUiEvent.UseMarkerGeometry);assertEquals(before,model.scene.value.monitor)
        model.onEvent(BridgeUiEvent.PauseMarkerReview)
        assertEquals(MarkerPhase.REVIEW,model.state.value.markerPhase);assertNull(model.state.value.scanPurpose)
        model.onEvent(BridgeUiEvent.MarkerCurveChanged(20f));assertEquals(60f,model.state.value.markerSettings.curveDegrees,0f)
        model.onEvent(BridgeUiEvent.UseMarkerGeometry)
        assertEquals(before.position,model.scene.value.monitor.position);assertEquals(before.quaternion,model.scene.value.monitor.quaternion)
        assertEquals(1.2f,model.scene.value.monitor.width,0f);assertEquals(60f,model.scene.value.monitor.curveDegrees,0f)
        assertFalse(model.scene.value.monitor.locked)
    }
    @Test fun liveEditsRejectOldConfigurationFrames()=runTest(dispatcher) {
        open();updates.emit(ScanUpdate.Markers(frame()));runCurrent()
        model.onEvent(BridgeUiEvent.MarkerCurveChanged(35f));assertNull(model.state.value.markerFrame)
        updates.emit(ScanUpdate.Markers(frame()));runCurrent();assertNull(model.state.value.markerFrame)
        updates.emit(ScanUpdate.Markers(frame(settings.copy(curveDegrees=35f),3)));runCurrent()
        model.onEvent(BridgeUiEvent.PauseMarkerReview);assertEquals(MarkerPhase.SCAN,model.state.value.markerPhase)
    }
    @Test fun cancelDiscardsCandidateWithoutChangingPriorCalibration()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(frame()));runCurrent()
        model.onEvent(BridgeUiEvent.BackFromMarkers)
        assertNull(model.state.value.markerFrame);assertEquals(before,model.scene.value.monitor)
    }
    @Test fun missingMeasuredReferenceCannotStartAcquisition() {
        val unconfirmed=BridgeViewModel(repository.pairing,ConnectBridgeUseCase(repository))
        unconfirmed.onEvent(BridgeUiEvent.RoomChanged(true));unconfirmed.onEvent(BridgeUiEvent.AlignMonitor)
        unconfirmed.onEvent(BridgeUiEvent.OpenMarkerAlignment);unconfirmed.onEvent(BridgeUiEvent.StartMarkerScan)
        assertEquals(MarkerPhase.SETUP,unconfirmed.state.value.markerPhase);assertNull(unconfirmed.state.value.scanPurpose)
        assertTrue(unconfirmed.state.value.scanStatus.contains("measured"))
    }
    @Test fun leavingRoomStopsCameraAndClearsItsImage()=runTest(dispatcher) {
        open();updates.emit(ScanUpdate.Markers(frame()));runCurrent()
        model.onEvent(BridgeUiEvent.RoomChanged(false))
        assertNull(model.state.value.scanPurpose);assertNull(model.state.value.markerFrame)
        assertEquals(MarkerPhase.NONE,model.state.value.markerPhase)
    }
    @Test fun resizedBoardInvalidatesEvenFrozenCandidate()=runTest(dispatcher) {
        open();updates.emit(ScanUpdate.Markers(frame()));runCurrent();model.onEvent(BridgeUiEvent.PauseMarkerReview)
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=2,board=board.copy(width=2000))));runCurrent()
        assertEquals(MarkerPhase.SETUP,model.state.value.markerPhase);assertNull(model.state.value.markerFrame)
    }
    private fun worldFrame()=frame().copy(worldRequired=true,worldAlignment=WorldMarkerAlignment(
        RigidPose.fromQuaternion(listOf(.2,1.2,-.8),listOf(0.0,0.0,0.0,1.0)),16,.003,.004))
    @Test fun worldScanCannotAcceptGeometryBeforeWorldCalibrationIsReady()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(frame().copy(worldRequired=true)));runCurrent()
        model.onEvent(BridgeUiEvent.PauseMarkerReview)
        assertEquals(MarkerPhase.SCAN,model.state.value.markerPhase)
        model.onEvent(BridgeUiEvent.UseMarkerGeometry)
        assertEquals(before,model.scene.value.monitor);assertNull(model.scene.value.worldMonitor)
    }
    @Test fun acceptedWorldCandidateCrossesToSceneOwnerBeforeChangingCalibration()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(worldFrame()));runCurrent()
        model.onEvent(BridgeUiEvent.PauseMarkerReview);model.onEvent(BridgeUiEvent.UseMarkerGeometry)
        val request=requireNotNull(model.scene.value.worldMonitor)
        assertEquals(before,model.scene.value.monitor)
        val local=before.copy(position=listOf(.1f,1.1f,-.9f),locked=true,editing=false)
        model.onEvent(BridgeUiEvent.WorldMonitorApplied(request,local))
        assertEquals(local,model.scene.value.monitor);assertNull(model.scene.value.worldMonitor)
        assertEquals(MarkerPhase.NONE,model.state.value.markerPhase)
    }
    @Test fun cancelledWorldRequestCannotOverwriteExistingMonitorFromLateCallback()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(worldFrame()));runCurrent()
        model.onEvent(BridgeUiEvent.PauseMarkerReview);model.onEvent(BridgeUiEvent.UseMarkerGeometry)
        val request=requireNotNull(model.scene.value.worldMonitor)
        model.onEvent(BridgeUiEvent.BackFromMarkers)
        model.onEvent(BridgeUiEvent.WorldMonitorApplied(request,before.copy(width=2f)))
        assertEquals(before,model.scene.value.monitor)
    }

    @Test fun backgroundStopDiscardsFrozenWorldCandidate()=runTest(dispatcher) {
        open();val before=model.scene.value.monitor
        updates.emit(ScanUpdate.Markers(worldFrame()));runCurrent()
        model.onEvent(BridgeUiEvent.PauseMarkerReview)
        model.onEvent(BridgeUiEvent.StopScan);model.onEvent(BridgeUiEvent.UseMarkerGeometry)
        assertNull(model.state.value.markerFrame);assertNull(model.scene.value.worldMonitor)
        assertEquals(before,model.scene.value.monitor)
    }

}
