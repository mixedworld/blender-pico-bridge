package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionPose
import org.junit.Test
import org.junit.Assert.*

class ModelReplacementFitUseCaseTest {
    private val fit = ModelReplacementFitUseCase()
    private val staged = InspectionPose(listOf(.4f,1.2f,-.8f),listOf(0f,0f,0f,1f),1.6f,
        .053f,listOf(-.15f,.02f,.08f))

    @Test fun addingAndRemovingGeometryKeepExistingSourcePointsAtTheSameWorldPosition() {
        for (centre in listOf(listOf(80f,-20f,9f),listOf(-3f,4f,2f),listOf(0f,0f,0f))) {
            val replacement = fit(true,staged,centre,100f,0f,staged.scale)
            assertEquals(staged.fitScale,replacement.scale,0f)
            // A surviving authored vertex must not move when the new bounds centre changes.
            val point = listOf(2f,-1f,.3f)
            val before = List(3) { staged.position[it]+staged.scale*(point[it]*staged.fitScale+staged.fitPosition[it]) }
            val after = List(3) { staged.position[it]+staged.scale*(point[it]*replacement.scale+replacement.offset[it]) }
            assertEquals(before,after)
        }
    }
    @Test fun sourceSizeSelectedByUserAlsoSurvivesRoomUpdates() {
        val sourceSize = staged.copy(fitScale=1f,fitPosition=listOf(1f,2f,3f))
        assertEquals(ModelReplacementFitUseCase.Fit(1f,listOf(1f,2f,3f)),fit(true,sourceSize,listOf(99f,0f,0f),50f,0f,1f))
    }
    @Test fun firstRoomLoadStillStartsAtSourceSize() {
        assertEquals(ModelReplacementFitUseCase.Fit(1f,listOf(-2f,1f,-3f)),fit(true,null,listOf(2f,-1f,3f),2f,0f,1f))
    }
    @Test fun previewKeepsItsBoundedRefitPolicy() {
        val replacement=fit(false,staged,listOf(2f,0f,0f),2f,1.2f,1f)
        assertEquals(.5f,replacement.scale,.00001f)
        assertEquals(-1f,replacement.offset[0],.00001f)
    }
}
