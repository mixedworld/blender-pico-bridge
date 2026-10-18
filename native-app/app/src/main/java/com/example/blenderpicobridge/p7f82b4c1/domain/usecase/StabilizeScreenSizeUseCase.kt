package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.MeasuredScreen
import kotlin.math.abs
import kotlin.math.max

/** Robust innovation gate before two scalar Kalman filters. Only geometrically valid sizes enter. */
class StabilizeScreenSizeUseCase {
    private val history = ArrayDeque<MeasuredScreen>()
    private var width = 0f
    private var height = 0f
    private var variance = .0004f
    var acceptedSamples = 0
        private set
    var rejectedSamples = 0
        private set
    val requiredSamples = 12
    private fun median(values: List<Float>) = values.sorted()[values.size/2]

    fun add(value: MeasuredScreen): MeasuredScreen? {
        require(value.width.isFinite() && value.height.isFinite() && value.width in .1f..2f && value.height in .1f..2f)
        if (history.size >= 5) {
            val mw = median(history.map { it.width }); val mh = median(history.map { it.height })
            val bw = max(.02f, 4.4478f*median(history.map { abs(it.width-mw) }))
            val bh = max(.02f, 4.4478f*median(history.map { abs(it.height-mh) }))
            if (abs(value.width-mw) > bw || abs(value.height-mh) > bh) {
                rejectedSamples++
                return null
            }
        }
        if (acceptedSamples == 0) { width=value.width; height=value.height }
        // 1mm process deviation and 5mm measurement deviation. Never use smoothing to admit bad depth.
        variance += .000001f
        val gain = variance/(variance+.000025f)
        width += gain*(value.width-width); height += gain*(value.height-height)
        variance *= 1f-gain
        acceptedSamples++
        history.addLast(value)
        if (history.size > 20) history.removeFirst()
        if (history.size < requiredSamples || variance > .000025f) return null
        val mw = median(history.map { it.width }); val mh = median(history.map { it.height })
        if (history.any { abs(it.width-mw) > .015f || abs(it.height-mh) > .015f }) return null
        return MeasuredScreen(width,height)
    }
}
