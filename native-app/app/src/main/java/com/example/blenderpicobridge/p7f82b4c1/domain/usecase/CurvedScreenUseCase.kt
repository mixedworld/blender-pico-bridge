package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import kotlin.math.*

/** Metres, +X right, +Y up, +Z towards viewer. Width is surface arc length. */
class CurvedScreenUseCase(val width: Float, val height: Float, val degrees: Float) {
    init {
        require(width.isFinite() && width in .1f..2f && height.isFinite() && height in .1f..2f)
        require(degrees.isFinite() && degrees in 0f..120f)
    }
    val angle = degrees * (PI.toFloat()/180f)
    val radius: Float? = if (angle < .00001f) null else width/angle
    val chord: Float = radius?.let { 2*it*sin(angle/2) } ?: width
    val edgeDepth: Float = radius?.let { it*(1-cos(angle/2)) } ?: 0f
    fun point(u: Float,v: Float): List<Float> {
        val phi=(u-.5f)*angle
        return listOf(radius?.let { it*sin(phi) } ?: ((u-.5f)*width),(.5f-v)*height,
            radius?.let { it*(1-cos(phi)) } ?: 0f)
    }
    data class Strip(val x: Float,val z: Float,val length: Float,val yaw: Float)
    fun strips(count: Int=32): List<Strip> {
        require(count in 1..128)
        return List(if (radius==null) 1 else count) { index ->
            val n=if (radius==null) 1 else count
            val a=point(index.toFloat()/n,.5f);val b=point((index+1f)/n,.5f)
            val dx=b[0]-a[0];val dz=b[2]-a[2]
            Strip((a[0]+b[0])/2,(a[2]+b[2])/2,sqrt(dx*dx+dz*dz),-atan2(dz,dx))
        }
    }
}
