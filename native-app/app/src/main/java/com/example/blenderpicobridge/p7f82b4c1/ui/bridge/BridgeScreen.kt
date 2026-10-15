package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components.*
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.*
import com.pico.spatial.ui.platform.containers.*
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment

@Composable
fun BridgeScreen(modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext
    val factory = remember(application) { BridgeViewModelFactory(application) }
    val model: BridgeViewModel = viewModel(viewModelStoreOwner = application as ViewModelStoreOwner, factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val feed = remember(model) { StateFlowHolder(model.scene) }
    val navigator = LocalSpatialNavigator.current
    LaunchedEffect(model) {
        if (state.endpoint.isNotBlank() && state.token.isNotBlank() && state.phase == "disconnected") model.onEvent(BridgeUiEvent.Connect)
        else if (state.endpoint.isBlank() || state.token.isBlank()) model.onEvent(BridgeUiEvent.ConnectionPanelChanged(true))
    }
    LaunchedEffect(state.autoRoom) {
        if (state.autoRoom) {
            when (val result = navigator.openStage("BlenderRoom", StageStyle.Mixed)) {
                OpenStageResult.Allowed -> Unit
                else -> { model.onEvent(BridgeUiEvent.RoomChanged(false)); model.onEvent(BridgeUiEvent.Feedback("Debug Room launch result: $result")) }
            }
        }
    }
    LaunchedEffect(navigator) { navigator.openWindowContainer("BlenderTools", tag = "tools") }
    Box(modifier.fillMaxSize()) {
        BridgeScene(feed, Modifier.fillMaxSize(), model::onEvent)
        Augment(anchor = NormalizedPoint3D.TopRightFront, alignment = AugmentContentAlignment.TopLeft,
            offset = DpOffset3D(24.dp, 0.dp, 0.dp), cornerRadius = 32.dp, enableMaterialBackground = true) {
            ToolsRecoveryButton {
                model.onEvent(BridgeUiEvent.CollapseChanged(false))
                navigator.openWindowContainer("BlenderTools", tag = "tools")
            }
        }
    }
}
