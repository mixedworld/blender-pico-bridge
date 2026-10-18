package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.RigidPose
import kotlin.math.*

data class RenderBillboardFit(val position: List<Float>,val rotation: List<Float>,val width: Float)

/** Container metres, +Z faces the eye. Enclosing sphere placement and projected visual-box coverage. */
class BuildRenderBillboardUseCase {
    private fun add(a: List<Float>,b: List<Float>)=List(3) { a[it]+b[it] }
    private fun sub(a: List<Float>,b: List<Float>)=List(3) { a[it]-b[it] }
    private fun mul(a: List<Float>,s: Float)=a.map { it*s }
    private fun dot(a: List<Float>,b: List<Float>)=a.indices.sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    private fun cross(a: List<Float>,b: List<Float>)=listOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    private fun valid(p: List<Float>)=p.size==3 && p.all(Float::isFinite)
    private fun unit(p: List<Float>): List<Float> { val n=sqrt(dot(p,p));require(n>1e-5f);return mul(p,1f/n) }
    private fun axes(eye: List<Float>,centre: List<Float>): List<List<Float>> {
        require(valid(eye) && valid(centre))
        val z=unit(sub(eye,centre))
        return axesFromNormal(z)
    }
    private fun axesFromNormal(z: List<Float>): List<List<Float>> {
        val up=if(abs(z[1])<.98f) listOf(0f,1f,0f) else listOf(0f,0f,1f)
        val x=unit(cross(up,z));return listOf(x,cross(z,x),z)
    }
    private fun quaternion(axes: List<List<Float>>)=RigidPose(List(9) { i -> axes[i%3][i/3].toDouble() },listOf(0.0,0.0,0.0))
        .quaternion().map(Double::toFloat)
    fun facing(eye: List<Float>,centre: List<Float>): List<Float>? = runCatching { quaternion(axes(eye,centre)) }.getOrNull()

    fun fit(eye: List<Float>,corners: List<List<Float>>,aspect: Float,stereoEyes: List<List<Float>> = emptyList(),
        previousNormal: List<Float> = listOf(0f,0f,1f)): RenderBillboardFit? = runCatching {
        require(valid(eye) && corners.size>=2 && corners.all(::valid))
        require(aspect.isFinite() && aspect in .1f..10f && stereoEyes.all(::valid))
        require(valid(previousNormal))
        val centre=List(3) { i -> corners.sumOf { it[i].toDouble() }.toFloat()/corners.size }
        val radius=corners.maxOf { val d=sub(it,centre);sqrt(dot(d,d)) }
        require(radius.isFinite() && radius>0f)
        val inside=(0..2).all { i -> eye[i]>=corners.minOf { it[i] } && eye[i]<=corners.maxOf { it[i] } }
        // Keep the last facing while inside, including at the exact centre. Looking
        // towards the centre there would flip the plane through the viewer by 180°.
        val axes=if(inside) axesFromNormal(unit(previousNormal)) else axes(eye,centre)
        val (x,y,z)=axes
        val viewers=listOf(eye)+stereoEyes
        // Largest half-axis misses box corners: the enclosing radius is the
        // half diagonal. This also works for oriented box corners.
        val nearest=dot(sub(eye,centre),z)-radius
        val minimumDistance=.25f-viewers.minOf { dot(sub(it,eye),z) }
        val close=nearest-.03f<minimumDistance
        // Normally centre + normal * (radius + .03), putting the full box behind
        // the plane from every direction. Eye safety wins only in close-up.
        val distance=min(9.5f,max(minimumDistance,nearest-.03f))
        val position=sub(eye,mul(z,distance))
        var halfWidth=0f
        var crossesEye=false
        for(viewer in viewers) {
            val planeDepth=dot(sub(viewer,position),z);require(planeDepth>=.24999f)
            for(point in corners) {
                val ray=sub(point,viewer);val depth=-dot(ray,z)
                if(depth<=.005f) { crossesEye=true;continue }
                val hit=add(viewer,mul(ray,planeDepth/depth))
                val offset=sub(hit,position)
                halfWidth=max(halfWidth,max(abs(dot(offset,x)),abs(dot(offset,y))*aspect))
            }
        }
        // Fitting a bound that crosses the eye has an unbounded projection. Crop
        // to a finite close-up (at most 120° in either dimension) instead.
        val nearestEyePlane=viewers.minOf { distance+dot(sub(it,eye),z) }
        val closeWidth=2f*nearestEyePlane*sqrt(3f)*min(1f,aspect)
        val padded=halfWidth*2f*1.08f
        val coverage=if(close) if(crossesEye) closeWidth else min(padded,closeWidth) else padded
        val width=max(max(coverage,.008f*distance),.005f)
        require(width.isFinite() && width<=100f && width/distance<18f)
        RenderBillboardFit(position,quaternion(axes),width)
    }.getOrNull()
}
