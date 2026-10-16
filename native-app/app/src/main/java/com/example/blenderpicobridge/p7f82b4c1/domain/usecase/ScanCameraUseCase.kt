package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.data.repository.CameraRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.flow.StateFlow

class ScanCameraUseCase(private val repository: CameraRepository) {
    operator fun invoke(purpose: ScanPurpose,board: CalibrationBoard?,settings: StateFlow<MarkerSettings>?=null) =
        repository.scan(purpose,board,settings)
}
