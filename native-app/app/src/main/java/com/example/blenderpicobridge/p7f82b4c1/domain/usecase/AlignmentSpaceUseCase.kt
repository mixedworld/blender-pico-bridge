package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

/** Change a complete alignment's rigid reference. Metres, curvature and model fit stay unchanged. */
class AlignmentSpaceUseCase {
    fun point(point: List<Float>, reference: RigidPose): List<Float> {
        require(point.size == 3 && point.all(Float::isFinite))
        return List(3) { row -> (reference.t[row] + (0..2).sumOf { k -> reference.r[row*3+k]*point[k] }).toFloat() }
    }
    fun rotation(quaternion: List<Float>, reference: RigidPose): List<Float> =
        (reference * RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),quaternion.map(Float::toDouble))).quaternion().map(Double::toFloat)
    fun inspection(pose: InspectionPose, reference: RigidPose) = pose.copy(
        position=point(pose.position,reference),quaternion=rotation(pose.quaternion,reference))
    operator fun invoke(snapshot: AlignmentSnapshot, reference: RigidPose) = snapshot.copy(
        monitor=snapshot.monitor.copy(position=point(snapshot.monitor.position,reference),
            quaternion=rotation(snapshot.monitor.quaternion,reference)),
        handles=snapshot.handles.copy(corners=snapshot.handles.corners.map { point(it,reference) }),
        stagedPose=snapshot.stagedPose?.let { inspection(it,reference) })
}
