package com.example.blenderpicobridge.p7f82b4c1
import com.example.blenderpicobridge.p7f82b4c1.data.repository.BridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlinx.coroutines.flow.*
class FakeBridgeRepository : BridgeRepository {
    var pairing = Pairing("http://192.168.1.2:8766", "test-token")
    val visibility = mutableListOf<Boolean>()
    val viewportCommands = mutableListOf<ViewportDelta>()
    val cameraCommands = mutableListOf<Pair<Long,TrackedViewportCamera?>>()
    var cameraFailure: Exception? = null
    val cameraRevisions = mutableListOf<String>()
    override suspend fun viewCamera(pairing: Pairing,session: String,revision: String,stream: String,sequence: Long,camera: TrackedViewportCamera?) {
        if (camera != null) cameraFailure?.let { throw it }
        cameraCommands.add(sequence to camera)
        cameraRevisions.add(revision)
    }
    override suspend fun viewportDelta(pairing: Pairing, session: String, requestId: String, delta: ViewportDelta) { viewportCommands.add(delta) }
    override suspend fun setSourceHidden(pairing: Pairing, hidden: Boolean) { visibility.add(hidden) }
    val events = MutableSharedFlow<BridgeUpdate>(extraBufferCapacity = 16)
    override fun savedPairing() = pairing
    override fun savePairing(pairing: Pairing) { this.pairing = pairing }
    override fun observe(pairing: Pairing): Flow<BridgeUpdate> = events
}
