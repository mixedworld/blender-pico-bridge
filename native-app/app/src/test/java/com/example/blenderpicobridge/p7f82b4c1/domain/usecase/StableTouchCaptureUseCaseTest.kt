package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class StableTouchCaptureUseCaseTest {
    private val point=listOf(.3f,1.2f,-.8f)
    @Test fun requiresStableActualContactAndFreshOppositePinch() {
        val capture=StableTouchCaptureUseCase()
        repeat(14) { i -> capture.update(point,.1f,true,i*20L) }
        val result=capture.update(point,.9f,true,280)
        assertEquals(ContactReadiness.CAPTURED,result.readiness);assertEquals(point,result.point)
        repeat(14) { i -> assertNull(capture.update(point,.9f,true,300+i*20L).point) }
        capture.update(point,.1f,true,580)
        assertEquals(ContactReadiness.CAPTURED,capture.update(point,.9f,true,600).readiness)
    }
    @Test fun movingOrMissingDataCannotBecomeAContactByHoldingPinch() {
        val capture=StableTouchCaptureUseCase()
        repeat(14) { i -> capture.update(listOf(.3f+i*.01f,1.2f,-.8f),.1f,true,i*20L) }
        assertNull(capture.update(point,.9f,true,280).point)
        repeat(20) { i -> assertNull(capture.update(point,.9f,true,300+i*20L).point) }
        assertEquals(ContactReadiness.TRACKING_NEEDED,capture.update(null,.1f,true,700).readiness)
    }
    @Test fun dataGapDisarmsAndDropsStaleContactWindow() {
        val capture=StableTouchCaptureUseCase()
        repeat(14) { i -> capture.update(point,.1f,true,i*20L) }
        assertNull(capture.update(point,.9f,true,800).point)
        assertEquals(ContactReadiness.RELEASE_PINCH,capture.update(point,.9f,true,820).readiness)
    }
    @Test fun callbacksSharingOneMillisecondDoNotResetTheStabilityWindow() {
        val capture=StableTouchCaptureUseCase()
        repeat(14) { i ->
            capture.update(point,.1f,true,i*20L)
            capture.update(point,.1f,true,i*20L)
        }
        val result=capture.update(point,.9f,true,280)
        assertEquals(ContactReadiness.CAPTURED,result.readiness)
        assertTrue(result.spanMillis >= 200)
        assertNull(capture.update(point,.9f,true,280).point)
    }

}
