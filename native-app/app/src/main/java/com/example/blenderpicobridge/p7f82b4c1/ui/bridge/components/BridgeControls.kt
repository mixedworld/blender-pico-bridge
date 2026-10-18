package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*
import com.pico.spatial.ui.design.*

/** One task per page. Window-level TabBar belongs to ToolsScreen, outside this scroll body. */
@Composable
internal fun BridgeControls(state: BridgeUiState, event: (BridgeUiEvent) -> Unit, changeSpace: (Boolean) -> Unit) {
    CameraPermissionEffect(state,event)
    if (state.collapsed && state.hasModel) {
        Box(Modifier.requiredSize(184.dp,72.dp).padding(8.dp)) {
            Action("Show tools", { event(BridgeUiEvent.CollapseChanged(false)) },Modifier.fillMaxWidth())
        }
        return
    }
    key(state.toolsTab,state.hasModel,state.advancedAlignment,state.markerPhase,state.touchPhase) {
        val scroll=rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    when {
                        !state.hasModel || state.connectionPanel || state.toolsTab==ToolsTab.CONNECTION -> ConnectionControls(state,event)
                        state.toolsTab==ToolsTab.RENDER -> if(state.room)RenderWindowControls(state,event) else {
                            Label("Open Room to place a live render window beside the model.")
                            Action("Open Room",{ changeSpace(true) },Modifier.fillMaxWidth(),!state.enteringRoom)
                        }
                        state.toolsTab==ToolsTab.ALIGN -> when {
                            !state.room -> { Label("Open Room to align a frame with Blender’s viewport.");Action("Open Room",{changeSpace(true)},Modifier.fillMaxWidth(),!state.enteringRoom) }
                            state.markerPhase!=MarkerPhase.NONE -> MarkerAlignmentControls(state,event)
                            state.touchPhase!=TouchAlignmentPhase.NONE -> TouchAlignmentControls(state,event)
                            state.advancedAlignment -> AdvancedAlignmentControls(state,event)
                            else -> MonitorControls(state,event)
                        }
                        else -> ModelControls(state,event,changeSpace)
                    }
                }
                ScrollIndicator(state=scroll,orientation=Orientation.Vertical)
            }
            if(scroll.canScrollForward) Box(Modifier.padding(horizontal=24.dp,vertical=8.dp)) {Label("Scroll for more ↓")}
        }
    }
}

@Composable
private fun ColumnScope.ModelControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit,changeSpace: (Boolean)->Unit) {
    Label(state.status)
    SegmentControl {
        SegmentItem(selected=!state.room,onClick={if(!state.enteringRoom)changeSpace(false)},title={Text("Preview")})
        SegmentItem(selected=state.room,onClick={if(!state.enteringRoom)changeSpace(true)},title={Text("Room")})
    }
    SegmentControl {
        for(mode in InspectionMode.entries) SegmentItem(selected=state.mode==mode,
            onClick={event(BridgeUiEvent.ModeChanged(mode))},title={Text(if(mode==InspectionMode.SCALE) "Scale" else "Explode")})
    }
    Label(if(state.room && state.fitScale==1f) "Source scale · ${"%.2f".format(state.scale)}×" else "Auto-fit ${"%.3f".format(state.fitScale)}× · grab ${"%.2f".format(state.scale)}×")
    if(state.mode==InspectionMode.EXPLODE) Slider(value=state.explosion,onValueChange={event(BridgeUiEvent.ExplosionChanged(it))},modifier=Modifier.fillMaxWidth(),enabled=state.partCount>0)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Action(if(state.room) "100% source" else "Fit preview",{event(if(state.room)BridgeUiEvent.SourceSize else BridgeUiEvent.Fit)},Modifier.weight(1f))
        Action("Assemble",{event(BridgeUiEvent.ExplosionChanged(0f))},Modifier.weight(1f))
    }
    ListItem(headlineContent={Text("Show 3D model")},supportingContent={Text("Grabbing stays active when hidden")},trailingContent={Switch(state.modelVisible,{event(BridgeUiEvent.ModelVisibilityChanged(it))})})
    ListItem(headlineContent={Text("Highlight on hover")},trailingContent={Switch(state.hover,{event(BridgeUiEvent.HoverChanged(it))})})
    ListItem(headlineContent={Text("Link Blender viewport")},supportingContent={Text(if(state.renderWindow.enabled) "Managed by Render window mode" else "Enable viewport control in Blender first")},trailingContent={Switch(state.linkViewport,{event(BridgeUiEvent.LinkViewport(it))},enabled=state.viewportAllowed && !state.renderWindow.enabled)})
    ListItem(headlineContent={Text("Palm Tools shortcut")},supportingContent={Text(if(state.palmTools)state.palmStatus else "Room · look toward your open palm to recall Tools")},trailingContent={Switch(state.palmTools,{event(BridgeUiEvent.PalmTools(it))},enabled=state.room)})
    Action("Bring Tools launcher here",{event(BridgeUiEvent.RecenterTools)},Modifier.fillMaxWidth(),state.room)
    Action("Hide tools",{event(BridgeUiEvent.CollapseChanged(true))},Modifier.fillMaxWidth())
    Label(state.note)
    Label("Frame ${state.frame} · ${if(state.playing) "Playing" else "Paused"} · ${state.partCount} parts")
}

