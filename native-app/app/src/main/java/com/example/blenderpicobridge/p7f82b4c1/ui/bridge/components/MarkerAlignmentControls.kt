package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*
import com.example.blenderpicobridge.p7f82b4c1.ui.theme.CreatorColors
import com.pico.spatial.ui.design.*
import kotlin.math.roundToInt

/** Bounded marker-curve-design wizard. The host owns padding and scrolling exactly once. */
@Composable
internal fun MarkerAlignmentControls(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    val settings=state.markerSettings
    val frame=state.markerFrame
    val review=state.markerPhase==MarkerPhase.REVIEW
    val scanning=state.markerPhase==MarkerPhase.SCAN
    val unavailable=scanning && state.scanPurpose==null
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TouchBody("Known-size marker",true)
        TouchBody(if(review) "Review" else if(scanning) "Scan" else "Setup")
        if (scanning && !unavailable) {
            MarkerScanActions(state,event)
            TouchBody(state.scanStatus,true)
        }
        if ((scanning && !unavailable) || review) {
            MarkerCameraFeedback(frame)
            TouchBody("Solid: detected · Dashed: fitted projection")
        }
        if (!review && !unavailable) {
            TouchBody("Marker black edge · mm")
            TouchBody("${settings.sideMm.roundToInt()} mm · ${if(settings.confirmed) "confirmed" else "unconfirmed"}",true)
            Slider(settings.sideMm,{ event(BridgeUiEvent.MarkerSideChanged(it.roundToInt().toFloat())) },
                valueRange=10f..200f,modifier=Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription="Measured marker black edge in millimeters" })
            TouchBody("Total horizontal curve · degrees")
            TouchBody("${settings.curveDegrees.roundToInt()}°${if(settings.curveDegrees==0f) " · flat" else ""}",true)
            Slider(settings.curveDegrees,{ event(BridgeUiEvent.MarkerCurveChanged(it.roundToInt().toFloat())) },
                valueRange=0f..120f,modifier=Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription="Total horizontal curve angle in degrees" })
            if (!scanning) MarkerCurveProfile(settings.curveDegrees)
        }
        when {
            review -> {
                frame?.estimate?.let { fit ->
                    TouchBody("Arc width ${(fit.arcWidth*1000).roundToInt()} mm · Height ${(fit.height*1000).roundToInt()} mm")
                    val radius=CurvedScreenUseCase(fit.arcWidth,fit.height,fit.curveDegrees).radius
                    TouchBody(if(radius==null) "Flat · 0°" else "Radius ${(radius*1000).roundToInt()} mm · ${fit.curveDegrees.roundToInt()}°")
                    TouchBody("Marker black edge ${settings.sideMm.roundToInt()} mm · Fit error %.1f px".format(fit.errorPx))
                }
                TouchBody(if(frame?.worldAlignment!=null) "Validated world pose ready. Apply to align and lock the monitor automatically." else "Position manually after applying size.")
                MarkerAction(if(frame?.worldAlignment!=null) "Use alignment" else "Use size + curve",{ event(BridgeUiEvent.UseMarkerGeometry) },Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MarkerAction("Rescan",{ event(BridgeUiEvent.StartMarkerScan) },Modifier.weight(1f))
                    MarkerAction("Back",{ event(BridgeUiEvent.BackFromMarkers) },Modifier.weight(1f))
                }
            }
            unavailable -> {
                TouchBody("Unable to scan",true)
                TouchBody(state.scanStatus)
                MarkerAction("Rescan",{ event(BridgeUiEvent.StartMarkerScan) },Modifier.fillMaxWidth())
                MarkerAction("Position manually",{ event(BridgeUiEvent.BackFromMarkers) },Modifier.fillMaxWidth())
                MarkerAction("Back",{ event(BridgeUiEvent.BackFromMarkers) },Modifier.fillMaxWidth())
            }
            scanning -> Unit
            else -> {
                TouchBody(if(settings.confirmed) "Measured black edge set. Keep Blender's window and marker pixel size unchanged."
                    else "Measure the physical black outer edge, excluding the white margin. Set that measured value before scanning. The 50 mm default is unconfirmed.")
                if(state.scanStatus.isNotBlank()) TouchBody(state.scanStatus)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MarkerAction("Scan",{ event(BridgeUiEvent.StartMarkerScan) },Modifier.weight(1f))
                    MarkerAction("Back",{ event(BridgeUiEvent.BackFromMarkers) },Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MarkerScanActions(state: BridgeUiState,event: (BridgeUiEvent)->Unit) {
    val frame=state.markerFrame
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        MarkerAction("Pause for review",{ event(BridgeUiEvent.PauseMarkerReview) },Modifier.weight(1f),
            frame?.estimate!=null && frame.stableSamples>=8 && frame.settings==state.markerSettings && (!frame.worldRequired || frame.worldAlignment!=null))
        MarkerAction("Stop",{ event(BridgeUiEvent.StopScan) },Modifier.weight(1f))
    }
}

@Composable
private fun MarkerAction(label: String,action:()->Unit,modifier: Modifier,enabled: Boolean=true) =
    Button(onClick=action,modifier=modifier.height(56.dp),enabled=enabled,size=ButtonDefaults.Max) { Text(label) }

/** Domain visualization, not an extra app-owned panel or passthrough replacement. */
@Composable
private fun MarkerCameraFeedback(frame: MarkerScanFrame?) {
    val ink=CreatorColors.monitorInk.toArgb()
    val halo=PicoTheme.colorScheme.labelPrimaryLight.toArgb()
    val bitmap=remember(frame,ink,halo) { frame?.let { cameraBitmap(it,ink,halo) } }
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap.asImageBitmap(),"Camera image feedback; observed solid, fitted dashed",Modifier.size(320.dp,240.dp))
        else Box(Modifier.size(320.dp,240.dp),contentAlignment=Alignment.Center) { TouchBody("Waiting for camera feedback…") }
    }
}

