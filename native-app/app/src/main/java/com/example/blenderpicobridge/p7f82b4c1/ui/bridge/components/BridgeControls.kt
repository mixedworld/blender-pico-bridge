package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionMode
import com.example.blenderpicobridge.p7f82b4c1.domain.model.ScanPurpose
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*
import com.pico.spatial.ui.design.*
import com.example.blenderpicobridge.p7f82b4c1.ui.theme.CreatorColors

@Composable
internal fun BridgeControls(state: BridgeUiState, event: (BridgeUiEvent) -> Unit, changeSpace: (Boolean) -> Unit) {
    CameraPermissionEffect(state,event)
    if (state.collapsed) {
        Box(Modifier.requiredSize(184.dp, 72.dp).padding(8.dp)) {
            Action("Show tools", { event(BridgeUiEvent.CollapseChanged(false)) }, Modifier.fillMaxWidth())
        }
        return
    }
    val setup = state.room && state.monitor.editing
    val height = if (state.connectionPanel) 656.dp else if (setup) 592.dp else if (state.room) 720.dp else 608.dp
    Column(Modifier.requiredSize(440.dp, height).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.connectionPanel -> ConnectionControls(state, event)
            setup && state.touchPhase != com.example.blenderpicobridge.p7f82b4c1.domain.model.TouchAlignmentPhase.NONE -> TouchAlignmentControls(state,event)
            setup -> MonitorControls(state, event)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Preview", !state.room, { changeSpace(false) }, Modifier.weight(1f), !state.enteringRoom)
                    Choice("Room", state.room, { changeSpace(true) }, Modifier.weight(1f), !state.enteringRoom)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Scale", state.mode == InspectionMode.SCALE, { event(BridgeUiEvent.ModeChanged(InspectionMode.SCALE)) }, Modifier.weight(1f))
                    Choice("Explode", state.mode == InspectionMode.EXPLODE, { event(BridgeUiEvent.ModeChanged(InspectionMode.EXPLODE)) }, Modifier.weight(1f))
                }
                Label(if (state.room && state.fitScale == 1f) "Source scale · ${"%.2f".format(state.scale)}×" else "Auto-fit ${"%.3f".format(state.fitScale)}× · grab ${"%.2f".format(state.scale)}×")
                ListItem(headlineContent = { Text("Link Blender viewport") }, supportingContent = {
                    Text(if (state.linkViewport) "Gestures control Blender's view; objects stay unchanged" else "Enable viewport control in Blender to link")
                }, trailingContent = { Switch(checked = state.linkViewport,onCheckedChange = { event(BridgeUiEvent.LinkViewport(it)) }, enabled = state.hasModel && state.viewportAllowed) })
                Slider(value = state.explosion, onValueChange = { event(BridgeUiEvent.ExplosionChanged(it)) }, modifier = Modifier.fillMaxWidth(), enabled = state.partCount > 0)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (state.room) "100% source" else "Fit preview", { event(if (state.room) BridgeUiEvent.SourceSize else BridgeUiEvent.Fit) }, Modifier.weight(1f), state.hasModel)
                    Action("Assemble", { event(BridgeUiEvent.ExplosionChanged(0f)) }, Modifier.weight(1f), state.hasModel)
                }
                ListItem(headlineContent = { Text("Highlight on hover") }, trailingContent = {
                    Switch(checked = state.hover, onCheckedChange = { event(BridgeUiEvent.HoverChanged(it)) })
                })
                if (state.room) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Align monitor", { event(BridgeUiEvent.AlignMonitor) }, Modifier.weight(1f), state.hasModel)
                    Action("Reset take", { event(BridgeUiEvent.ResetTake) }, Modifier.weight(1f), state.hasModel)
                }
                if (state.room) ListItem(modifier = Modifier.clickable { event(BridgeUiEvent.SourceHidingChanged(!state.hideSource)) }, headlineContent = { Text("Hide Blender source") },
                    supportingContent = { Text(if (state.sourceHidden) "Source hidden · Reset take restores" else if (state.hideSource) "After full extraction · restore on Reset take" else "Source remains visible") }, trailingContent = {
                        Switch(checked = state.hideSource, onCheckedChange = { event(BridgeUiEvent.SourceHidingChanged(it)) })
                    })
                Label(state.note)
                Label("Frame ${state.frame} · ${if (state.playing) "Playing" else "Paused"} · ${(state.explosion * 100).toInt()}% / ${state.partCount} parts")
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Connection", { event(BridgeUiEvent.ConnectionPanelChanged(true)) }, Modifier.weight(1f))
                    Action("Hide tools", { event(BridgeUiEvent.CollapseChanged(true)) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MonitorControls(state: BridgeUiState, event: (BridgeUiEvent) -> Unit) {
    Body("Align monitor")
    Action("Touch marker centers", { event(BridgeUiEvent.OpenTouchAlignment) }, Modifier.fillMaxWidth(),state.scanPurpose == null)
    if (state.touchApplied) TouchBody("Touch alignment applied. Check the monitor plane, then choose Lock alignment in the alignment controls.")
    Action("Measure with markers + depth", { event(BridgeUiEvent.ScanRequested(ScanPurpose.DEPTH)) }, Modifier.fillMaxWidth(), state.scanPurpose == null)
    if (state.scanStatus.isNotBlank()) Label(state.scanStatus)
    if (state.scanPurpose != null) Action("Stop scanning", { event(BridgeUiEvent.StopScan) }, Modifier.fillMaxWidth())
    state.measuredScreen?.let { size ->
        Label("Measured ${"%.3f".format(size.width)} × ${"%.3f".format(size.height)} m")
        Action("Use measured size", { event(BridgeUiEvent.UseMeasuredSize) }, Modifier.fillMaxWidth())
    }
    Label("Grab and rotate the plane to match your screen. Adjust width and height separately.")
    Label("Width · ${"%.2f".format(state.monitor.width)} m")
    Slider(state.monitor.width, { event(BridgeUiEvent.MonitorWidth(it)) }, valueRange = .1f..2f, modifier = Modifier.fillMaxWidth())
    Label("Height · ${"%.2f".format(state.monitor.height)} m")
    Slider(state.monitor.height, { event(BridgeUiEvent.MonitorHeight(it)) }, valueRange = .1f..2f, modifier = Modifier.fillMaxWidth())
    Action("Stage for take", { event(BridgeUiEvent.StageTake) }, Modifier.fillMaxWidth(), state.hasModel)
    Label(state.note)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("Lock alignment", { event(BridgeUiEvent.LockMonitor) }, Modifier.weight(1f))
        Action("Back", { event(BridgeUiEvent.LeaveMonitor) }, Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.ConnectionControls(state: BridgeUiState, event: (BridgeUiEvent) -> Unit) {
    Body("Blender connection"); Label(state.status); Label(state.note)
    Label(if (state.scanStatus.isBlank()) "Open PICO Bridge in Blender and look at its pairing QR." else state.scanStatus)
    if (state.scanPurpose != null) Action("Stop scanning", { event(BridgeUiEvent.StopScan) }, Modifier.fillMaxWidth())
    else Action("Scan Blender QR", { event(BridgeUiEvent.ScanRequested(ScanPurpose.PAIRING)) }, Modifier.fillMaxWidth())
    if (!state.manualConnection) Action("Use manual connection", { event(BridgeUiEvent.ManualConnection) }, Modifier.fillMaxWidth())
    if (state.manualConnection) {
    Label("Bridge URL")
    TextField(state.endpoint, { event(BridgeUiEvent.EndpointChanged(it)) }, Modifier.fillMaxWidth(), textStyle = PicoTheme.typography.bodyMedium.copy(fontSize = 18.sp), singleLine = false, maxLines = 2)
    Label("Pairing token")
    TextField(state.token, { event(BridgeUiEvent.TokenChanged(it)) }, Modifier.fillMaxWidth(), textStyle = PicoTheme.typography.bodyMedium.copy(fontSize = 18.sp), visualTransformation = PasswordVisualTransformation(), singleLine = false, maxLines = 2)
    Action("Connect", { event(BridgeUiEvent.Connect) }, Modifier.fillMaxWidth())
    }
    Action("Refresh model", { event(BridgeUiEvent.Refresh) }, Modifier.fillMaxWidth())
    Action("Disconnect", { event(BridgeUiEvent.Disconnect) }, Modifier.fillMaxWidth())
    Spacer(Modifier.weight(1f))
    Action("Back to model", { event(BridgeUiEvent.ConnectionPanelChanged(false)) }, Modifier.fillMaxWidth())
}

@Composable
private fun Choice(label: String, selected: Boolean, action: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    val colors = if (selected) ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillPrimary,
        contentColor = PicoTheme.colorScheme.labelPrimaryLight) else ButtonDefaults.buttonColors()
    Button(onClick = action, modifier = modifier.height(56.dp), size = ButtonDefaults.Max, enabled = enabled, colors = colors) { Text(label) }
}
@Composable
private fun Action(label: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick = action, modifier = modifier.height(56.dp), size = ButtonDefaults.Max, enabled = enabled) { Text(label) }
@Composable internal fun Body(value: String) = Text(value, color = PicoTheme.colorScheme.labelPrimary, fontSize = 28.sp, lineHeight = 34.sp)
@Composable internal fun Label(value: String) = Text(value, color = PicoTheme.colorScheme.labelPrimary, fontSize = 16.sp, lineHeight = 22.sp)
