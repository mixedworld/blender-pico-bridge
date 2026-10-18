package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components.BridgeControls
import com.pico.spatial.ui.platform.containers.WindowContainerScope
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.resize.SpatialContainerUtils
import kotlinx.coroutines.launch

/** Independent OS window: the native caption owns pinch movement, never menu controls. */
@Composable
fun WindowContainerScope.ToolsScreen() {
    val context = LocalContext.current
    val application = context.applicationContext
    val factory = remember(application) { BridgeViewModelFactory(application) }
    val model: BridgeViewModel = viewModel(viewModelStoreOwner = application as ViewModelStoreOwner, factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val width = if (state.collapsed) 184.dp else 440.dp
    val height = if (state.collapsed) 72.dp else if (state.connectionPanel) 656.dp
        else if (state.room && state.monitor.editing) 592.dp else if (state.room) 720.dp else 608.dp
    LaunchedEffect(width,height,density) {
        val accepted = SpatialContainerUtils.resizeCurrentWindowContainer(context,
            with(density) { width.roundToPx() },with(density) { height.roundToPx() })
        Log.i("BlenderTools", "resize width=$width height=$height accepted=$accepted")
    }
    Box(Modifier.windowConstraints(width=width,height=height)) {
        BridgeControls(state,model::onEvent) { room -> scope.launch {
            if (room) {
                model.onEvent(BridgeUiEvent.RoomPending(true))
                when (val result=navigator.openStage("BlenderRoom",StageStyle.Mixed)) {
                    OpenStageResult.Allowed -> Unit
                    else -> { model.onEvent(BridgeUiEvent.RoomPending(false));model.onEvent(BridgeUiEvent.Feedback("Room could not open: $result")) }
                }
            } else navigator.closeStage()
        } }
    }
}