private fun cameraBitmap(frame: MarkerScanFrame,ink: Int,halo: Int): Bitmap {
    val w=frame.previewWidth;val h=frame.previewHeight
    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    if(frame.previewRgb.size==w*h*3) {
        val pixels=IntArray(w*h) { i -> val b=frame.previewRgb
            (0xff shl 24) or ((b[i*3].toInt() and 255) shl 16) or ((b[i*3+1].toInt() and 255) shl 8) or (b[i*3+2].toInt() and 255) }
        bitmap.setPixels(pixels,0,w,0,0,w,h)
    }
    val canvas=Canvas(bitmap)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    fun polygon(points: List<ImagePoint>,dashed: Boolean) {
        if(points.isEmpty()) return
        val path=Path();path.moveTo(points[0].x*w/1280,points[0].y*h/960)
        points.drop(1).forEach { path.lineTo(it.x*w/1280,it.y*h/960) };path.close()
        paint.pathEffect=if(dashed) DashPathEffect(floatArrayOf(7f,5f),0f) else null
        paint.color=halo;paint.alpha=255;paint.strokeWidth=4f;canvas.drawPath(path,paint)
        paint.color=ink;paint.alpha=if(dashed) 166 else 255;paint.strokeWidth=2f;canvas.drawPath(path,paint)
    }
    frame.observed.forEach { polygon(it.points,false) }
    frame.projected.forEach { polygon(it.points,true) }
    polygon(frame.outline,true)
    return bitmap
}

@Composable
private fun MarkerCurveProfile(degrees: Float) {
    val ink=CreatorColors.monitorInk.toArgb()
    val bitmap=remember(degrees,ink) {
        val geometry=CurvedScreenUseCase(1f,.6f,degrees)
        val result=Bitmap.createBitmap(320,64,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ink;style=Paint.Style.STROKE;strokeWidth=2f }
        val path=Path()
        for(i in 0..36) {
            val p=geometry.point(i/36f,.5f)
            val x=160+p[0]/geometry.chord*288;val y=56-p[2]*180
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        Canvas(result).drawPath(path,paint);result
    }
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        Image(bitmap.asImageBitmap(),"Horizontal curve profile; zero degrees is flat",Modifier.size(320.dp,64.dp))
    }
}
