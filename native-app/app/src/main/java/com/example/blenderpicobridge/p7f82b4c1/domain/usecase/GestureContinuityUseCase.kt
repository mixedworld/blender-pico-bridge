package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

/** A new pointer set changes the gesture baseline, not the object's world pose. */
class GestureContinuityUseCase {
    private var pointers = emptySet<Long>()
    private var rebasing = true

    fun pointers(ids: Set<Long>) {
        if (ids != pointers) { pointers = ids.toSet(); rebase() }
    }

    fun rebase() { rebasing = true }

    fun accept(translationMetres: List<Float>, scale: Float, rotation: List<Float>): Boolean {
        if (rebasing) { rebasing = false; return false }
        if (pointers.isEmpty() || translationMetres.size != 3 || rotation.size != 4) return false
        if (!(translationMetres + rotation + scale).all(Float::isFinite)) return false
        // A single update must not teleport the model or apply a broken scale/rotation.
        // Reject rather than clamp: a centroid discontinuity is not physical motion.
        return translationMetres.sumOf { (it * it).toDouble() } <= .25 * .25 &&
            scale in .5f..2f && rotation.sumOf { (it * it).toDouble() } in .9..1.1
    }
}
