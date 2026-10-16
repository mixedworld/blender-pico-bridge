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
    @Test fun roomDoesNotStartWithAnOldServerOrUnlockedMonitor()=runTest(dispatcher) {
        open()
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=2,cameraSyncVersion=0,cameraControlEnabled=false)));runCurrent()
        model.onEvent(BridgeUiEvent.LinkViewport(true));assertFalse(model.state.value.linkViewport)
        repository.events.emit(BridgeUpdate.Frame(packet.copy(sequence=3)));runCurrent()
        model.onEvent(BridgeUiEvent.AlignMonitor)
        model.onEvent(BridgeUiEvent.LinkViewport(true));assertFalse(model.state.value.linkViewport)
    }
}
