package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.*
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components.BridgeControls
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun RoomScreen() {
    val application = LocalContext.current.applicationContext
    val factory = remember(application) { BridgeViewModelFactory(application) }
    val model: BridgeViewModel = viewModel(viewModelStoreOwner = application as ViewModelStoreOwner, factory = factory)
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val feed = remember(model) { StateFlowHolder(model.scene) }
    DisposableEffect(Unit) {
        model.onEvent(BridgeUiEvent.RoomChanged(true))
        navigator.closeWindowContainer("BlenderInspection")
        onDispose {
            model.onEvent(BridgeUiEvent.RoomChanged(false))
            navigator.openWindowContainer("BlenderInspection")
        }
    }
    BridgeScene(feed, Modifier, model::onEvent, room = true, tools = {
        PicoTheme {
            val state by model.state.collectAsStateWithLifecycle()
            BridgeControls(state, model::onEvent) { room -> if (!room) scope.launch { navigator.closeStage() } }
        }
    })
}
