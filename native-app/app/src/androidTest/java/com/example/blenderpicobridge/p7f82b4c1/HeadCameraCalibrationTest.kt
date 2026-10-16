package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blenderpicobridge.p7f82b4c1.data.repository.HeadCameraCalibrationRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class HeadCameraCalibrationTest {
    @Before fun setup() { assertTrue(OpenCVLoader.initLocal()) }
    private fun rotation(axis: Int,degrees: Double): RigidPose {
        val q=MutableList(4) { 0.0 };q[axis]=sin(Math.toRadians(degrees)/2);q[3]=cos(Math.toRadians(degrees)/2)
        return RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),q)
    }
    private val camera=RigidPose(rotation(0,180.0).r,listOf(.032,.017,-.054))
    private val marker=RigidPose(rotation(1,12.0).r,listOf(.4,1.2,-1.0))
    private fun observations(yawOnly: Boolean=false)=List(20) { i ->
        val orientation=rotation(1,(i%5-2)*12.0)*rotation(0,if(yawOnly) 0.0 else (i/5-1.5)*12.0)
        val head=RigidPose(orientation.r,listOf((i%5-2)*.025,1.6+(i/5-1.5)*.02,.05))
        HeadMarkerSample(head,camera.inverse()*head.inverse()*marker)
    }
    @Test fun recoversCameraOffsetAndGlobalMonitorWithIndependentHoldouts() {
        val result=HeadCameraCalibrationRepository().solve(observations())
        assertNotNull(result)
        assertTrue(camera.distance(result!!.headFromCamera)<.0001)
        assertTrue(camera.angle(result.headFromCamera)<.0001)
        assertTrue(marker.distance(result.alignment.globalFromMarker)<.0001)
        assertTrue(marker.angle(result.alignment.globalFromMarker)<.0001)
    }
    @Test fun yawOnlyIsNotAnObservableCameraCalibration() {
        assertNull(HeadCameraCalibrationRepository().solve(observations(true)))
    }
    @Test fun incorrectHeldOutPoseCannotProduceAcceptedWorldPlacement() {
        val observations=observations().toMutableList()
        val bad=observations[0]
        observations[0]=bad.copy(cameraFromMarker=RigidPose(bad.cameraFromMarker.r,bad.cameraFromMarker.t.map { it+.15 }))
        assertNull(HeadCameraCalibrationRepository().solve(observations))
    }
}
