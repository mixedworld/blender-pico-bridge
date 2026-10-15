package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.content.Context
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class HttpBridgeRepository(context: Context) : BridgeRepository {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "bridge-models").apply { mkdirs() }
    override fun savedPairing() = Pairing(prefs.getString("endpoint", "") ?: "", prefs.getString("token", "") ?: "")
    override fun savePairing(pairing: Pairing) {
        prefs.edit().putString("endpoint", pairing.endpoint).putString("token", pairing.token).apply()
    }
    override fun observe(pairing: Pairing): Flow<BridgeUpdate> = flow {
        var currentRevision: String? = null
        var session = ""
        var sequence = -1L
        var receivedModel = false
        emit(BridgeUpdate.Status("connecting", "Connecting to Blender…"))
        while (currentCoroutineContext().isActive) {
            try {
                val packet = decode(JSONObject(String(request(pairing, "/v1/state", 4 * 1024 * 1024))))
                require(pairing.expectedSession == null || pairing.expectedSession == packet.session) { "Pairing QR expired. Scan the current Blender QR." }
                if (packet.session != session) {
                    session = packet.session
                    sequence = -1
                    currentRevision = null
                }
                if (packet.sequence > sequence) {
                    sequence = packet.sequence
                    val revision = packet.revision
                    if (revision == null) {
                        emit(BridgeUpdate.Status("empty", packet.error ?: "Export a model in Blender"))
                    } else if (revision != currentRevision) {
                        require(revision.matches(Regex("[a-f0-9]{64}"))) { "Invalid model revision" }
                        require(packet.modelPath == "/v1/models/$revision.glb") { "Invalid model path" }
                        emit(BridgeUpdate.Status(if (receivedModel) "refreshing" else "receiving", "Receiving the Blender model…"))
                        val destination = File(directory, "$revision.glb")
                        if (!destination.exists()) {
                            val bytes = request(pairing, packet.modelPath!!, 200 * 1024 * 1024)
                            require(bytes.size >= 20 && bytes.copyOfRange(0, 4).contentEquals("glTF".toByteArray())) { "Not a GLB model" }
                            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            require(hash == revision) { "Incomplete model download" }
                            val temporary = File(directory, "$revision.part")
                            temporary.writeBytes(bytes)
                            check(temporary.renameTo(destination)) { "Unable to publish downloaded model" }
                        }
                        emit(BridgeUpdate.Model(packet, destination))
                        currentRevision = revision
                        receivedModel = true
                    }
                    emit(BridgeUpdate.Frame(packet))
                }
                delay(50)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(BridgeUpdate.Status(if (receivedModel) "stale" else "error", if (error is java.io.IOException) "Blender connection lost · retrying…" else error.message ?: "Connection unavailable"))
                delay(1500)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun setSourceHidden(pairing: Pairing, hidden: Boolean) = withContext(Dispatchers.IO) {
        val connection = URI(pairing.endpoint + "/v1/source-visibility").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { it.write("{\"hidden\":$hidden}".toByteArray()) }
            check(connection.responseCode == 200) { "Blender source visibility was not accepted" }
        } finally { connection.disconnect() }
        Unit
    }

    private fun request(pairing: Pairing, path: String, limit: Int): ByteArray {
        val connection = URI(pairing.endpoint.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        try {
            val code = connection.responseCode
            check(code == 200) { if (code == 401) "Pairing token rejected" else "Blender bridge returned HTTP $code" }
            require(connection.contentLengthLong <= limit) { "Model is too large (limit ${limit / 1048576} MB)" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Bridge response exceeds size limit" }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun viewportDelta(pairing: Pairing, session: String, requestId: String, delta: ViewportDelta): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("session",session).put("requestId",requestId)
            .put("pan",JSONArray(delta.pan)).put("rotation",JSONArray(delta.rotation)).put("zoom",delta.zoom)
        val connection = URI(pairing.endpoint+"/v1/viewport-delta").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=2000;connection.readTimeout=2000
            connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer ${pairing.token}")
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode in listOf(200,202)) { "Viewport command unavailable" }
        } finally { connection.disconnect() }
    }

    private fun decode(json: JSONObject): FramePacket {
        require(json.getInt("protocol") == 1) { "Unsupported Blender bridge protocol" }
        fun JSONArray.values(count: Int): List<Float> {
            require(length() == count)
            return List(count) { getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
        }
        val transforms = json.optJSONArray("transforms") ?: JSONArray()
        require(transforms.length() <= 10000) { "Too many transform nodes" }
        val parts = json.optJSONArray("explodeParts") ?: JSONArray()
        require(parts.length() <= 10000) { "Too many explosion parts" }
        val board = json.optJSONObject("calibrationBoard")?.let { value ->
            require(value.getInt("version") == 1 && value.getString("dictionary") == "DICT_4X4_50")
            val width = value.getInt("width"); val height = value.getInt("height")
            require(width in 100..20000 && height in 100..20000)
            val markers = value.getJSONArray("markers")
            require(markers.length() == 4)
            CalibrationBoard(width, height, List(4) { index ->
                val marker = markers.getJSONObject(index); val rect = marker.getJSONArray("rect")
                require(marker.getInt("id") == index && rect.length() == 4)
                MarkerRect(index, rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3)).also {
                    require(it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0 && it.x+it.width <= width && it.y+it.height <= height)
                }
            })
        }
        val viewport = json.optJSONObject("viewport")?.let { value ->
            val distance = value.getDouble("distance").toFloat()
            require(distance.isFinite() && distance >= 0)
            ViewportFrame(value.getJSONArray("location").values(3),distance.coerceAtLeast(.001f))
        }
        return FramePacket(
            json.getString("session"), json.getLong("sequence"),
            if (json.isNull("revision")) null else json.getString("revision"),
            if (json.isNull("modelPath")) null else json.getString("modelPath"),
            json.optString("modelName", "Blender model"), json.optInt("frame", 1),
            json.optBoolean("playing", false), json.optDouble("timeSeconds", 0.0).toFloat().also { require(it.isFinite()) },
            List(transforms.length()) { index -> transforms.getJSONObject(index).let {
                NodePose(it.getString("name"), it.getJSONArray("p").values(3), it.getJSONArray("q").values(4), it.getJSONArray("s").values(3))
            } }, if (json.isNull("error")) null else json.optString("error"),
            json.optInt("textureCount"), json.optInt("animationCount"),
            List(parts.length()) { index -> parts.getJSONObject(index).let {
                ExplosionPart(it.getString("name"), it.getJSONArray("offset").values(3))
            } },
            json.optJSONArray("viewQuaternion")?.values(4), json.optBoolean("sourceHidden", false),
            board, json.optBoolean("viewportControlEnabled", false),viewport,
        )
    }
}
