package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionPose

/** Room edits keep the existing source-to-world mapping, even when new bounds move the centre. */
class ModelReplacementFitUseCase {
    data class Fit(val scale: Float, val offset: List<Float>)

    operator fun invoke(room: Boolean, previous: InspectionPose?, sourceCentre: List<Float>,
        sourceRadius: Float, availableRadius: Float, inspectionScale: Float): Fit {
        if (room && previous != null) return Fit(previous.fitScale, previous.fitPosition.toList())
        val scale = if (room) 1f else availableRadius / (sourceRadius * 1.2f * inspectionScale)
        return Fit(scale, sourceCentre.map { -it * scale })
    }
}
