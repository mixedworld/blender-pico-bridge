package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import com.example.blenderpicobridge.p7f82b4c1.domain.model.SceneFeed
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTransformGesture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.blenderpicobridge.p7f82b4c1.domain.model.TouchAlignmentRequest

@Composable
fun BridgeScene(feed: StateFlowHolder, modifier: Modifier, event: (BridgeUiEvent) -> Unit, room: Boolean = false,
    renderVideo: com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ReceiveRenderVideoUseCase? = null, tools: (@Composable () -> Unit)? = null,
    palmTools: (@Composable () -> Unit)? = null,
    alignment: com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ManageAlignmentUseCase? = null) {
    val runtime = remember { BridgeSceneRuntime(event, room, renderVideo) }
    var attached by remember { mutableStateOf(false) }
    val hmd = remember(room) { if (room) HMDTrackingProvider() else null }
    DisposableEffect(hmd) { hmd?.start(); onDispose { hmd?.stop() } }
    LaunchedEffect(hmd) {
        hmd?.dataFlow?.collect { value ->
            if (hmd.state == DataProvider.State.STARTED && attached) {
                runtime.placeForViewer(value.hmdPose.position,value.hmdPose.rotation)
                runtime.trackView(value.hmdPose.position)
            }
        }
    }
    val touch by remember(feed) { feed.flow.map { it.touchAlignment }.distinctUntilChanged() }.collectAsState(initial=null)
    TouchTrackingEffect(touch,room,attached,runtime,feed,event)
    val palmEnabled by remember(feed) {feed.flow.map{it.palmTools && it.touchAlignment==null}.distinctUntilChanged()}.collectAsState(initial=false)
    PalmToolsEffect(palmEnabled && room,attached,runtime,event)
    if(alignment!=null)PersistentAlignmentEffect(room,attached,runtime,feed,event,alignment)
    val editing by remember(feed) { feed.flow.map { it.monitor.editing }.distinctUntilChanged() }.collectAsState(initial=false)
    val context = LocalContext.current
    DisposableEffect(runtime) { onDispose { runtime.close() } }
    val renderLifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(runtime,renderLifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _, action ->
            if(action==Lifecycle.Event.ON_STOP)runtime.setRenderActive(false)
            if(action==Lifecycle.Event.ON_RESUME)runtime.setRenderActive(true)
        }
        renderLifecycle.addObserver(observer)
        onDispose { renderLifecycle.removeObserver(observer) }
    }
    // The same recognizer targets individual alignment guides or the inspection model.
    SpatialView(modifier = modifier.pointerInput(runtime,editing) {
        // Observe before the SDK recognizer consumes the Main-pass event. In 6.1.9
        // its first two-pointer centroid can arrive as a large translation delta.
        awaitPointerEventScope {
            while (true) {
                val input = awaitPointerEvent(PointerEventPass.Initial)
                runtime.gesturePointers(input.changes.filter { it.pressed }.map { it.id.value }.toSet())
            }
        }
    }.pointerInput(runtime,editing) {
        detectSpatialTransformGesture(context, targetedToEntity = TargetEntity.any { feed.flow.value.debugReveal == null && feed.flow.value.touchAlignment == null && runtime.accepts(it) }, onTransformBegin = runtime::beginGesture, onTransform = runtime::manipulate)
    }, initial = { content, attachments ->
        runtime.attach(content)
        attachments.entity("room-tools")?.let {
            it.components[TransformComponent::class.java]!!.position = Vector3(.7f, 1.05f, -1f)
            runtime.attachTools(it)
        }
        attachments.entity("palm-tools")?.let(runtime::attachPalm)
        attached = true
    }, update = { _, _ -> runtime.measure();runtime.measureTools() }, attachments = {
        if (tools != null) AttachmentPanel("room-tools") { tools() }
        if(palmTools!=null)AttachmentPanel("palm-tools"){palmTools()}
    })
    LaunchedEffect(attached) {
        if (attached) feed.flow.collect { value ->
            try {
                runtime.measure()
                runtime.setInspection(value.mode, value.explosion)
                if (value.file != null && value.packet != null) {
                    runtime.replace(value.file, value.packet)
                    runtime.apply(value.packet)
                }
                runtime.resetIfChanged(feed.flow.value.reset)
                runtime.preferences(feed.flow.value)
                value.worldMonitor?.let { event(BridgeUiEvent.WorldMonitorApplied(it,runtime.monitorFromWorld(it))) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { android.util.Log.e("BlenderBridge", "SCENE_ERROR", error); event(BridgeUiEvent.SceneError(error.message ?: "Model could not be loaded")) }
        }
    }
}

class StateFlowHolder(val flow: kotlinx.coroutines.flow.StateFlow<SceneFeed>)
