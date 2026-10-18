package com.example.blenderpicobridge.p7f82b4c1.data.repository

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.*

/** Measures the rigid camera/head offset from stationary, diverse paired observations. */
class HeadCameraCalibrationRepository {
    data class Result(val headFromCamera: RigidPose,val alignment: WorldMarkerAlignment)
    var status="Look left/right and tilt up/down; pause briefly at each angle."
        private set
    fun solve(samples: List<HeadMarkerSample>): Result? {
        if(samples.size<12) { status="Head views ${samples.size}/12 · turn and tilt, pausing at each angle";return null }
        val allocations=mutableListOf<Mat>()
        fun mat(rows: Int,cols: Int,values: List<Double>)=Mat(rows,cols,CvType.CV_64FC1).apply { put(0,0,*values.toDoubleArray());allocations+=this }
        try {
            val first=samples.first().globalFromHead
            val axes=samples.map { sample ->
                val relative=first.inverse()*sample.globalFromHead
                val rv=Mat().also(allocations::add)
                Calib3d.Rodrigues(mat(3,3,relative.r),rv)
                List(3) { rv.get(it,0)[0] }
            }
            val covariance=mat(3,3,List(9) { i -> axes.sumOf { it[i/3]*it[i%3] }/axes.size })
            val eigen=Mat().also(allocations::add)
            Core.eigen(covariance,eigen)
            if(eigen.get(1,0)[0]<.002 || samples.maxOf { first.angle(it.globalFromHead) }<Math.toRadians(18.0)) {
                status="Need more angles · turn left/right AND tilt up/down, pausing briefly";return null
            }
            // Hold out every fourth observation; it cannot influence the fitted camera/head offset.
            val train=samples.filterIndexed { i,_ -> i%4!=0 }
            val test=samples.filterIndexed { i,_ -> i%4==0 }
            val headR=train.map { mat(3,3,it.globalFromHead.r) };val headT=train.map { mat(3,1,it.globalFromHead.t) }
            val cameraR=train.map { mat(3,3,it.cameraFromMarker.r) };val cameraT=train.map { mat(3,1,it.cameraFromMarker.t) }
            val candidates=listOf(Calib3d.CALIB_HAND_EYE_PARK,Calib3d.CALIB_HAND_EYE_HORAUD).mapNotNull { method ->
                try {
                    val r=Mat().also(allocations::add);val t=Mat().also(allocations::add)
                    Calib3d.calibrateHandEye(headR,headT,cameraR,cameraT,r,t,method)
                    val x=RigidPose((0..2).flatMap { row -> (0..2).map { column -> r.get(row,column)[0] } },List(3) { t.get(it,0)[0] })
                    if(sqrt(x.t.sumOf { it*it })>.3) return@mapNotNull null
                    val worlds=train.map { it.globalFromHead*x*it.cameraFromMarker }
                    val mean=RigidPose.mean(worlds)
                    val validation=test.map { it.globalFromHead*x*it.cameraFromMarker }
                    val all=worlds+validation
                    val positionRms=sqrt(validation.sumOf { mean.distance(it).pow(2) }/validation.size)
                    val angleRms=sqrt(validation.sumOf { mean.angle(it).pow(2) }/validation.size)
                    if(positionRms>.012 || angleRms>Math.toRadians(1.2) ||
                        all.any { mean.distance(it)>.025 || mean.angle(it)>Math.toRadians(2.5) }) return@mapNotNull null
                    Result(x,WorldMarkerAlignment(mean,samples.size,positionRms,angleRms))
                } catch (_: CvException) { null } catch (_: IllegalArgumentException) { null }
            }
            return candidates.minByOrNull { it.alignment.positionRms+it.alignment.angleRms*.5 }.also {
                status=if(it==null) "World fit inconsistent · keep the monitor fixed and pause at more head angles"
                    else "World alignment ready · %.0f mm / %.1f° validation".format(it.alignment.positionRms*1000,Math.toDegrees(it.alignment.angleRms))
            }
        } finally { allocations.asReversed().forEach { it.release() } }
    }
}
