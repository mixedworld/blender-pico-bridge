package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import kotlin.math.sqrt

enum class ContactReadiness { TRACKING_NEEDED, HOLD_STILL, RELEASE_PINCH, READY, CAPTURED }
data class ContactUpdate(val readiness: ContactReadiness, val point: List<Float>? = null)

/** Local arrival times only: the public tracking snapshot has no exposure timestamp property. */
class StableTouchCaptureUseCase {
    private data class Sample(val time: Long,val point: List<Float>)
    private val samples=ArrayDeque<Sample>()
    private var previousTime: Long?=null
    private var previousPinch=1f
    private var armed=false

    fun reset() { samples.clear();previousTime=null;previousPinch=1f;armed=false }
    fun update(point: List<Float>?,pinch: Float?,pinchReady: Boolean,nowMillis: Long): ContactUpdate {
        val prior=previousTime
        if (prior != null && (nowMillis <= prior || nowMillis-prior > 150)) reset()
        previousTime=nowMillis
        if (point == null || point.size != 3 || !point.all(Float::isFinite) || !pinchReady || pinch == null || !pinch.isFinite()) {
            samples.clear();armed=false;previousPinch=1f
            return ContactUpdate(ContactReadiness.TRACKING_NEEDED)
        }
        val rising=pinch >= .85f && previousPinch < .85f
        previousPinch=pinch
        if (pinch < .25f) armed=true
        samples.addLast(Sample(nowMillis,point.toList()))
        while (samples.isNotEmpty() && nowMillis-samples.first().time > 350) samples.removeFirst()
        val center=List(3) { axis -> samples.map { it.point[axis] }.sorted()[samples.size/2] }
        val stable=samples.size >= 8 && nowMillis-samples.first().time >= 200 && samples.all { sample ->
            sqrt(sample.point.indices.sumOf { axis -> val d=sample.point[axis]-center[axis];(d*d).toDouble() }) <= .008
        }
        if (rising) {
            val accept=armed && stable
            armed=false
            if (accept) { samples.clear();return ContactUpdate(ContactReadiness.CAPTURED,center) }
        }
        return ContactUpdate(when { !armed -> ContactReadiness.RELEASE_PINCH; !stable -> ContactReadiness.HOLD_STILL; else -> ContactReadiness.READY })
    }
}
