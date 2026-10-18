package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.data.repository.CameraRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class ScanCameraUseCaseTest {
    @Test fun pairingDoesNotInventAMarkerBoard() = runTest {
        var received: CalibrationBoard?=CalibrationBoard(100,100,emptyList())
        val repo=object: CameraRepository {
            override fun scan(purpose: ScanPurpose,board: CalibrationBoard?,settings: StateFlow<MarkerSettings>?): Flow<ScanUpdate> {
                received=board;return flowOf(ScanUpdate.Status("camera"))
            }
        }
        assertEquals(listOf(ScanUpdate.Status("camera")),ScanCameraUseCase(repo)(ScanPurpose.PAIRING,null).toList())
        assertNull(received)
    }
    @Test fun forwardsLiveSettingsWithoutStartingAnotherCameraSession() = runTest {
        var starts=0
        val settings=MutableStateFlow(MarkerSettings(44f,0f,true))
        val repo=object: CameraRepository {
            override fun scan(purpose: ScanPurpose,board: CalibrationBoard?,settings: StateFlow<MarkerSettings>?): Flow<ScanUpdate> {
                starts++;return requireNotNull(settings).map { ScanUpdate.Status(it.curveDegrees.toString()) }
            }
        }
        val scan=ScanCameraUseCase(repo)(ScanPurpose.MARKER_POSE,CalibrationBoard(1920,1080,emptyList()),settings)
        assertEquals(ScanUpdate.Status("0.0"),scan.first())
        settings.value=settings.value.copy(curveDegrees=45f)
        assertEquals(ScanUpdate.Status("45.0"),scan.first());assertEquals(1,starts)
    }
}
