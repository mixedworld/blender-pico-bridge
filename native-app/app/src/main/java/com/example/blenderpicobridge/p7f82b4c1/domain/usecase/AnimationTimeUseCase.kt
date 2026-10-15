package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

class AnimationTimeUseCase {
    operator fun invoke(seconds: Float, duration: Float): Float {
        require(seconds.isFinite() && duration.isFinite() && duration >= 0)
        return seconds.coerceIn(0f, duration)
    }
}
