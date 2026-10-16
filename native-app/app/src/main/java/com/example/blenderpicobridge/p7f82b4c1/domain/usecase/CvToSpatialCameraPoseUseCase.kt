package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.CameraSpacePose

/** Camera-relative only. Does not invent a camera-to-world transform or compensate exposure timing. */
class CvToSpatialCameraPoseUseCase {
    operator fun invoke(pose: CameraSpacePose): FloatArray {
        require(pose.rotation.size==9 && pose.translation.size==3)
        require(pose.rotation.all(Float::isFinite) && pose.translation.all(Float::isFinite))
        // Left multiplication by diag(1,-1,-1): Spatial camera +X right, +Y up, -Z forward.
        // Flip two axes (a proper rotation), retaining the object's own local basis.
        return FloatArray(16).also { matrix ->
            for(row in 0..2) {
                val sign=if(row==0) 1f else -1f
                for(column in 0..2) matrix[row*4+column]=sign*pose.rotation[row*3+column]
                matrix[row*4+3]=sign*pose.translation[row]
            }
            matrix[15]=1f
        }
    }
}
