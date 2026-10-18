package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.data.repository.BridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import java.net.URI

class ConnectBridgeUseCase(private val repository: BridgeRepository) {
    fun validate(pairing: Pairing): Pairing {
        val uri = runCatching { URI(pairing.endpoint.trim()) }.getOrElse { throw IllegalArgumentException("Enter the Blender bridge HTTP URL") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) { "Use http://Mac-IP:8766" }
        require(pairing.token.isNotBlank() && !pairing.token.contains('\n') && !pairing.token.contains('\r')) { "Enter the pairing token from Blender" }
        return pairing.copy(endpoint = pairing.endpoint.trim().trimEnd('/'), token = pairing.token.trim())
    }
    suspend fun setSourceHidden(pairing: Pairing, hidden: Boolean) = repository.setSourceHidden(validate(pairing), hidden)
    suspend fun viewportDelta(pairing: Pairing, session: String, requestId: String, delta: ViewportDelta) = repository.viewportDelta(validate(pairing),session,requestId,delta)
    suspend fun viewCamera(pairing: Pairing,session: String,revision: String,stream: String,sequence: Long,camera: TrackedViewportCamera?) = repository.viewCamera(validate(pairing),session,revision,stream,sequence,camera)
    operator fun invoke(pairing: Pairing) = repository.observe(validate(pairing).also(repository::savePairing))
}
