package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.StableTouchCaptureUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ContactReadiness
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Only discrete status/contact results reach UI. Joint and Entity work stays on the main collector. */
@Composable
internal fun TouchTrackingEffect(request: TouchAlignmentRequest?,room: Boolean,attached: Boolean,
    runtime: BridgeSceneRuntime,feed: StateFlowHolder,event: (BridgeUiEvent)->Unit) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestEvent by rememberUpdatedState(event)
    DisposableEffect(lifecycle,request?.id) {
        val observer=LifecycleEventObserver { _,action ->
            if (action==Lifecycle.Event.ON_STOP && request!=null) latestEvent(BridgeUiEvent.CancelTouchAlignment(request.id))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (request!=null) latestEvent(BridgeUiEvent.CancelTouchAlignment(request.id)) }
    }
    LaunchedEffect(request?.id,room,attached,lifecycle) {
        if (request==null || !room || !attached) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val provider=HandTrackingProvider()
            val capture=StableTouchCaptureUseCase()
            val contacts=mutableListOf<List<Float>>()
            var previous: Pair<Int,String>?=null
            fun report(status: String) {
                val next=contacts.size to status
                if (next!=previous) { previous=next;latestEvent(BridgeUiEvent.TouchProgress(request.id,contacts.size,status)) }
            }
            try {
                provider.start()
                report("Keep both hands in view. Rest your chosen index on the marker.")
                withTimeout(120000) {
                    provider.dataFlow.collect { data ->
                        if (contacts.size>=4) return@collect
                        if (feed.flow.value.packet?.board != request.board) {
                            latestEvent(BridgeUiEvent.TouchInvalid(request.id,"Marker layout changed. Restart without resizing Blender."));return@collect
                        }
                        val available=provider.state==DataProvider.State.STARTED && provider.supportState==DataProvider.SupportState.SUPPORTED
                        val hand=if (request.hand==TouchingHand.RIGHT) data.right else data.left
                        val opposite=if (request.hand==TouchingHand.RIGHT) data.leftPinch else data.rightPinch
                        val point=if (available) hand?.handJoints?.firstOrNull { it.index==HandJoint.Index.INDEX_TIP }?.position?.let { listOf(it.x,it.y,it.z) } else null
                        val result=capture.update(point,opposite?.pinchValue,available && opposite?.isReady==true,SystemClock.elapsedRealtime())
                        when (result.readiness) {
                            ContactReadiness.TRACKING_NEEDED -> report("Keep both hands in view. Rest your chosen index on the marker.")
                            ContactReadiness.HOLD_STILL -> report("Hold your touching fingertip still on the marker center.")
                            ContactReadiness.RELEASE_PINCH -> report("Release the confirming hand's pinch before recording.")
                            ContactReadiness.READY -> report("Ready. Pinch with your other hand to record this center.")
                            ContactReadiness.CAPTURED -> {
                                contacts+=requireNotNull(result.point)
                                android.util.Log.i("BridgeTouch","contact accepted count=${contacts.size}")
                                if (contacts.size==4) {
                                    try { latestEvent(BridgeUiEvent.TouchMeasured(request.id,runtime.monitorFromTouches(request,contacts))) }
                                    catch (_: IllegalArgumentException) { latestEvent(BridgeUiEvent.TouchInvalid(request.id,"The four contacts do not form a consistent monitor plane.")) }
                                } else report("Marker recorded. Move to the next center, then release the confirming pinch.")
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) { latestEvent(BridgeUiEvent.TouchInvalid(request.id,"Touch capture timed out. Restart when both hands are visible.")) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { latestEvent(BridgeUiEvent.TouchInvalid(request.id,"Hand tracking unavailable. Retry in Room mode.")) }
            finally { provider.stop();capture.reset();contacts.clear();android.util.Log.i("BridgeTouch","tracking stopped") }
        }
    }
}
