package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import android.view.Surface
import com.example.blenderpicobridge.p7f82b4c1.data.repository.RenderVideoRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

class ReceiveRenderVideoUseCase(private val repository: RenderVideoRepository) {
    operator fun invoke(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit): AutoCloseable =
        repository.open(surface,pairing,aspect,status)
}
