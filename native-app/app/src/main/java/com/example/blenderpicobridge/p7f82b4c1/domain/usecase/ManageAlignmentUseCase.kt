package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.data.repository.AlignmentRepository
import com.example.blenderpicobridge.p7f82b4c1.data.repository.PersistentAlignmentRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.flow.map

/** Keep storage and SDK anchor handles out of the Compose permission/lifecycle effect. */
class ManageAlignmentUseCase(private val repository: AlignmentRepository) {
    private var created: PersistentAlignmentRepository.Created?=null
    val hasSaved get()=repository.hasSaved
    fun read()=repository.read()
    suspend fun save(snapshot: AlignmentSnapshot): SavedAlignment {
        val result=repository.save(snapshot);created=result;return result.saved
    }
    sealed interface Update {
        data class Located(val pose: RigidPose): Update
        data class Status(val message: String): Update
        data object Removed: Update
    }
    fun track(saved: SavedAlignment)=repository.track(saved,created?.takeIf{it.saved==saved}?.anchor.also{created=null}).map {
        when(it) {
            is PersistentAlignmentRepository.Update.Located -> Update.Located(PersistentAlignmentRepository.pose(it.anchor))
            is PersistentAlignmentRepository.Update.Status -> Update.Status(it.message)
            PersistentAlignmentRepository.Update.Removed -> Update.Removed
        }
    }
}
