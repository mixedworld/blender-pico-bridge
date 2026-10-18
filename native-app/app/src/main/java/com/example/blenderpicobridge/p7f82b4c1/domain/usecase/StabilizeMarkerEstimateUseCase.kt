package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.abs

class StabilizeMarkerEstimateUseCase {
    private var previous: MarkerEstimate?=null
    var acceptedSamples=0; private set
    fun reset() { previous=null;acceptedSamples=0 }
    fun add(value: MarkerEstimate?): Boolean {
        if (value==null || !value.distance.isFinite() || value.distance !in .15f..5f ||
            !value.errorPx.isFinite() || value.errorPx > 3f) { reset();return false }
        val old=previous
        val agrees=old!=null && abs(value.distance-old.distance) < maxOf(.015f,old.distance*.02f) &&
            old.curveDegrees==value.curveDegrees && abs(old.arcWidth-value.arcWidth)<.001f &&
            abs(old.height-value.height)<.001f
        acceptedSamples=if (agrees) acceptedSamples+1 else 1
        previous=value
        return acceptedSamples>=8
    }
}
