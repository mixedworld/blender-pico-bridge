package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import com.example.blenderpicobridge.p7f82b4c1.FakeBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCleanupTest {
    private val dispatcher=StandardTestDispatcher()
    private lateinit var repo: FakeBridgeRepository
    private lateinit var model: BridgeViewModel
    private val packet=FramePacket("s",4,"a".repeat(64),null,"Cube",1,false,0f,emptyList(),null,0,0)
    @Before fun setup(){Dispatchers.setMain(dispatcher);repo=FakeBridgeRepository();model=BridgeViewModel(repo.pairing,ConnectBridgeUseCase(repo))}
    @After fun finish(){model.onEvent(BridgeUiEvent.Disconnect);Dispatchers.resetMain()}
    private suspend fun TestScope.live(){model.onEvent(BridgeUiEvent.Connect);runCurrent();repo.events.emit(BridgeUpdate.Model(packet,File("${packet.revision}.glb")));runCurrent();model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!,1f))}
    @Test fun offlineShowsConnectionOnlyAndLateLoadCannotReviveIt()=runTest(dispatcher){
        assertTrue(model.state.value.connectionPanel);assertFalse(model.state.value.hasModel)
        live();model.onEvent(BridgeUiEvent.Disconnect)
        model.onEvent(BridgeUiEvent.ModelReady(packet.revision!!,1f))
        assertFalse(model.state.value.hasModel);assertNull(model.scene.value.file);assertNull(model.scene.value.packet)
        assertFalse(model.scene.value.monitor.enabled);assertFalse(model.scene.value.renderWindow.enabled)
    }
    @Test fun briefNetworkPauseRetainsSceneButConfirmedLossClearsIt()=runTest(dispatcher){
        live();val original=model.scene.value.file
        repo.events.emit(BridgeUpdate.Status("stale","Retrying"));runCurrent();advanceTimeBy(2000)
        assertEquals(original,model.scene.value.file)
        repo.events.emit(BridgeUpdate.Frame(packet.copy(sequence=8)));runCurrent();advanceTimeBy(4000);runCurrent()
        assertTrue(model.state.value.hasModel)
        repo.events.emit(BridgeUpdate.Status("stale","Retrying"));runCurrent();advanceTimeBy(3501);runCurrent()
        assertFalse(model.state.value.hasModel);assertTrue(model.state.value.connectionPanel);assertNull(model.scene.value.packet)
    }
    @Test fun sceneRevisionRetainsPlacementAndSelectedTab()=runTest(dispatcher){
        live();model.onEvent(BridgeUiEvent.SelectTab(ToolsTab.RENDER));model.onEvent(BridgeUiEvent.InspectionChanged(2f))
        repo.events.emit(BridgeUpdate.Status("refreshing","Receiving model"));runCurrent();advanceTimeBy(8000)
        assertTrue(model.state.value.hasModel)
        val next=packet.copy(revision="b".repeat(64))
        repo.events.emit(BridgeUpdate.Model(next,File("${next.revision}.glb")));runCurrent()
        model.onEvent(BridgeUiEvent.ModelReady(next.revision!!,1f))
        assertEquals(ToolsTab.RENDER,model.state.value.toolsTab);assertEquals(2f,model.state.value.scale,0f)
    }
    @Test fun rejectedPairingClearsSavedTokenAndNewScanCannotReuseUiResult()=runTest(dispatcher){
        live();repo.events.emit(BridgeUpdate.Status("disconnected","QR expired",true));runCurrent()
        assertEquals("",repo.pairing.token);assertEquals("",model.state.value.token)
        assertTrue(model.state.value.connectionPanel);assertFalse(model.state.value.hasModel)
        model.onEvent(BridgeUiEvent.TokenChanged("old-token"));model.onEvent(BridgeUiEvent.ScanRequested(ScanPurpose.PAIRING))
        assertEquals("",model.state.value.token);assertEquals(ScanPurpose.PAIRING,model.state.value.scanPurpose)
        model.onEvent(BridgeUiEvent.StopScan);assertNull(model.state.value.scanPurpose)
    }
}
