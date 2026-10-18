package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.CalibrationBoard
import kotlin.math.abs
import kotlin.math.sqrt

/** Four actual tracked contact points, ordered TL/TR/BR/BL. No camera extrinsic is involved. */
data class TouchedScreen(val center: List<Float>, val rotation: List<Float>, val width: Float, val height: Float)

class FitTouchedScreenUseCase {
    operator fun invoke(points: List<List<Float>>, board: CalibrationBoard): TouchedScreen {
        require(points.size == 4 && points.all { it.size == 3 && it.all(Float::isFinite) })
        fun sub(a: List<Float>,b: List<Float>)=List(3) { a[it]-b[it] }
        fun dot(a: List<Float>,b: List<Float>)=a.indices.sumOf { (a[it]*b[it]).toDouble() }.toFloat()
        fun length(a: List<Float>)=sqrt(dot(a,a))
        fun unit(a: List<Float>)=length(a).let { n -> require(n > .01f); a.map { it/n } }
        fun cross(a: List<Float>,b: List<Float>)=listOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
        val top=sub(points[1],points[0]); val bottom=sub(points[2],points[3])
        val left=sub(points[0],points[3]); val right=sub(points[1],points[2])
        require(length(top) in .05f..2f && length(left) in .05f..2f) { "Contact points are too close or too far apart" }
        require(abs(length(top)-length(bottom))/length(top) < .12f && abs(length(left)-length(right))/length(left) < .12f) { "Retouch the marker centers; opposite edges disagree" }
        val x=unit(List(3) { top[it]+bottom[it] }); val up=unit(List(3) { left[it]+right[it] })
        require(abs(dot(x,up)) < .15f) { "Contact points do not form a rectangle" }
        val y=unit(List(3) { up[it]-x[it]*dot(x,up) }); val z=cross(x,y)
        require(abs(dot(z,sub(points[2],points[0]))) < .02f) { "Contact points are not on one plane" }
        val markers=board.markers.sortedBy { it.id }
        require(markers.map { it.id } == listOf(0,1,2,3) && board.width > 0 && board.height > 0)
        val px=markers.map { it.x+it.width*.5f }; val py=markers.map { it.y+it.height*.5f }
        val spanX=((px[1]-px[0])+(px[2]-px[3]))*.5f
        val spanY=((py[3]-py[0])+(py[2]-py[1]))*.5f
        require(spanX > 0 && spanY > 0)
        val pitchX=(length(top)+length(bottom))*.5f/spanX
        val pitchY=(length(left)+length(right))*.5f/spanY
        require(pitchX/pitchY in .75f..1.25f) { "Contact points disagree with the displayed marker layout" }
        val width=pitchX*board.width; val height=pitchY*board.height
        require(width in .1f..2f && height in .1f..2f)
        val center=List(3) { axis -> points.map { it[axis] }.average().toFloat()+
            x[axis]*(board.width*.5f-px.average().toFloat())*pitchX+
            y[axis]*(py.average().toFloat()-board.height*.5f)*pitchY }
        // Rotation matrix columns are screen-right, screen-up and outward normal.
        val m00=x[0];val m01=y[0];val m02=z[0];val m10=x[1];val m11=y[1];val m12=z[1];val m20=x[2];val m21=y[2];val m22=z[2]
        val q=when {
            m00+m11+m22 > 0 -> { val s=sqrt(m00+m11+m22+1f)*2; listOf((m21-m12)/s,(m02-m20)/s,(m10-m01)/s,.25f*s) }
            m00 > m11 && m00 > m22 -> { val s=sqrt(1f+m00-m11-m22)*2;listOf(.25f*s,(m01+m10)/s,(m02+m20)/s,(m21-m12)/s) }
            m11 > m22 -> { val s=sqrt(1f+m11-m00-m22)*2;listOf((m01+m10)/s,.25f*s,(m12+m21)/s,(m02-m20)/s) }
            else -> { val s=sqrt(1f+m22-m00-m11)*2;listOf((m02+m20)/s,(m12+m21)/s,.25f*s,(m10-m01)/s) }
        }
        val norm=sqrt(q.sumOf { (it*it).toDouble() }).toFloat()
        return TouchedScreen(center,q.map { it/norm },width,height)
    }
}
