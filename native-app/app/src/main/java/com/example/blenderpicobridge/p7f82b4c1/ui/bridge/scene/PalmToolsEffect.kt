package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.PalmToolsHand
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.PalmToolsUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import kotlinx.coroutines.*

/** Provider, watchdog and all Entity writes share the lifecycle's main-thread scope. */
@Composable
internal fun PalmToolsEffect(enabled: Boolean,attached: Boolean,runtime: BridgeSceneRuntime,event: (BridgeUiEvent)->Unit) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestEvent by rememberUpdatedState(event)
    LaunchedEffect(enabled,attached,lifecycle,runtime) {
        if(!enabled || !attached){runtime.palmTools(null);return@LaunchedEffect}
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val provider=HandTrackingProvider()
            val left= PalmToolsUseCase();val right=PalmToolsUseCase()
            var lastFrame=0L
            var reported: String?=null
            fun report(text: String){if(text!=reported){reported=text;latestEvent(BridgeUiEvent.PalmStatus(text))}}
            try {
                provider.start()
                report("Look toward a steady open palm · Tools appears at the little-finger side")
                coroutineScope {
                    launch {
                        while(isActive){
                            delay(100)
                            if(SystemClock.elapsedRealtime()-lastFrame>300){
                                runtime.palmTools(null);left.reset();right.reset()
                                report("No hand tracking · use the draggable Tools launcher")
                            }
                        }
                    }
                    provider.dataFlow.collect { data ->
                        val now=SystemClock.elapsedRealtime();lastFrame=now
                        if(provider.state!=DataProvider.State.STARTED || provider.supportState!=DataProvider.SupportState.SUPPORTED){
                            runtime.palmTools(null);left.reset();right.reset();return@collect
                        }
                        val head=runtime.toolsHead()
                        val point=left.update(data.left?.toolsHand(false),head?.first,head?.second,now)
                            ?: right.update(data.right?.toolsHand(true),head?.first,head?.second,now)
                        runtime.palmTools(point)
                        report(if(point==null) "Look toward a steady open palm · hold briefly" else "Palm Tools ready")
                    }
                }
            } catch(cancelled: CancellationException){throw cancelled}
            catch(_: Exception){report("Hand tracking unavailable · use the draggable Tools launcher")}
            finally {provider.stop();runtime.palmTools(null);left.reset();right.reset()}
        }
    }
}

private fun HandPose.toolsHand(right: Boolean): PalmToolsHand? {
    fun point(index: HandJoint.Index)=handJoints.firstOrNull{it.index==index}?.position?.let{listOf(it.x,it.y,it.z)}
    return PalmToolsHand(right,point(HandJoint.Index.PALM) ?: return null,point(HandJoint.Index.WRIST) ?: return null,
        point(HandJoint.Index.INDEX_METACARPAL) ?: return null,point(HandJoint.Index.LITTLE_METACARPAL) ?: return null,
        point(HandJoint.Index.INDEX_TIP) ?: return null,point(HandJoint.Index.LITTLE_TIP) ?: return null)
}
