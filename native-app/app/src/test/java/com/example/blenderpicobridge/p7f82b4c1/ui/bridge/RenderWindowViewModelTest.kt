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
class RenderWindowViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val repository=FakeBridgeRepository()
    private lateinit var model: BridgeViewModel
    private val packet=FramePacket("render-session",1,"a".repeat(64),null,"Cube",1,false,0f,emptyList(),null,0,0,
        cameraSyncVersion=1,cameraControlEnabled=true,renderStreamVersion=1)
    @Before fun setup() { Dispatchers.setMain(dispatcher);model=BridgeViewModel(repository.pairing,ConnectBridgeUseCase(repository)) }
    @After fun stop() { model.onEvent(BridgeUiEvent.Disconnect);Dispatchers.resetMain() }
    private suspend fun TestScope.open() {
        model.onEvent(BridgeUiEvent.Connect);runCurrent()
        repository.events.emit(BridgeUpdate.Model(packet,File("fixture.glb")))
        repository.events.emit(BridgeUpdate.Frame(packet));runCurrent()
        model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!,1f));model.onEvent(BridgeUiEvent.RoomChanged(true))
    }
    @Test fun billboardAndFitAreIndependentOfLockVideoAndMode()=runTest(dispatcher) {
        try {
            open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
            model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
            val before=model.scene.value
            model.onEvent(BridgeUiEvent.RenderFaceUser(true))
            model.onEvent(BridgeUiEvent.RenderFitModel(true))
            model.onEvent(BridgeUiEvent.RenderLocked(true))
            assertEquals(before.copy(renderWindow=before.renderWindow.copy(faceUser=true,fitModel=true,locked=true)),model.scene.value)
            assertTrue(model.scene.value.renderWindow.autoFit)
            assertTrue(model.state.value.linkViewport)
            assertTrue(model.state.value.renderStreaming)
            model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.LIVE_DISPLAY))
            assertFalse(model.scene.value.renderWindow.autoFit)
            assertTrue(model.scene.value.renderWindow.faceUser)
            model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.SCENE_ALIGNED))
            assertTrue(model.scene.value.renderWindow.autoFit)
            model.onEvent(BridgeUiEvent.RenderFaceUser(false))
            assertFalse(model.scene.value.renderWindow.autoFit)
            assertTrue(model.scene.value.renderWindow.locked)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun renderLockOnlyChangesPickingAndSurvivesRetryModeAndVisibilityChanges()=runTest(dispatcher) {
        try {
            open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
            model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
            val before=model.scene.value
            assertFalse(before.renderWindow.locked)
            model.onEvent(BridgeUiEvent.RenderLocked(true))
            assertEquals(before.copy(renderWindow=before.renderWindow.copy(locked=true)),model.scene.value)
            assertTrue(model.state.value.linkViewport)
            assertTrue(model.state.value.renderStreaming)
            model.onEvent(BridgeUiEvent.RetryRender)
            model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.LIVE_DISPLAY))
            model.onEvent(BridgeUiEvent.ModelVisibilityChanged(false))
            model.onEvent(BridgeUiEvent.RenderEnabled(false))
            model.onEvent(BridgeUiEvent.RenderEnabled(true))
            assertTrue(model.state.value.renderWindow.locked)
            assertTrue(model.scene.value.renderWindow.locked)
            model.onEvent(BridgeUiEvent.RenderLocked(false))
            assertFalse(model.scene.value.renderWindow.locked)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun stereoProtocolCapabilityCanOpenTheRenderWindow()=runTest(dispatcher) {
        try {
            open()
            repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=2,renderStreamVersion=3,renderStereoEyeSeparationMm=64f)))
            runCurrent()
            assertTrue(model.state.value.renderAvailable)
            model.onEvent(BridgeUiEvent.RenderEnabled(true))
            assertTrue(model.state.value.renderWindow.enabled)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun sceneAlignedOwnsCameraOnlyAfterVideoIsReceivedAndLiveModeReleasesIt()=runTest(dispatcher) {
        try {
        open();val monitor=model.scene.value.monitor
        model.onEvent(BridgeUiEvent.RenderEnabled(true));assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Connecting",recovering=true)))
        assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
        assertTrue(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RecenterRender);val reset=model.scene.value.renderWindow.reset
        model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.LIVE_DISPLAY))
        assertFalse(model.state.value.linkViewport);assertEquals(reset,model.scene.value.renderWindow.reset)
        assertEquals(monitor,model.scene.value.monitor)
        model.onEvent(BridgeUiEvent.LinkViewport(true));assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.SCENE_ALIGNED));assertTrue(model.state.value.linkViewport)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun bothVideoModesKeepBlenderVisibleWithoutChangingTheSavedPreference()=runTest(dispatcher) {
        try {
        open();assertTrue(model.state.value.hideSource)
        model.onEvent(BridgeUiEvent.RenderEnabled(true));runCurrent()
        model.onEvent(BridgeUiEvent.SourceHidden(true));runCurrent()
        model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.LIVE_DISPLAY))
        model.onEvent(BridgeUiEvent.SourceHidden(true));runCurrent()
        assertFalse(repository.visibility.contains(true));assertTrue(model.state.value.hideSource)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun startingPhysicalAlignmentStopsVideoWithoutMovingTheSavedFrame()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true));val before=model.state.value.monitor
        model.onEvent(BridgeUiEvent.AlignMonitor)
        assertFalse(model.state.value.renderWindow.enabled);assertFalse(model.state.value.linkViewport)
        assertEquals(before.position,model.state.value.monitor.position)
        assertEquals(before.curveDegrees,model.state.value.monitor.curveDegrees,0f)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun streamLossReleasesTheCameraAndRetryPreservesModeAndPlacementCounters()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)));assertTrue(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Disconnected")));assertFalse(model.state.value.linkViewport)
        val before=model.scene.value.renderWindow
        model.onEvent(BridgeUiEvent.RetryRender)
        assertEquals(before.mode,model.scene.value.renderWindow.mode)
        assertEquals(before.reset,model.scene.value.renderWindow.reset)
        assertEquals(before.retry+1,model.scene.value.renderWindow.retry)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun temporaryVideoRecoveryKeepsTheAlignedCameraAndLiveModeStillReleases()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
        val before=model.scene.value.renderWindow
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Reconnecting",recovering=true)))
        assertTrue(model.state.value.linkViewport)
        assertFalse(model.state.value.renderStreaming)
        assertEquals(before,model.scene.value.renderWindow)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
        assertTrue(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Reconnecting",recovering=true)))
        model.onEvent(BridgeUiEvent.RenderMode(RenderWindowMode.LIVE_DISPLAY))
        assertFalse(model.state.value.linkViewport)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun recoveryExpiryReleasesCameraAndVideoReturnReacquiresAutomatically()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Reconnecting",recovering=true)))
        assertTrue(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Unavailable",recovering=false)))
        assertFalse(model.state.value.linkViewport)
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)))
        assertTrue(model.state.value.linkViewport)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }
    @Test fun temporaryCameraNetworkFailureDuringVideoDoesNotTurnOffAlignment()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)));runCurrent()
        repository.cameraFailure=java.io.IOException("Export busy")
        val camera=TrackedViewportCamera(listOf(0f,0f,1f),listOf(0f,0f,0f,1f),listOf(-1f,1f,-1f,1f),.01f,100f)
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(camera));advanceTimeBy(12);runCurrent()
        assertTrue(model.state.value.linkViewport)
        repository.cameraFailure=null
        model.onEvent(BridgeUiEvent.TrackedCameraFrame(camera));advanceTimeBy(300);runCurrent()
        assertTrue(model.state.value.linkViewport)
        assertTrue(repository.cameraCommands.any { it.second!=null })
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }

    @Test fun turningVideoOffReleasesCameraEvenDuringNewRevisionLoading()=runTest(dispatcher) {
        try {
        open();model.onEvent(BridgeUiEvent.RenderEnabled(true))
        model.onEvent(BridgeUiEvent.RenderStatus(RenderVideoStatus("Live",true)));runCurrent()
        val next=packet.copy(revision="b".repeat(64))
        repository.events.emit(BridgeUpdate.Model(next,File("new.glb")));runCurrent()
        repository.cameraCommands.clear();repository.cameraRevisions.clear()
        model.onEvent(BridgeUiEvent.RenderEnabled(false));runCurrent()
        assertFalse(model.state.value.linkViewport)
        assertEquals(next.revision,repository.cameraRevisions.last())
        assertNull(repository.cameraCommands.last().second)
        model.onEvent(BridgeUiEvent.ModelReady(next.revision!!,1f));runCurrent()
        assertFalse(model.state.value.linkViewport)
        } finally { model.onEvent(BridgeUiEvent.Disconnect);runCurrent() }
    }

}
