package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blenderpicobridge.p7f82b4c1.data.repository.AlignmentCodec
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MonitorHandlesUseCase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure serialization fixtures; never reads or modifies the user's saved room or camera profile. */
@RunWith(AndroidJUnit4::class)
class AlignmentCodecTest {
    private fun fixture(): SavedAlignment {
        val m=MonitorCalibration(enabled=true,locked=true,width=.8f,height=.4f,curveDegrees=30f,
            position=listOf(.01f,.02f,-.03f))
        val h=MonitorHandlesUseCase().from(m)
        return SavedAlignment("d8c6408c-44b9-45bd-8c04-4cffc346b530",AlignmentSnapshot(m,h,
            InspectionPose(listOf(.1f,.2f,-.3f),listOf(0f,0f,0f,1f),.9f,.053f,listOf(.1f,.2f,.3f)),"fixture"))
    }
    @Test fun exactFrameCurvatureCornersAndFitRoundTrip() {
        val value=fixture()
        assertEquals(value,AlignmentCodec.decode(AlignmentCodec.encode(value)))
    }
    @Test fun flatUnstagedAlignmentRoundTripsWithoutInventingAModelPose() {
        val value=fixture().let { it.copy(relative=it.relative.copy(stagedPose=null,revision=null)) }
        assertEquals(value,AlignmentCodec.decode(AlignmentCodec.encode(value)))
    }
    @Test fun corruptOrUnsupportedCalibrationCannotReachTheScene() {
        val original=AlignmentCodec.encode(fixture())
        for((key,bad) in listOf("version" to 99,"anchor" to "invalid","width" to -1,"curve" to 999)) {
            val text=JSONObject(original).put(key,bad).toString()
            assertTrue("Rejected $key",runCatching { AlignmentCodec.decode(text) }.isFailure)
        }
    }
}
