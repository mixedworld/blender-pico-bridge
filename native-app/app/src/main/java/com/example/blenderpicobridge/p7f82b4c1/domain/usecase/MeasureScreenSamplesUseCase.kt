package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.CalibrationBoard
import com.example.blenderpicobridge.p7f82b4c1.domain.model.MeasuredScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Separate depth modes per marker, then require a consistent four-corner rectangle. */
class MeasureScreenSamplesUseCase {
    private fun median(values: List<Float>) = values.sorted()[values.size/2]
    private fun candidates(samples: List<List<Float>>): List<List<Float>> {
        val valid = samples.filter { p -> p.size == 3 && p.all(Float::isFinite) &&
            sqrt(p.sumOf { (it*it).toDouble() }) in .15..5.0 }.sortedBy { abs(it[2]) }
        val groups = mutableListOf<MutableList<List<Float>>>()
        for (point in valid) {
            val group = groups.lastOrNull()
            if (group == null || abs(abs(point[2])-abs(group.first()[2])) > max(.025f,.03f*abs(group.first()[2]))) groups += mutableListOf(point)
            else group += point
        }
        return groups.filter { it.size >= 5 }.sortedByDescending { it.size }.take(3)
            .map { group -> List(3) { axis -> median(group.map { it[axis] }) } }
    }
    operator fun invoke(points: List<List<Float>>, samplesPerMarker: Int, board: CalibrationBoard): MeasuredScreen? {
        require(samplesPerMarker >= 5 && points.size == samplesPerMarker*4)
        val options = points.chunked(samplesPerMarker).map(::candidates)
        if (options.any { it.isEmpty() }) return null
        val estimates = mutableListOf<MeasuredScreen>()
        for (a in options[0]) for (b in options[1]) for (c in options[2]) for (d in options[3]) {
            runCatching { MeasureScreenUseCase()(listOf(a,b,c,d),board) }.getOrNull()?.let(estimates::add)
        }
        if (estimates.isEmpty()) return null
        val w=median(estimates.map { it.width }); val h=median(estimates.map { it.height })
        // Multiple plausible but incompatible depth modes are ambiguity, not calibration.
        if (estimates.any { abs(it.width-w) > .02f || abs(it.height-h) > .02f }) return null
        return MeasuredScreen(w,h)
    }
}
