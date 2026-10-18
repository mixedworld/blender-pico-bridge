package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.*

/** Physical-screen projection. Does not substitute VST intrinsics or an assumed headset optical FOV. */
class BuildMonitorCameraUseCase {
    operator fun invoke(eyeInMonitor: List<Float>,positionInModel: List<Float>,screenRotationInModel: List<Float>,
        modelWorldScale: Float,monitor: MonitorCalibration,clipAtMonitor: Boolean): TrackedViewportCamera {
        require(eyeInMonitor.size==3 && positionInModel.size==3 && screenRotationInModel.size==4)
        require((eyeInMonitor+positionInModel+screenRotationInModel).all(Float::isFinite))
        require(modelWorldScale.isFinite() && modelWorldScale>1e-6f && modelWorldScale<1e6f)
        val (x,y,z)=eyeInMonitor
        require(z in .03f..10f) { "Stand in front of the aligned monitor" }
        val width=CurvedScreenUseCase(monitor.width,monitor.height,monitor.curveDegrees).chord
        val slopes=listOf((-width/2-x)/z,(width/2-x)/z,(-monitor.height/2-y)/z,(monitor.height/2-y)/z)
        require(slopes.all { abs(it)<20f } && slopes[1]-slopes[0] in .0072f..19f && slopes[3]-slopes[2]>1e-4f)
        val norm=sqrt(screenRotationInModel.sumOf { (it*it).toDouble() }).toFloat()
        require(norm>.99f && norm<1.01f)
        val near=(if(clipAtMonitor) z else .005f)/modelWorldScale
        val far=min(1e6f,max(near+1f,1000f/modelWorldScale))
        require(near>=1e-5f && near<far)
        return TrackedViewportCamera(positionInModel,screenRotationInModel.map { it/norm },slopes,near,far)
    }
}
