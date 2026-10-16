package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.CameraSpacePose
import org.junit.Assert.*
import org.junit.Test

class CvToSpatialCameraPoseUseCaseTest {
    @Test fun frontFacingMarkerHasIdentityRenderRotationAndNegativeCameraDepth() {
        val matrix=CvToSpatialCameraPoseUseCase()(CameraSpacePose(listOf(1f,0f,0f,0f,-1f,0f,0f,0f,-1f),listOf(.1f,.2f,.8f)))
        assertArrayEquals(floatArrayOf(1f,0f,0f,.1f,0f,1f,0f,-.2f,0f,0f,1f,-.8f,0f,0f,0f,1f),matrix,.00001f)
    }
    @Test fun basisConversionPreservesRightHandedObjectAxes() {
        val matrix=CvToSpatialCameraPoseUseCase()(CameraSpacePose(listOf(0f,0f,1f,0f,1f,0f,-1f,0f,0f),listOf(0f,0f,1f)))
        // Render X is +Z, render Y is -Y: their cross product is +X, matching render Z.
        assertEquals(1f,matrix[8],0f);assertEquals(-1f,matrix[5],0f);assertEquals(1f,matrix[2],0f)
        assertEquals(-1f,matrix[11],0f)
    }
}
