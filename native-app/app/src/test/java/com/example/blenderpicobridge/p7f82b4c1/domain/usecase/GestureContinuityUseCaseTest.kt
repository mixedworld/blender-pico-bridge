package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import org.junit.Test
import org.junit.Assert.*

class GestureContinuityUseCaseTest {
    private val guard = GestureContinuityUseCase()
    private val identity = listOf(0f,0f,0f,1f)
    private fun step(delta: List<Float> = listOf(.001f,0f,0f), scale: Float = 1f) = guard.accept(delta,scale,identity)

    @Test fun addingAndRemovingSecondHandRebasesWithoutLosingSubsequentMotion() {
        guard.pointers(setOf(1)); assertFalse(step()); assertTrue(step())
        guard.pointers(setOf(1,2)); assertFalse(step(listOf(.145f,-.882f,.52f)))
        assertTrue(step(scale=1.02f))
        guard.pointers(setOf(1)); assertFalse(step()); assertTrue(step())
    }
    @Test fun replacingOnePointerWithAnotherAlsoRebases() {
        guard.pointers(setOf(1)); step(); assertTrue(step())
        guard.pointers(setOf(2)); assertFalse(step()); assertTrue(step())
    }
    @Test fun repeatedSamePointerSetDoesNotSuppressNormalInput() {
        guard.pointers(setOf(1,2)); step()
        repeat(20) { guard.pointers(setOf(2,1)); assertTrue(step(scale=1.01f)) }
    }
    @Test fun discontinuityIsRejectedWithoutClampingOrBlockingRecovery() {
        guard.pointers(setOf(1,2)); step()
        assertFalse(step(listOf(.145f,-.882f,.52f)))
        assertFalse(step(scale=Float.NaN)); assertFalse(step(scale=100f))
        assertFalse(step(listOf(Float.POSITIVE_INFINITY,0f,0f)))
        assertTrue(step(scale=.98f))
    }
    @Test fun releaseAndNewGestureCannotReusePreviousDelta() {
        guard.pointers(setOf(1)); step(); assertTrue(step())
        guard.pointers(emptySet()); assertFalse(step()); assertFalse(step())
        guard.pointers(setOf(1)); assertFalse(step()); assertTrue(step())
        guard.rebase(); assertFalse(step()); assertTrue(step())
    }
}
