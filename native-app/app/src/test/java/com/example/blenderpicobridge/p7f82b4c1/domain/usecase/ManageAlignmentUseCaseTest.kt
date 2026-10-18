package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.data.repository.AlignmentRepository
import com.example.blenderpicobridge.p7f82b4c1.data.repository.PersistentAlignmentRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.pico.spatial.sense.world.WorldAnchor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManageAlignmentUseCaseTest {
    private val saved=SavedAlignment("fixture",AlignmentSnapshot(MonitorCalibration(),MonitorHandlesUseCase().from(MonitorCalibration())))
    private fun repository(value: SavedAlignment?)=object: AlignmentRepository {
        override val hasSaved=value!=null
        override fun read()=value
        override suspend fun save(global: AlignmentSnapshot): PersistentAlignmentRepository.Created=error("Not used in read/observe tests")
        override fun track(saved: SavedAlignment,initial: WorldAnchor?)=flowOf(
            PersistentAlignmentRepository.Update.Status("Locating"),PersistentAlignmentRepository.Update.Removed)
    }
    @Test fun readingMissingOrSavedAlignmentPreservesStorageMeaning() {
        val missing=ManageAlignmentUseCase(repository(null));assertFalse(missing.hasSaved);assertNull(missing.read())
        val stored=ManageAlignmentUseCase(repository(saved));assertTrue(stored.hasSaved);assertEquals(saved,stored.read())
    }
    @Test fun trackingExposesStatusAndRemovalWithoutLeakingSdkHandles()=runTest {
        assertEquals(listOf(ManageAlignmentUseCase.Update.Status("Locating"),ManageAlignmentUseCase.Update.Removed),ManageAlignmentUseCase(repository(saved)).track(saved).toList())
    }
}
