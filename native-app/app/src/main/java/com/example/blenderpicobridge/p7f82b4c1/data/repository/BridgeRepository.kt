package com.example.blenderpicobridge.p7f82b4c1.data.repository

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.flow.Flow

interface BridgeRepository {
    fun savedPairing(): Pairing
    fun savePairing(pairing: Pairing)
    suspend fun setSourceHidden(pairing: Pairing, hidden: Boolean) { throw UnsupportedOperationException("Source visibility unavailable") }
    fun observe(pairing: Pairing): Flow<BridgeUpdate>
}
