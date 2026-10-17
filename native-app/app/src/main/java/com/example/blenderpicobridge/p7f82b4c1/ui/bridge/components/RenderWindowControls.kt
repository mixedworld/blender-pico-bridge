package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blenderpicobridge.p7f82b4c1.domain.model.RenderWindowMode
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*
import com.pico.spatial.ui.design.*

/** In-place tools group; the video itself is an independent scene Entity. */
@Composable
internal fun RenderWindowControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    Body("Render window")
    ListItem(headlineContent={ Text("Show render window") },trailingContent={
        Switch(checked=state.renderWindow.enabled,onCheckedChange={ event(BridgeUiEvent.RenderEnabled(it)) },
            enabled=state.hasModel && state.renderAvailable)
    })
    if(!state.renderAvailable) Label("Update Blender’s bridge add-on to enable viewport sharing.")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        for((mode,label) in listOf(RenderWindowMode.SCENE_ALIGNED to "Scene-aligned",RenderWindowMode.LIVE_DISPLAY to "Live display")) {
            ToggleButton(checked=state.renderWindow.mode==mode,onCheckedChange={ if(it)event(BridgeUiEvent.RenderMode(mode)) },
                modifier=Modifier.weight(1f).height(56.dp),size=ToggleButtonDefaults.Max,enabled=state.renderWindow.enabled) { Text(label) }
        }
    }
    Label(state.renderStatus)
    Label(if(state.renderWindow.mode==RenderWindowMode.SCENE_ALIGNED)
        "Blender’s camera follows this window and your viewpoint. Assemble the model for an aligned comparison."
        else "Shows Blender’s current view. Navigate freely in Blender.")
    Label("Grab to move · two hands to resize")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ event(BridgeUiEvent.RecenterRender) },modifier=Modifier.weight(1f).height(56.dp),
            size=ButtonDefaults.Max,enabled=state.renderWindow.enabled) { Text("Recenter window") }
        Button(onClick={ event(BridgeUiEvent.RetryRender) },modifier=Modifier.weight(1f).height(56.dp),
            size=ButtonDefaults.Max,enabled=state.renderWindow.enabled) { Text("Retry stream") }
    }
}
