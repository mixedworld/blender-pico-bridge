package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

/** Stays with the model surface even when the independent tools window is closed. */
@Composable
internal fun ToolsRecoveryButton(draggable: Boolean=false,gripModifier: Modifier=Modifier,openTools: () -> Unit) {
    Column(Modifier.requiredSize(184.dp, if(draggable)144.dp else 72.dp).padding(8.dp)) {
        Button(onClick = openTools, modifier = Modifier.fillMaxWidth().height(if(draggable)64.dp else 56.dp), size = ButtonDefaults.Max) {
            Text("Tools")
        }
        if(draggable)ListItem(modifier=Modifier.fillMaxWidth().height(64.dp).spatialHoverEffect().then(gripModifier),
            headlineContent={Text("Pinch here to move",fontSize=12.sp)})
    }
}
