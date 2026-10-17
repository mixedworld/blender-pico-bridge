package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

/** Expand a cropped render-window frustum to the full Blender WINDOW region behind its chrome. */
class BuildRenderCameraUseCase {
    operator fun invoke(eye: List<Float>,position: List<Float>,rotation: List<Float>,modelScale: Float,
        width: Float,aspect: Float,crop: List<Float>): TrackedViewportCamera {
        require(width.isFinite() && width>0 && aspect.isFinite() && aspect in .1f..10f)
        require(crop.size==4 && crop.all(Float::isFinite))
        val (u0,v0,u1,v1)=crop
        require(u0>=0 && v0>=0 && u1<=1 && v1<=1 && u1-u0>.01f && v1-v0>.01f)
        val base=BuildMonitorCameraUseCase()(eye,position,rotation,modelScale,
            MonitorCalibration(width=width,height=width/aspect),false)
        val (l,r,b,t)=base.slopes
        val dx=(r-l)/(u1-u0);val dy=(t-b)/(v1-v0)
        val slopes=listOf(l-dx*u0,r+dx*(1-u1),b-dy*v0,t+dy*(1-v1))
        require(slopes.all { kotlin.math.abs(it)<20 })
        require(slopes[1]-slopes[0]<20 && slopes[3]-slopes[2]<20)
        return base.copy(slopes=slopes)
    }
}
