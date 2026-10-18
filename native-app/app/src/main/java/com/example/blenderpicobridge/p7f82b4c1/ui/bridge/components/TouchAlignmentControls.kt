package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*
import com.example.blenderpicobridge.p7f82b4c1.ui.theme.CreatorColors
import com.pico.spatial.ui.design.*

@Composable
internal fun TouchAlignmentControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    val right=state.touchingHand == TouchingHand.RIGHT
    val finger=if (right) "right" else "left"
    val opposite=if (right) "left" else "right"
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TouchBody("Touch marker centers",true)
        when (state.touchPhase) {
            TouchAlignmentPhase.SETUP -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TouchBody("Choose the finger that will touch each marker center. Keep that finger on the screen; confirm with the other hand.")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TouchHand("Right index",right,{ event(BridgeUiEvent.TouchHandChanged(TouchingHand.RIGHT)) },Modifier.weight(1f))
                    TouchHand("Left index",!right,{ event(BridgeUiEvent.TouchHandChanged(TouchingHand.LEFT)) },Modifier.weight(1f))
                }
                TouchBody("Touch with your $finger index. Pinch with your $opposite hand to record.")
            }
            TouchAlignmentPhase.CAPTURE -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                val corners=listOf("Top left","Top right","Bottom right","Bottom left")
                TouchBody("${state.touchCount.coerceIn(0,3)+1} of 4 · ${corners[state.touchCount.coerceIn(0,3)]}",true)
                TouchBody("Keep your $finger index fingertip on the marker center. Pinch with your $opposite hand only when ready.")
                TouchBody(state.touchStatus)
            }
            TouchAlignmentPhase.REVIEW -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                state.touchedMonitor?.let { TouchBody("Measured %.3f × %.3f m".format(it.width,it.height),true) }
                TouchBody("Use this alignment to position the monitor plane. Check the result, then choose Lock alignment.")
            }
            TouchAlignmentPhase.INVALID -> TouchBody("${state.touchStatus} Previous alignment is unchanged. Restart and touch each marker center again.")
            TouchAlignmentPhase.NONE -> Unit
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if (state.touchPhase == TouchAlignmentPhase.SETUP || state.touchPhase == TouchAlignmentPhase.REVIEW)
                TouchAction(if (state.touchPhase == TouchAlignmentPhase.SETUP) "Start touching" else "Use alignment",
                    { event(if (state.touchPhase == TouchAlignmentPhase.SETUP) BridgeUiEvent.StartTouchAlignment else BridgeUiEvent.UseTouchedAlignment) },Modifier.weight(1f))
            if (state.touchPhase != TouchAlignmentPhase.SETUP)
                TouchAction("Restart",{ event(BridgeUiEvent.RestartTouchAlignment) },Modifier.weight(1f))
        }
        TouchAction("Cancel",{ event(BridgeUiEvent.CancelTouchAlignment()) },Modifier.fillMaxWidth())
    }
}
@Composable
internal fun TouchBody(value: String,heading: Boolean=false) = Text(value,color=CreatorColors.monitorInk,
    fontSize=if (heading) 28.sp else 16.sp,lineHeight=if (heading) 34.sp else 22.sp)
@Composable
private fun TouchAction(label: String,action:()->Unit,modifier: Modifier) = Button(onClick=action,modifier=modifier.height(56.dp),size=ButtonDefaults.Max) { Text(label) }
@Composable
private fun TouchHand(label: String,selected: Boolean,action:()->Unit,modifier: Modifier) {
    val colors=ButtonDefaults.buttonColors(containerColor=if (selected) PicoTheme.colorScheme.fillPrimary else PicoTheme.colorScheme.fillLight,
        contentColor=if (selected) PicoTheme.colorScheme.labelPrimaryLight else CreatorColors.monitorInk)
    Button(onClick=action,modifier=modifier.height(56.dp),size=ButtonDefaults.Max,colors=colors) { Text(label) }
}
