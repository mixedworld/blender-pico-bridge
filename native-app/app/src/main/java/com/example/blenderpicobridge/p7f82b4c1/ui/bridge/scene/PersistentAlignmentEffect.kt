package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.blenderpicobridge.p7f82b4c1.data.repository.PersistentAlignmentRepository
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Full Space only. Permission, subscription and coroutine belong to the Room composition. */
@Composable
internal fun PersistentAlignmentEffect(room: Boolean,attached: Boolean,runtime: BridgeSceneRuntime,
    feed: StateFlowHolder,event: (BridgeUiEvent)->Unit) {
    if(!room) return
    val context=LocalContext.current
    val repository=remember(context.applicationContext) { PersistentAlignmentRepository(context.applicationContext) }
    val permission="com.picovr.permission.SPATIAL_DATA"
    var granted by remember { mutableStateOf(context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED) }
    val save by remember(feed) { feed.flow.map { it.alignmentSave }.distinctUntilChanged() }.collectAsState(initial=null)
    val registry=LocalActivityResultRegistryOwner.current
    if(registry!=null) {
        val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted=allowed
            if(!allowed) event(BridgeUiEvent.Feedback("Alignment is usable this session. Allow Spatial data in app settings to save or restore it."))
        }
        LaunchedEffect(attached,save?.id) {
            if(attached && !granted && (save!=null || repository.hasSaved)) launcher.launch(permission)
        }
    } else {
        LaunchedEffect(attached,save?.id) {
            if(attached && !granted && (save!=null || repository.hasSaved))
                event(BridgeUiEvent.Feedback("Allow Spatial data in app settings to save or restore the viewport alignment."))
        }
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,action ->
            if(action==Lifecycle.Event.ON_RESUME) granted=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(attached,granted) {
        if(!attached || !granted) return@LaunchedEffect
        feed.flow.map { it.monitor.editing to it.alignmentSave }.distinctUntilChanged().collectLatest { (editing,request) ->
            if(editing) { runtime.detachAlignmentAnchor();return@collectLatest }
            try {
                val created=request?.let {
                    val snapshot=runtime.alignmentSnapshot(it)
                    runtime.detachAlignmentAnchor()
                    repository.save(snapshot)
                }
                val saved=created?.saved ?: repository.read() ?: return@collectLatest
                val restoring=created==null
                if(restoring) event(BridgeUiEvent.AlignmentLocating)
                var first=true
                repository.track(saved,created?.anchor).collect { update ->
                    when(update) {
                        is PersistentAlignmentRepository.Update.Status -> event(BridgeUiEvent.Feedback(update.message))
                        PersistentAlignmentRepository.Update.Removed -> {
                            runtime.detachAlignmentAnchor();event(BridgeUiEvent.AlignmentLocating)
                            event(BridgeUiEvent.Feedback("Saved room anchor was removed. Choose Align viewport and lock a new alignment."))
                        }
                        is PersistentAlignmentRepository.Update.Located -> {
                            runtime.anchoredAlignment(saved,PersistentAlignmentRepository.pose(update.anchor),restoring && first)
                            if(first) event(BridgeUiEvent.Feedback(if(restoring) "Viewport frame and curvature restored from the room anchor."
                                else "Viewport frame and curvature saved. Look around nearby to help PICO recognize it next time."))
                            first=false
                        }
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                android.util.Log.w("BlenderBridge","ALIGNMENT_PERSISTENCE_FAILED",error)
                event(BridgeUiEvent.Feedback("Room alignment could not be saved/restored. Current manual alignment remains usable; choose Align viewport to retry."))
            }
        }
    }
}
