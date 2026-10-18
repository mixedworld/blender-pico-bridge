package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.ViewportDelta
import kotlin.math.sqrt

/** Aggregate incremental gestures while the preceding HTTP command is in flight. */
class MergeViewportDeltaUseCase {
    operator fun invoke(previous: ViewportDelta?, next: ViewportDelta): ViewportDelta {
        val old = previous ?: ViewportDelta()
        require(next.rotation.size == 4 && next.rotation.all(Float::isFinite))
        val (x,y,z,w) = next.rotation; val (a,b,c,d) = old.rotation
        val product = listOf(w*a+x*d+y*c-z*b,w*b-x*c+y*d+z*a,w*c+x*b-y*a+z*d,w*d-x*a-y*b-z*c)
        val norm = sqrt(product.sumOf { (it*it).toDouble() }).toFloat()
        require(norm > .001f && norm.isFinite() && next.pan.size == 3 && next.pan.all(Float::isFinite) && next.zoom.isFinite() && next.zoom > 0)
        return ViewportDelta(List(3) { (old.pan[it]+next.pan[it]).coerceIn(-1f,1f) },product.map { it/norm },(old.zoom*next.zoom).coerceIn(.25f,4f))
    }
}
