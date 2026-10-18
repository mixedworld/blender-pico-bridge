package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.MarkerCameraProfile
import org.junit.Assert.*
import org.junit.Test

class SelectMarkerIntrinsicsUseCaseTest {
    private val sdk=floatArrayOf(800f,0f,639.5f,0f,800f,479.5f,0f,0f,1f)
    private val measured=sdk.toList().toMutableList().apply { this[0]=950f;this[4]=740f }.toList()
    private val profile=MarkerCameraProfile(1280,960,sdk.toList(),measured,.3f,15f,12f,36)
    private val select=SelectMarkerIntrinsicsUseCase()
    @Test fun onlyMatchingMeasuredProfileIsSelectedWithoutMutatingSdkIntrinsics() {
        assertArrayEquals(measured.toFloatArray(),select(sdk,1280,960,profile),0f)
        assertEquals(800f,sdk[0],0f)
        assertArrayEquals(sdk,select(sdk,1280,960,null),0f)
    }
    @Test fun sizeCameraChangesAndUncertainMalformedCalibrationKeepSdkValues() {
        val bad=listOf(profile.copy(width=640),profile.copy(sdkK=sdk.map { it+1 }),profile.copy(rms=2f),
            profile.copy(stdFx=100f),profile.copy(samples=8),profile.copy(measuredK=measured.dropLast(1)),
            profile.copy(stdFy=Float.NaN),profile.copy(measuredK=measured.map { Float.NaN }))
        bad.forEach { assertArrayEquals(sdk,select(sdk,1280,960,it),0f) }
    }
}
