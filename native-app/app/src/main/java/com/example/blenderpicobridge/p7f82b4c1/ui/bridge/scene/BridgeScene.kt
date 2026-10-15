package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
fun BridgeScene(feed: StateFlowHolder, modifier: Modifier, event: (BridgeUiEvent) -> Unit, room: Boolean = false, tools: (@Composable () -> Unit)? = null) {
    val runtime = remember { BridgeSceneRuntime(event, room) }
    var attached by remember { mutableStateOf(false) }
    val hmd = remember(room) { if (room) HMDTrackingProvider() else null }
    DisposableEffect(hmd) { hmd?.start(); onDispose { hmd?.stop() } }
    LaunchedEffect(hmd) {
        hmd?.dataFlow?.collect { value ->
            if (hmd.state == DataProvider.State.STARTED && attached) runtime.placeForViewer(value.hmdPose.position, value.hmdPose.rotation)
        }
    }
    val touch by remember(feed) { feed.flow.map { it.touchAlignment }.distinctUntilChanged() }.collectAsState(initial=null)
    TouchTrackingEffect(touch,room,attached,runtime,feed,event)
    val context = LocalContext.current
    DisposableEffect(runtime) { onDispose { runtime.close() } }
    SpatialView(modifier = modifier.pointerInput(runtime) {
        detectSpatialTransformGesture(context, targetedToEntity = TargetEntity.any { feed.flow.value.debugReveal == null && feed.flow.value.touchAlignment == null && runtime.accepts(it) }, onTransform = runtime::manipulate)
    }, initial = { content, attachments ->
        runtime.attach(content)
        attachments.entity("room-tools")?.let {
            it.components[TransformComponent::class.java]!!.position = Vector3(.7f, 1.05f, -1f)
            runtime.tools = it; content.addEntity(it)
        }
        attached = true
    }, update = { _, _ -> runtime.measure() }, attachments = {
        if (tools != null) AttachmentPanel("room-tools") { tools() }
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
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { android.util.Log.e("BlenderBridge", "SCENE_ERROR", error); event(BridgeUiEvent.SceneError(error.message ?: "Model could not be loaded")) }
        }
    }
}

class StateFlowHolder(val flow: kotlinx.coroutines.flow.StateFlow<SceneFeed>)
