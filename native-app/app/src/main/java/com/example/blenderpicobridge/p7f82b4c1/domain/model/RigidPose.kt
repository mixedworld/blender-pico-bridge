package com.example.blenderpicobridge.p7f82b4c1.domain.model

import kotlin.math.*

/** Rigid transform, column-vector convention. Rotation is row-major; translation is metres. */
data class RigidPose(val r: List<Double>,val t: List<Double>) {
    init { require(r.size==9 && t.size==3 && (r+t).all(Double::isFinite)) }
    operator fun times(other: RigidPose)=RigidPose(
        List(9) { i -> (0..2).sumOf { k -> r[(i/3)*3+k]*other.r[k*3+i%3] } },
        List(3) { row -> t[row]+(0..2).sumOf { k -> r[row*3+k]*other.t[k] } })
    fun inverse(): RigidPose {
        val transpose=List(9) { i -> r[(i%3)*3+i/3] }
        return RigidPose(transpose,List(3) { row -> -(0..2).sumOf { k -> transpose[row*3+k]*t[k] } })
    }
    fun distance(other: RigidPose)=sqrt(t.indices.sumOf { (t[it]-other.t[it]).pow(2) })
    fun angle(other: RigidPose): Double {
        val a=quaternion();val b=other.quaternion()
        return 2*acos(abs(a.indices.sumOf { a[it]*b[it] }).coerceIn(0.0,1.0))
    }
    /** Quaternion x,y,z,w; the sign carries no physical distinction. */
    fun quaternion(): List<Double> {
        val trace=r[0]+r[4]+r[8]
        val q=when {
            trace>0 -> { val s=sqrt(trace+1)*2;listOf((r[7]-r[5])/s,(r[2]-r[6])/s,(r[3]-r[1])/s,s/4) }
            r[0]>r[4] && r[0]>r[8] -> { val s=sqrt(1+r[0]-r[4]-r[8])*2;listOf(s/4,(r[1]+r[3])/s,(r[2]+r[6])/s,(r[7]-r[5])/s) }
            r[4]>r[8] -> { val s=sqrt(1+r[4]-r[0]-r[8])*2;listOf((r[1]+r[3])/s,s/4,(r[5]+r[7])/s,(r[2]-r[6])/s) }
            else -> { val s=sqrt(1+r[8]-r[0]-r[4])*2;listOf((r[2]+r[6])/s,(r[5]+r[7])/s,s/4,(r[3]-r[1])/s) }
        }
        val norm=sqrt(q.sumOf { it*it });require(norm>1e-9)
        return q.map { it/norm }
    }
    companion object {
        fun fromQuaternion(t: List<Double>,quaternion: List<Double>): RigidPose {
            require(quaternion.size==4 && quaternion.all(Double::isFinite))
            val norm=sqrt(quaternion.sumOf { it*it });require(norm>.5 && norm<1.5)
            val (x,y,z,w)=quaternion.map { it/norm }
            return RigidPose(listOf(1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),
                2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),
                2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)),t)
        }
        fun mean(poses: List<RigidPose>): RigidPose {
            require(poses.isNotEmpty())
            val reference=poses.first().quaternion()
            val qs=poses.map { it.quaternion().let { q -> if(q.indices.sumOf { i -> q[i]*reference[i] }<0) q.map { -it } else q } }
            return fromQuaternion(List(3) { i -> poses.sumOf { it.t[i] }/poses.size },List(4) { i -> qs.sumOf { it[i] }/poses.size })
        }
    }
}

data class HeadMarkerSample(val globalFromHead: RigidPose,val cameraFromMarker: RigidPose)
data class WorldMarkerAlignment(val globalFromMarker: RigidPose,val samples: Int,
    val positionRms: Double,val angleRms: Double)