@Composable
private fun ColumnScope.MonitorControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    Label("Place cyan handles on Blender’s 3D viewport corners, not the monitor bezel. Move the amber top handle in/out to match the curve.")
    Action(if(state.monitor.editing) "Reset corner handles" else "Adjust corner handles",{event(BridgeUiEvent.AlignMonitor)},Modifier.fillMaxWidth())
    Label(state.handleError ?: "${"%.2f".format(state.monitor.width)} × ${"%.2f".format(state.monitor.height)} m · ${state.monitor.curveDegrees.toInt()}° curve")
    if(state.monitor.editing) {
        Action("Stage for take",{event(BridgeUiEvent.StageTake)},Modifier.fillMaxWidth(),state.handleError==null)
        Action("Lock alignment",{event(BridgeUiEvent.LockMonitor)},Modifier.fillMaxWidth(),state.handleError==null)
        Action("Back",{event(BridgeUiEvent.LeaveMonitor)},Modifier.fillMaxWidth())
    }
    Action("Reset take",{event(BridgeUiEvent.ResetTake)},Modifier.fillMaxWidth(),state.monitor.locked)
    ListItem(headlineContent={Text("Hide Blender source")},supportingContent={Text(if(state.renderWindow.enabled) "Kept visible while sharing the render window" else "Hide after extraction; restore on return or reset")},trailingContent={Switch(state.hideSource,{event(BridgeUiEvent.SourceHidingChanged(it))},enabled=!state.renderWindow.enabled)})
    Label(state.note)
    Action("Experimental calibration",{event(BridgeUiEvent.AdvancedAlignment(true))},Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.AdvancedAlignmentControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    Body("Experimental calibration")
    Label("Five handles are recommended. These alternatives need live camera or hand data; the emulator may not supply it.")
    if(!state.monitor.editing)Action("Start alignment",{event(BridgeUiEvent.AlignMonitor);event(BridgeUiEvent.AdvancedAlignment(true))},Modifier.fillMaxWidth())
    Action("Known-size marker",{event(BridgeUiEvent.OpenMarkerAlignment)},Modifier.fillMaxWidth(),state.monitor.editing && state.scanPurpose==null)
    Action("Touch marker centres",{event(BridgeUiEvent.OpenTouchAlignment)},Modifier.fillMaxWidth(),state.monitor.editing && state.scanPurpose==null)
    Action("Markers + depth",{event(BridgeUiEvent.ScanRequested(ScanPurpose.DEPTH))},Modifier.fillMaxWidth(),state.monitor.editing && state.scanPurpose==null)
    if(state.scanStatus.isNotBlank())Label(state.scanStatus)
    if(state.scanPurpose!=null)Action("Stop scanning",{event(BridgeUiEvent.StopScan)},Modifier.fillMaxWidth())
    state.measuredScreen?.let {
        Label("Measured ${"%.3f".format(it.width)} × ${"%.3f".format(it.height)} m")
        Action("Use measured size",{event(BridgeUiEvent.UseMeasuredSize)},Modifier.fillMaxWidth())
    }
    if(state.monitor.editing) {
        Label("Width · ${"%.2f".format(state.monitor.width)} m")
        Slider(state.monitor.width,{event(BridgeUiEvent.MonitorWidth(it))},valueRange=.1f..2f,modifier=Modifier.fillMaxWidth())
        Label("Height · ${"%.2f".format(state.monitor.height)} m")
        Slider(state.monitor.height,{event(BridgeUiEvent.MonitorHeight(it))},valueRange=.1f..2f,modifier=Modifier.fillMaxWidth())
    }
    Action("Back to alignment",{event(BridgeUiEvent.StopScan);event(BridgeUiEvent.AdvancedAlignment(false))},Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.ConnectionControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    if(!state.hasModel)Body("Connect to Blender")
    Label(state.status)
    if(state.hasModel) {
        Action("Refresh model",{event(BridgeUiEvent.Refresh)},Modifier.fillMaxWidth())
        Action("Scan a different QR",{event(BridgeUiEvent.ScanRequested(ScanPurpose.PAIRING))},Modifier.fillMaxWidth())
        Action("Disconnect",{event(BridgeUiEvent.Disconnect)},Modifier.fillMaxWidth())
        return
    }
    if(state.phase in setOf("connecting","receiving","reconnecting","refreshing")) {
        Label("Checking the bridge and receiving your model…")
        Action("Cancel",{event(BridgeUiEvent.Disconnect)},Modifier.fillMaxWidth())
        return
    }
    Label("Open PICO Bridge in Blender and start the server. Scan the current QR or enter its server address and token.")
    if(state.scanStatus.isNotBlank())Label(state.scanStatus)
    if(state.scanPurpose!=null)Action("Cancel scan",{event(BridgeUiEvent.StopScan)},Modifier.fillMaxWidth())
    else Action("Scan Blender QR",{event(BridgeUiEvent.ScanRequested(ScanPurpose.PAIRING))},Modifier.fillMaxWidth())
    if(!state.manualConnection)Action("Manual connection",{event(BridgeUiEvent.ManualConnection)},Modifier.fillMaxWidth())
    else {
        Label("Bridge URL")
        TextField(state.endpoint,{event(BridgeUiEvent.EndpointChanged(it))},Modifier.fillMaxWidth(),textStyle=PicoTheme.typography.bodyMedium.copy(fontSize=18.sp),singleLine=false,maxLines=2)
        Label("Pairing token")
        TextField(state.token,{event(BridgeUiEvent.TokenChanged(it))},Modifier.fillMaxWidth(),textStyle=PicoTheme.typography.bodyMedium.copy(fontSize=18.sp),visualTransformation=PasswordVisualTransformation(),singleLine=false,maxLines=2)
        Action("Connect",{event(BridgeUiEvent.Connect)},Modifier.fillMaxWidth())
    }
}

@Composable private fun Action(label: String,action: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true) =
    Button(onClick=action,modifier=modifier.height(56.dp),size=ButtonDefaults.Max,enabled=enabled) { Text(label) }
@Composable internal fun Body(value: String)=Text(value,color=PicoTheme.colorScheme.labelPrimary,fontSize=28.sp,lineHeight=34.sp)
@Composable internal fun Label(value: String)=Text(value,color=PicoTheme.colorScheme.labelPrimary,fontSize=16.sp,lineHeight=22.sp)
