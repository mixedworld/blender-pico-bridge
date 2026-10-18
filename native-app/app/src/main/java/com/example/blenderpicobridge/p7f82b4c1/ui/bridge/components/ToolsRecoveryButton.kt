package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Text

/** Stays with the model surface even when the independent tools window is closed. */
@Composable
internal fun ToolsRecoveryButton(openTools: () -> Unit) {
    Box(Modifier.requiredSize(184.dp, 72.dp).padding(8.dp)) {
        Button(onClick = openTools, modifier = Modifier.fillMaxSize(), size = ButtonDefaults.Max) {
            Text("Tools")
        }
    }
}
