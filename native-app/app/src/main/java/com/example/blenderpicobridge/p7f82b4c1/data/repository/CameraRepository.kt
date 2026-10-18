package com.example.blenderpicobridge.p7f82b4c1.data.repository

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CameraRepository {
    fun scan(purpose: ScanPurpose,board: CalibrationBoard?,settings: StateFlow<MarkerSettings>?=null): Flow<ScanUpdate>
}
