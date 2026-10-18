package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.*

/** Expand a cropped render-window frustum to the full Blender WINDOW region behind its chrome. */
class BuildRenderCameraUseCase {
    operator fun invoke(eye: List<Float>,position: List<Float>,rotation: List<Float>,modelScale: Float,
        width: Float,aspect: Float,crop: List<Float>): TrackedViewportCamera {
        require(width.isFinite() && width>0 && aspect.isFinite() && aspect in .1f..10f)
        require(crop.size==4 && crop.all(Float::isFinite))
        val (u0,v0,u1,v1)=crop
        require(u0>=0 && v0>=0 && u1<=1 && v1<=1 && u1-u0>.01f && v1-v0>.01f)
        require(eye.size==3 && position.size==3 && rotation.size==4 && (eye+position+rotation).all(Float::isFinite))
        require(modelScale.isFinite() && modelScale>1e-6f && modelScale<1e6f)
        val (x,y,z)=eye
        require(z in .03f..10f)
        val norm=sqrt(rotation.sumOf { (it*it).toDouble() }).toFloat()
        require(norm>.99f && norm<1.01f)
        // Virtual windows are not physical monitors: do not inherit the monitor's
        // 0.1–2 m calibration bounds (a fitted window can be smaller or much larger).
        val l=(-width/2-x)/z;val r=(width/2-x)/z
        val b=(-width/aspect/2-y)/z;val t=(width/aspect/2-y)/z
        require(r-l in .0072f..19f && t-b>1e-4f)
        val near=.005f/modelScale
        val far=min(1e6f,max(near+1f,1000f/modelScale))
        require(near>=1e-5f && near<far)
        val dx=(r-l)/(u1-u0);val dy=(t-b)/(v1-v0)
        val slopes=listOf(l-dx*u0,r+dx*(1-u1),b-dy*v0,t+dy*(1-v1))
        require(slopes.all { kotlin.math.abs(it)<20 })
        require(slopes[1]-slopes[0]<20 && slopes[3]-slopes[2]<20)
        return TrackedViewportCamera(position,rotation.map { it/norm },slopes,near,far)
    }
}
