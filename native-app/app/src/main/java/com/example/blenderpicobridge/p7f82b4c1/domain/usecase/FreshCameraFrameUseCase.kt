package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

/** Per scan baseline. Camera timestamp units are opaque; only progression is used. */
class FreshCameraFrameUseCase(private val warmupMs: Long = 600) {
    private var baselineHash: Int? = null
    private var previousStamp: Long? = null
    private var startedAt = 0L
    private var advances = 0
    fun accept(timestamp: Long, fingerprint: Int, elapsedMs: Long): Boolean {
        if (baselineHash == null) {
            baselineHash=fingerprint;previousStamp=timestamp;startedAt=elapsedMs
            return false // A newly created session can expose the previous scan's frame.
        }
        if (timestamp <= (previousStamp ?: timestamp)) return false
        previousStamp=timestamp;advances++
        return advances>=3 && fingerprint!=baselineHash && elapsedMs-startedAt>=warmupMs
    }
}
