package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import com.example.blenderpicobridge.p7f82b4c1.FakeBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorHandleViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private lateinit var model: BridgeViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model=BridgeViewModel(FakeBridgeRepository().pairing,ConnectBridgeUseCase(FakeBridgeRepository()))
        model.onEvent(BridgeUiEvent.RoomChanged(true));model.onEvent(BridgeUiEvent.AlignMonitor)
    }
    @After fun stop() { model.onEvent(BridgeUiEvent.Disconnect);Dispatchers.resetMain() }
    @Test fun invalidCornerDraftIsRetainedWithoutReplacingTheValidCalibration() {
        val before=model.state.value.monitor;val points=model.scene.value.monitorHandles!!.corners
        model.onEvent(BridgeUiEvent.MonitorHandleMoved(0,listOf(.2f,.3f,0f)))
        assertNotNull(model.state.value.handleError);assertEquals(before,model.state.value.monitor)
        assertEquals(points[1],model.scene.value.monitorHandles!!.corners[1])
        model.onEvent(BridgeUiEvent.LockMonitor);assertFalse(model.state.value.monitor.locked)
        model.onEvent(BridgeUiEvent.MonitorHandleMoved(0,listOf(-.2f,-.3f,0f)))
        assertNull(model.state.value.handleError)
        model.onEvent(BridgeUiEvent.LockMonitor)
        assertTrue(model.state.value.monitor.locked);assertNull(model.scene.value.monitorHandles)
    }
    @Test fun enteringOtherAlignmentFlowsHidesManualHandlesAndBackRestoresThem() {
        model.onEvent(BridgeUiEvent.OpenMarkerAlignment)
        assertNull(model.scene.value.monitorHandles)
        model.onEvent(BridgeUiEvent.BackFromMarkers)
        assertNotNull(model.scene.value.monitorHandles)
        model.onEvent(BridgeUiEvent.LeaveMonitor)
        assertNull(model.scene.value.monitorHandles);assertFalse(model.state.value.monitor.editing)
    }
    @Test fun stagedLockedAlignmentCanBeReopenedAndEditedRepeatedly() {
        repeat(3) {
            model.onEvent(BridgeUiEvent.StageTake)
            val consumedStage=model.scene.value.commandSequence
            model.onEvent(BridgeUiEvent.LockMonitor)
            assertTrue(model.state.value.monitor.locked)
            assertNull(model.scene.value.monitorHandles)
            model.onEvent(BridgeUiEvent.AlignMonitor)
            assertFalse(model.state.value.monitor.locked)
            assertTrue(model.state.value.monitor.editing)
            assertEquals(consumedStage,model.scene.value.commandSequence)
            val before=model.scene.value.monitorHandles!!.corners
            model.onEvent(BridgeUiEvent.MonitorHandleMoved(1,listOf(.003f,0f,0f)))
            val after=model.scene.value.monitorHandles!!.corners
            assertEquals(before[1][0]+.003f,after[1][0],1e-6f)
            assertEquals(before[0],after[0]);assertEquals(before[2],after[2]);assertEquals(before[3],after[3])
            assertNull(model.state.value.handleError)
        }
    }
    @Test fun lockingSavesExactCornerResidualAndCurvatureAndReopeningRetainsThem() {
        model.onEvent(BridgeUiEvent.MonitorHandleMoved(0,listOf(0f,.002f,0f)))
        model.onEvent(BridgeUiEvent.MonitorHandleMoved(4,listOf(0f,0f,-.03f)))
        val draft=model.scene.value.monitorHandles!!
        model.onEvent(BridgeUiEvent.LockMonitor)
        val save=model.scene.value.alignmentSave!!
        assertEquals(draft,save.handles);assertTrue(save.monitor.curveDegrees>0)
        model.onEvent(BridgeUiEvent.AlignMonitor)
        assertNull(model.scene.value.alignmentSave)
        assertEquals(draft,model.scene.value.monitorHandles)
    }
    @Test fun restoredAlignmentRetainsExactHandlesButCannotOverwriteAnActiveEdit() {
        val draft=model.scene.value.monitorHandles!!
        val restored=model.state.value.monitor.copy(position=listOf(1f,2f,3f),editing=false,locked=true)
        model.onEvent(BridgeUiEvent.AlignmentRestored(restored,draft))
        assertNotEquals(restored,model.state.value.monitor)
        model.onEvent(BridgeUiEvent.LockMonitor)
        model.onEvent(BridgeUiEvent.AlignmentRestored(restored,draft))
        assertEquals(restored,model.state.value.monitor)
        model.onEvent(BridgeUiEvent.AlignMonitor)
        assertEquals(draft,model.scene.value.monitorHandles)
    }
    @Test fun roomExitDoesNotReplayAConsumedAnchorSave() {
        model.onEvent(BridgeUiEvent.LockMonitor)
        assertNotNull(model.scene.value.alignmentSave)
        model.onEvent(BridgeUiEvent.RoomChanged(false))
        assertNull(model.scene.value.alignmentSave)
    }

}
