package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.*

/** Draft points are TL/TR/BR/BL in placementOwner metres; sagitta is behind their chord. */
class MonitorHandlesUseCase {
    private fun add(a: List<Float>,b: List<Float>)=List(3) { a[it]+b[it] }
    private fun sub(a: List<Float>,b: List<Float>)=List(3) { a[it]-b[it] }
    private fun mul(a: List<Float>,s: Float)=a.map { it*s }
    private fun dot(a: List<Float>,b: List<Float>)=a.indices.sumOf { (a[it]*b[it]).toDouble() }.toFloat()
    private fun length(a: List<Float>)=sqrt(dot(a,a))
    private fun unit(a: List<Float>)=length(a).let { require(it>.01f) { "Separate the four corner handles" };mul(a,1f/it) }
    private fun cross(a: List<Float>,b: List<Float>)=listOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    private data class Frame(val c: List<Float>,val x: List<Float>,val y: List<Float>,val z: List<Float>,val w: Float,val h: Float)
    private fun frame(draft: MonitorHandleDraft): Frame {
        val p=draft.corners
        require(p.size==4 && p.all { it.size==3 && it.all(Float::isFinite) }) { "Place all four corners" }
        val x=unit(add(sub(p[1],p[0]),sub(p[2],p[3])))
        val up=add(sub(p[0],p[3]),sub(p[1],p[2]))
        val y=unit(sub(up,mul(x,dot(x,up))))
        return Frame(List(3) { k -> p.sumOf { it[k].toDouble() }.toFloat()/4 },x,y,cross(x,y),
            dot(x,add(sub(p[1],p[0]),sub(p[2],p[3])))/2,dot(y,up)/2)
    }
    fun from(calibration: MonitorCalibration): MonitorHandleDraft {
        val surface=CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees)
        val pose=RigidPose.fromQuaternion(calibration.position.map(Float::toDouble),calibration.quaternion.map(Float::toDouble))
        val corners=listOf(0f to 0f,1f to 0f,1f to 1f,0f to 1f).map { (u,v) ->
            val point=surface.point(u,v)
            List(3) { row -> (pose.t[row]+(0..2).sumOf { k -> pose.r[row*3+k]*point[k] }).toFloat() }
        }
        return MonitorHandleDraft(corners,surface.edgeDepth)
    }
    private fun normal(draft: MonitorHandleDraft,calibration: MonitorCalibration): List<Float> =
        runCatching { frame(draft).z }.getOrElse {
            val pose=RigidPose.fromQuaternion(calibration.position.map(Float::toDouble),calibration.quaternion.map(Float::toDouble))
            List(3) { pose.r[it*3+2].toFloat() }
        }
    fun positions(draft: MonitorHandleDraft,calibration: MonitorCalibration): List<List<Float>> {
        // Anchor to the actual top corners, even while the rectangle fit is invalid.
        val midpoint=mul(add(draft.corners[0],draft.corners[1]),.5f)
        return draft.corners+listOf(sub(midpoint,mul(normal(draft,calibration),draft.sagitta)))
    }
    /** Editable guide only: interpolates the raw corners and crown, independent of commit validation. */
    fun outline(draft: MonitorHandleDraft,calibration: MonitorCalibration): List<Pair<List<Float>,List<Float>>> {
        val p=positions(draft,calibration)
        val topMid=mul(add(p[0],p[1]),.5f)
        val bow=sub(p[4],topMid)
        fun point(a: List<Float>,b: List<Float>,u: Float)=add(add(mul(a,1-u),mul(b,u)),mul(bow,4*u*(1-u)))
        val result=mutableListOf<Pair<List<Float>,List<Float>>>()
        for(i in 0 until 32) {
            val a=i/32f;val b=(i+1)/32f
            result+=point(p[0],p[1],a) to point(p[0],p[1],b)
            result+=point(p[3],p[2],a) to point(p[3],p[2],b)
        }
        result+=p[0] to p[3];result+=p[1] to p[2]
        return result
    }
    fun move(draft: MonitorHandleDraft,index: Int,delta: List<Float>): MonitorHandleDraft {
        require(index in 0..4 && delta.size==3 && delta.all(Float::isFinite))
        if(index<4) return draft.copy(corners=draft.corners.mapIndexed { i,p -> if(i==index) add(p,delta) else p })
        val f=frame(draft)
        val sag=draft.sagitta-dot(delta,f.z)
        // Allow a tiny overpull to reach the exact flat stop; never generate a convex screen.
        require(sag<=f.w*.5f*tan(PI.toFloat()/6)+.001f) { "Curve exceeds 120 degrees; move the amber handle towards you" }
        return draft.copy(sagitta=max(0f,sag))
    }
    fun fit(draft: MonitorHandleDraft,base: MonitorCalibration): MonitorCalibration {
        val f=frame(draft)
        require(f.w in .1f..2f && f.h in .1f..2f) { "Place the four corners around a viewport 0.1–2 m wide and high" }
        val expected=listOf(-1f to 1f,1f to 1f,1f to -1f,-1f to -1f).map { (x,y) -> add(f.c,add(mul(f.x,x*f.w/2),mul(f.y,y*f.h/2))) }
        val residual=expected.indices.maxOf { length(sub(expected[it],draft.corners[it])) }
        require(residual<=max(.015f,min(f.w,f.h)*.06f)) { "Match opposite corners: the four points do not yet form a rectangular screen" }
        require(draft.sagitta.isFinite() && draft.sagitta>=0)
        val angle=4*atan2(2*draft.sagitta,f.w)
        require(angle<=2*PI.toFloat()/3+.0001f) { "Curve exceeds 120 degrees; move the amber handle towards you" }
        val arc=if(draft.sagitta<1e-6f) f.w else (f.w*f.w/4+draft.sagitta*draft.sagitta)/(2*draft.sagitta)*angle
        require(arc in .1f..2f) { "Curved surface arc width must be 0.1–2 m" }
        val pose=RigidPose(List(9) { i -> listOf(f.x,f.y,f.z)[i%3][i/3].toDouble() },sub(f.c,mul(f.z,draft.sagitta)).map(Float::toDouble))
        return base.copy(enabled=true,editing=true,locked=false,width=arc,height=f.h,curveDegrees=angle*180/PI.toFloat(),
            position=pose.t.map(Double::toFloat),quaternion=pose.quaternion().map(Double::toFloat))
    }
}
