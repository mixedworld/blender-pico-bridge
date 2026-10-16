package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.MarkerCameraProfile
import kotlin.math.abs

/** A measured profile is valid only for its exact image size and originating SDK camera matrix. */
class SelectMarkerIntrinsicsUseCase {
    operator fun invoke(sdkK: FloatArray,width: Int,height: Int,profile: MarkerCameraProfile?): FloatArray {
        val p=profile ?: return sdkK.copyOf()
        fun valid(k: List<Float>) = k.size==9 && k.all(Float::isFinite) && k[0]>0 && k[4]>0 &&
            abs(k[1])<.0001f && abs(k[3])<.0001f && abs(k[6])<.0001f && abs(k[7])<.0001f && abs(k[8]-1)<.0001f &&
            k[2] in 0f..width.toFloat() && k[5] in 0f..height.toFloat()
        val matches=sdkK.size==9 && valid(p.sdkK) && valid(p.measuredK) &&
            p.width==width && p.height==height && sdkK.indices.all { abs(sdkK[it]-p.sdkK[it])<.001f } &&
            p.samples>=24 && p.rms.isFinite() && p.rms in 0f..1f &&
            p.stdFx.isFinite() && p.stdFx in 0f..p.measuredK[0]*.05f &&
            p.stdFy.isFinite() && p.stdFy in 0f..p.measuredK[4]*.05f &&
            p.measuredK[0]/sdkK[0] in .5f..2f && p.measuredK[4]/sdkK[4] in .5f..2f &&
            abs(p.measuredK[2]-sdkK[2])<.001f && abs(p.measuredK[5]-sdkK[5])<.001f
        return if(matches) p.measuredK.toFloatArray() else sdkK.copyOf()
    }
}
