package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class JointPinchStrengthUseCaseTest {
    private val strength=JointPinchStrengthUseCase()
    private val thumb=listOf(.2f,1.2f,-.8f)
    private fun finger(gap: Float)=listOf(.2f+gap,1.2f,-.8f)
    @Test fun trackedJointPinchWorksWithoutSystemPinchReadiness() {
        val capture=StableTouchCaptureUseCase()
        repeat(14) { capture.update(thumb,strength(thumb,finger(.06f)),true,it*20L) }
        val result=capture.update(thumb,strength(thumb,finger(.015f)),true,280)
        assertEquals(ContactReadiness.CAPTURED,result.readiness)
        repeat(20) { assertNull(capture.update(thumb,strength(thumb,finger(.015f)),true,300+it*20L).point) }
    }
    @Test fun distancesHaveSeparateOpenAndClosedThresholds() {
        assertEquals(0f,strength(thumb,finger(.06f))!!,0f)
        assertEquals(1f,strength(thumb,finger(.015f))!!,0f)
        val between=strength(thumb,finger(.032f))!!
        assertTrue(between in .25f.. .85f)
    }
    @Test fun absentNonfiniteOrInconsistentJointsCannotConfirm() {
        assertNull(strength(null,finger(.01f)))
        assertNull(strength(thumb,listOf(Float.NaN,1f,0f)))
        assertNull(strength(thumb,finger(.4f)))
    }
}
