package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.ln

/** An inspection layer: always derive from the incoming authored pose, never the last offset pose. */
class ExplosionUseCase {
    fun fromSpan(current: Float, incrementalScale: Float): Float =
        if (!incrementalScale.isFinite() || incrementalScale <= 0f) current
        else (current + 1.4f * ln(incrementalScale)).coerceIn(0f, 1f)

    fun poses(packet: FramePacket, amount: Float): List<NodePose> {
        val offsets = packet.explodeParts.associateBy { it.name }
        return packet.poses.map { pose ->
            val offset = offsets[pose.name]?.offset
            if (offset == null || amount == 0f) pose
            else pose.copy(position = List(3) { pose.position[it] + offset[it] * amount.coerceIn(0f, 1f) })
        }
    }
}
