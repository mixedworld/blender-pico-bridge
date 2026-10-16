package com.example.blenderpicobridge.p7f82b4c1

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blenderpicobridge.p7f82b4c1.data.repository.SpatialCameraRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in physical camera probe. Never records image pixels or pairing credentials. */
@RunWith(AndroidJUnit4::class)
class PhysicalMarkerProbeTest {
    @Test fun installMeasuredCameraProfile() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physical_camera")=="true")
        val json=JSONObject(requireNotNull(args.getString("profile")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("marker-camera-profile",Context.MODE_PRIVATE).edit()
            .putString("profile",json.toString()).putString("firmware",android.os.Build.FINGERPRINT).commit()
        println("CAMERA_PROFILE installed local measured profile")
    }

    @Test fun readPublicCameraCalibrationMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physical_camera")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cameras=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        println("CAMERA_METADATA ids=${cameras.cameraIdList.joinToString(",")}")
        for(id in cameras.cameraIdList) {
            val info=cameras.getCameraCharacteristics(id)
            println("CAMERA_METADATA id=$id active=${info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} "+
                "intrinsic=${info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.joinToString(",")} "+
                "distortion=${info.get(CameraCharacteristics.LENS_DISTORTION)?.joinToString(",")} "+
                "poseReference=${info.get(CameraCharacteristics.LENS_POSE_REFERENCE)} "+
                "translation=${info.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.joinToString(",")} "+
                "rotation=${info.get(CameraCharacteristics.LENS_POSE_ROTATION)?.joinToString(",")}")
        }
    }

    @Test fun sampleLiveMarkerResiduals() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physical_camera")=="true")
        val json=JSONObject(requireNotNull(args.getString("board")))
        fun marker(value: JSONObject): MarkerRect { val r=value.getJSONArray("rect")
            return MarkerRect(value.getInt("id"),r.getInt(0),r.getInt(1),r.getInt(2),r.getInt(3)) }
        val markers=json.getJSONArray("markers")
        val board=CalibrationBoard(json.getInt("width"),json.getInt("height"),List(markers.length()) { marker(markers.getJSONObject(it)) },
            json.optJSONObject("referenceMarker")?.let(::marker))
        val settings=MutableStateFlow(MarkerSettings(requireNotNull(args.getString("side_mm")).toFloat(),
            requireNotNull(args.getString("curve_deg")).toFloat(),true))
        val camera=SpatialCameraRepository(InstrumentationRegistry.getInstrumentation().targetContext,diagnostics=true,automaticWorld=false)
        val observations=mutableListOf<MarkerPolygon>()
        runBlocking {
            withTimeout(90000) {
                camera.scan(ScanPurpose.MARKER_POSE,board,settings).filterIsInstance<ScanUpdate.Markers>().filter { it.frame.observed.size>=4 }.take(30).collect { update ->
                    observations+=update.frame.observed
                    if(update.frame.stableSamples>=8) println("PHYSICAL_MARKER estimate_ready errorPx=${update.frame.estimate?.errorPx}")
                }
            }
        }
        args.getString("camera_k")?.let { text ->
            val initial=text.split(",").map(String::toFloat).toFloatArray()
            val result=MarkerCameraCalibrationProbe.fit(observations,settings.value.sideMm,initial)
            println("CAMERA_CALIBRATION k=${result.k.joinToString(",")} rms=${result.rms} focalStd=${result.focalStd} samples=${result.samples}")
        }
    }
}
