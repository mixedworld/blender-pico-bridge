package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class StabilizeMarkerEstimateUseCaseTest {
    private val estimate=MarkerEstimate(1.2f,.7f,30f,1f,.8f,4)
    @Test fun acceptsOnlyAfterEightConsistentLowErrorFits() {
        val gate=StabilizeMarkerEstimateUseCase()
        repeat(7) { assertFalse(gate.add(estimate)) };assertTrue(gate.add(estimate))
    }
    @Test fun lostDetectionHighErrorAndChangedScaleInvalidateReadiness() {
        val gate=StabilizeMarkerEstimateUseCase();repeat(8) { gate.add(estimate) }
        assertFalse(gate.add(null));assertEquals(0,gate.acceptedSamples)
        repeat(8) { gate.add(estimate) };assertFalse(gate.add(estimate.copy(errorPx=5f)))
        repeat(8) { gate.add(estimate) };assertFalse(gate.add(estimate.copy(arcWidth=1.5f)))
        assertEquals(1,gate.acceptedSamples)
    }
    @Test fun depthJumpNeedsFreshAgreementInsteadOfAveragingBadMeasurements() {
        val gate=StabilizeMarkerEstimateUseCase();repeat(8) { gate.add(estimate) }
        assertFalse(gate.add(estimate.copy(distance=3f)))
        assertFalse(gate.add(estimate.copy(distance=Float.NaN)))
    }
}
