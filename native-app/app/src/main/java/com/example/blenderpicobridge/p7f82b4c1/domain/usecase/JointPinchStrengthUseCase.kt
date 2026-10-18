package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import kotlin.math.sqrt

/** App gesture from tracked thumb/index centres; independent of the OS pinch readiness flag. */
class JointPinchStrengthUseCase {
    operator fun invoke(thumb: List<Float>?,index: List<Float>?): Float? {
        if (thumb?.size!=3 || index?.size!=3 || !thumb.all(Float::isFinite) || !index.all(Float::isFinite)) return null
        val distance=sqrt((0..2).sumOf { axis -> val d=thumb[axis]-index[axis];(d*d).toDouble() }).toFloat()
        if (distance > .25f) return null
        // 20 mm = closed, 45 mm = open. Capture applies release/press hysteresis.
        return ((.045f-distance)/.025f).coerceIn(0f,1f)
    }
}
