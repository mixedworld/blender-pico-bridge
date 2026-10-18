package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import org.junit.Assert.*
import org.junit.Test
class FreshCameraFrameUseCaseTest {
    @Test fun cachedFirstFrameAndRepeatedTimestampCannotPair() {
        val fresh=FreshCameraFrameUseCase()
        assertFalse(fresh.accept(100,123,0))
        repeat(20){assertFalse(fresh.accept(100,456,1000L+it))}
        assertFalse(fresh.accept(101,123,2000))
        assertFalse(fresh.accept(102,123,2100))
        assertFalse(fresh.accept(103,123,2200))
        assertTrue(fresh.accept(104,456,2300))
    }
    @Test fun scanWarmupAndNewBaselineCannotReusePreviousResult() {
        val first=FreshCameraFrameUseCase()
        assertFalse(first.accept(1,42,0));assertFalse(first.accept(2,43,100))
        assertFalse(first.accept(3,44,200));assertFalse(first.accept(4,45,300))
        assertTrue(first.accept(5,46,700))
        val next=FreshCameraFrameUseCase()
        assertFalse(next.accept(5,46,800));assertFalse(next.accept(4,99,2000))
        assertFalse(next.accept(6,46,2100));assertFalse(next.accept(7,46,2200))
        assertFalse(next.accept(8,46,2300))
        assertTrue(next.accept(9,47,2400))
    }
}
