package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class RenderBorderTest {
    @Test fun automaticBorderFollowsOnlyActiveSceneFit() {
        val state=RenderWindowState(faceUser=true,fitModel=true)
        assertFalse(state.borderVisible)
        assertTrue(state.copy(faceUser=false).borderVisible)
        assertTrue(state.copy(mode=RenderWindowMode.LIVE_DISPLAY).borderVisible)
    }
    @Test fun explicitChoiceSurvivesFittingAndCanBeResetToAutomatic() {
        val state=RenderWindowState(faceUser=true,fitModel=true,hideBorder=false)
        assertTrue(state.borderVisible)
        assertTrue(state.copy(fitModel=false).borderVisible)
        assertFalse(state.copy(hideBorder=true,faceUser=false).borderVisible)
        assertFalse(state.copy(hideBorder=null).borderVisible)
    }
}
