package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import org.junit.Assert.*
import org.junit.Test
class AnimationTimeUseCaseTest {
    private val seek = AnimationTimeUseCase()
    @Test fun scrubClampsToClipWithoutWrappingOrAdvancingWhilePaused() {
        assertEquals(2.5f, seek(2.5f, 5f), 0f)
        assertEquals(5f, seek(7f, 5f), 0f)
        assertEquals(0f, seek(-1f, 5f), 0f)
    }
    @Test fun rejectsNonFiniteNetworkTimesAndNegativeDurations() {
        assertThrows(IllegalArgumentException::class.java) { seek(Float.NaN, 5f) }
        assertThrows(IllegalArgumentException::class.java) { seek(1f, -1f) }
        assertThrows(IllegalArgumentException::class.java) { seek(1f, Float.POSITIVE_INFINITY) }
    }
}
