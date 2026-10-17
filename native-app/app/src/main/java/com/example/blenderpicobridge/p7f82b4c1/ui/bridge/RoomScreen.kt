package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.*
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components.ToolsRecoveryButton
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator

@Composable
fun RoomScreen() {
    val application = LocalContext.current.applicationContext
    val factory = remember(application) { BridgeViewModelFactory(application) }
    val model: BridgeViewModel = viewModel(viewModelStoreOwner = application as ViewModelStoreOwner, factory = factory)
    val navigator = LocalSpatialNavigator.current
    val feed = remember(model) { StateFlowHolder(model.scene) }
    DisposableEffect(Unit) {
        model.onEvent(BridgeUiEvent.RoomChanged(true))
        navigator.openWindowContainer("BlenderTools", tag = "tools")
        navigator.closeWindowContainer("BlenderInspection")
        onDispose {
            model.onEvent(BridgeUiEvent.RoomChanged(false))
            navigator.openWindowContainer("BlenderInspection")
        }
    }
    BridgeScene(feed, Modifier, model::onEvent, room = true, renderVideo=model.renderVideo, tools = {
        ToolsRecoveryButton {
            model.onEvent(BridgeUiEvent.CollapseChanged(false))
            navigator.openWindowContainer("BlenderTools", tag = "tools")
        }
    })
}
