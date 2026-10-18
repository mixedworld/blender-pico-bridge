package com.example.blenderpicobridge.p7f82b4c1.data.repository

import com.example.blenderpicobridge.p7f82b4c1.domain.model.AlignmentSnapshot
import com.example.blenderpicobridge.p7f82b4c1.domain.model.SavedAlignment
import com.pico.spatial.sense.world.WorldAnchor
import kotlinx.coroutines.flow.Flow

interface AlignmentRepository {
    val hasSaved: Boolean
    fun read(): SavedAlignment?
    suspend fun save(global: AlignmentSnapshot): PersistentAlignmentRepository.Created
    fun track(saved: SavedAlignment,initial: WorldAnchor? = null): Flow<PersistentAlignmentRepository.Update>
}
