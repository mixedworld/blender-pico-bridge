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
        return Pairing(pairing.endpoint.trim().trimEnd('/'), pairing.token.trim())
    }
    suspend fun setSourceHidden(pairing: Pairing, hidden: Boolean) = repository.setSourceHidden(validate(pairing), hidden)
    operator fun invoke(pairing: Pairing) = repository.observe(validate(pairing).also(repository::savePairing))
}
