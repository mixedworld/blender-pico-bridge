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
class TrackedCameraViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private lateinit var model: BridgeViewModel
    private val repository=FakeBridgeRepository()
    private val packet=FramePacket("camera-session",1,"a".repeat(64),null,"Cube",1,false,0f,emptyList(),null,0,0,
        cameraSyncVersion=1,cameraControlEnabled=true)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model=BridgeViewModel(repository.pairing,ConnectBridgeUseCase(repository),initialMonitor=true)
    }
    @After fun stop() { model.onEvent(BridgeUiEvent.Disconnect);Dispatchers.resetMain() }
    private suspend fun TestScope.open() {
        model.onEvent(BridgeUiEvent.Connect);runCurrent()
        repository.events.emit(BridgeUpdate.Model(packet,File("${packet.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.RoomChanged(true))
    }
    @Test fun roomSendsLatestAbsoluteCameraThenReleasesOnStop()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        val frame=TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-.3f,.3f,-.2f,.2f),1f,100f)
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(frame))
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(frame.copy(position=listOf(.1f,0f,1f))))
        advanceTimeBy(34);runCurrent()
        assertEquals(1,repository.cameraCommands.size)
        assertEquals(.1f,repository.cameraCommands.single().second!!.position[0],0f)
        assertTrue(repository.viewportCommands.isEmpty())
        model.onEvent(BridgeUiEvent.LinkViewport(false));runCurrent()
        assertEquals(2L,repository.cameraCommands.last().first);assertNull(repository.cameraCommands.last().second)
    }
    @Test fun freshCameraFramesCanBeSentWithinTwelveMilliseconds()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        val frame=TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-.3f,.3f,-.2f,.2f),1f,100f)
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(frame));advanceTimeBy(12);runCurrent()
        assertEquals(1,repository.cameraCommands.size)
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(frame.copy(position=listOf(.1f,0f,1f))))
        advanceTimeBy(12);runCurrent()
        assertEquals(2,repository.cameraCommands.size)
        assertEquals(.1f,repository.cameraCommands.last().second!!.position[0],0f)
    }
    @Test fun fasterCadenceStillAllowsTwoSecondsForMissingHeadData()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        advanceTimeBy(1500);runCurrent()
        assertTrue(model.state.value.linkViewport)
        advanceTimeBy(600);runCurrent()
        assertFalse(model.state.value.linkViewport)
    }
    @Test fun roomDoesNotStartWithAnOldServerOrUnlockedMonitor()=runTest(dispatcher) {
        open()
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=2,cameraSyncVersion=0,cameraControlEnabled=false)));runCurrent()
        model.onEvent(BridgeUiEvent.LinkViewport(true));assertFalse(model.state.value.linkViewport)
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=3)));runCurrent()
        model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.LinkViewport(true));assertFalse(model.state.value.linkViewport)
    }
    @Test fun cameraTransportFailureRestoresHiddenSourceAndStopsRenewing()=runTest(dispatcher) {
        open(); model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        model.onEvent(BridgeUiEvent.SourceHidden(true));runCurrent()
        assertTrue(repository.visibility.last())
        repository.cameraFailure=java.io.IOException("Rejected sample")
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-.3f,.3f,-.2f,.2f),1f,100f)))
        advanceTimeBy(34);runCurrent()
        assertFalse(model.state.value.linkViewport)
        assertEquals(listOf(true,false),repository.visibility)
        advanceTimeBy(3000);runCurrent()
        assertEquals(listOf(true,false),repository.visibility)
        assertNull(repository.cameraCommands.last().second)
    }
    @Test fun missingHeadFramesRestoreHiddenSource()=runTest(dispatcher) {
        open(); model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        model.onEvent(BridgeUiEvent.SourceHidden(true));runCurrent()
        advanceTimeBy(2100);runCurrent()
        assertFalse(model.state.value.linkViewport)
        assertEquals(listOf(true,false),repository.visibility)
    }
    @Test fun roomReplacementPreservesSetupAndRebindsCameraOnlyAfterMatchingModelReady()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        val pose=InspectionPose(listOf(.4f,1.2f,-.8f),listOf(0f,0f,0f,1f),1.6f,.053f,listOf(-.15f,.02f,.08f))
        model.onEvent(BridgeUiEvent.PoseSaved(true,pose))
        model.onEvent(BridgeUiEvent.ExplosionChanged(.4f))
        val monitor=model.state.value.monitor
        val next=packet.copy(revision="b".repeat(64),sequence=2)
        repository.events.emit(BridgeUpdate.Model(next,File("${next.revision}.glb")));runCurrent()
        assertFalse(model.state.value.linkViewport)
        assertEquals(pose,model.scene.value.roomPose)
        assertEquals(monitor,model.state.value.monitor)
        assertEquals(.4f,model.scene.value.explosion,0f)
        model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!,.053f));runCurrent()
        assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.ModelReady(next.revision!!,.053f));runCurrent()
        assertTrue(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-.3f,.3f,-.2f,.2f),1f,100f)))
        advanceTimeBy(34);runCurrent()
        assertEquals(next.revision,repository.cameraRevisions.last())
    }
    @Test fun optOutDuringReplacementDoesNotResumeCamera()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        val next=packet.copy(revision="b".repeat(64),sequence=2)
        repository.events.emit(BridgeUpdate.Model(next,File("${next.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.LinkViewport(false))
        model.onEvent(BridgeUiEvent.ModelReady(next.revision!!,.053f));runCurrent()
        assertFalse(model.state.value.linkViewport)
    }
    @Test fun oldCameraRejectionBeforeModelNotificationDoesNotLoseRoomLinkIntent()=runTest(dispatcher) {
        open();model.onEvent(BridgeUiEvent.LinkViewport(true));runCurrent()
        repository.cameraFailure=java.io.IOException("Old camera revision rejected")
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-.3f,.3f,-.2f,.2f),1f,100f)))
        advanceTimeBy(12);runCurrent()
        assertFalse(model.state.value.linkViewport)
        repository.cameraFailure=null
        val next=packet.copy(revision="b".repeat(64),sequence=2)
        repository.events.emit(BridgeUpdate.Model(next,File("${next.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.ModelReady(next.revision!!,.053f));runCurrent()
        assertTrue(model.state.value.linkViewport)
    }
}
