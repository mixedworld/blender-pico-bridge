package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.Pairing
import java.net.URI
import java.net.URLDecoder

/** Decode only our QR format, never open arbitrary QR links or browser intents. */
class ReadPairingQrUseCase {
    operator fun invoke(text: String): Pairing {
        require(text.length in 1..2048) { "Not a Blender bridge QR code" }
        val uri = URI(text)
        require(uri.scheme == "blender-pico" && uri.rawAuthority == "pair" && uri.path.isNullOrEmpty() && uri.fragment == null)
        val fields = uri.rawQuery.orEmpty().split('&').map {
            val field = it.split('=', limit = 2)
            require(field.size == 2)
            URLDecoder.decode(field[0], "UTF-8") to URLDecoder.decode(field[1], "UTF-8")
        }
        require(fields.size == 4 && fields.map { it.first }.toSet() == setOf("v", "endpoint", "token", "session"))
        val data = fields.toMap()
        require(data["v"] == "1") { "Unsupported pairing QR version" }
        val endpoint = URI(data.getValue("endpoint"))
        require(endpoint.scheme == "http" && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null && endpoint.path.isNullOrEmpty())
        require(endpoint.port in 1024..65535)
        // Auto-pairing is restricted to literal private/loopback IPv4 hosts; no DNS lookup.
        val host = endpoint.host.orEmpty().split('.')
        require(host.size == 4 && host.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
        val octets = host.map(String::toInt)
        require(octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31))
        require(data.getValue("token").matches(Regex("[A-Za-z0-9_-]{16,128}")))
        require(data.getValue("session").matches(Regex("[a-f0-9]{24}")))
        return Pairing(endpoint.toString(), data.getValue("token"), data.getValue("session"))
    }
}
