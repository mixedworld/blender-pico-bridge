package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BridgeViewModel(pairing: Pairing, private val connect: ConnectBridgeUseCase,
    initialMode: InspectionMode = InspectionMode.SCALE, initialExplosion: Float = 0f, initialRoom: Boolean = false,
    initialMonitor: Boolean = false, debugReveal: Float? = null, initiallyCollapsed: Boolean = false, hideSourceInitial: Boolean = true,
    private val saveSourceOption: (Boolean) -> Unit = {}) : ViewModel() {
    private val _state = MutableStateFlow(BridgeUiState(endpoint = pairing.endpoint, token = pairing.token,
        mode = initialMode, explosion = initialExplosion.coerceIn(0f, 1f),
        autoRoom = initialRoom, collapsed = initiallyCollapsed, hideSource = hideSourceInitial,
        monitor = MonitorCalibration(enabled = initialMonitor, locked = initialMonitor)))
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()
    private val _scene = MutableStateFlow(SceneFeed(mode = initialMode, explosion = initialExplosion.coerceIn(0f, 1f),
        monitor = MonitorCalibration(enabled = initialMonitor, locked = initialMonitor), hideSource = hideSourceInitial,
        command = if (initialMonitor) SceneCommand.STAGE_TAKE else SceneCommand.NONE,
        commandSequence = if (initialMonitor) 1 else 0, debugReveal = debugReveal))
    val scene: StateFlow<SceneFeed> = _scene.asStateFlow()
    private var connection: Job? = null
    private var visibilityJob: Job? = null
    private var sourceHidden = false
    private fun sourceVisibility(hidden: Boolean) {
        sourceHidden = hidden
        visibilityJob?.cancel()
        val pairing = Pairing(state.value.endpoint, state.value.token)
        visibilityJob = viewModelScope.launch {
            do {
                try { connect.setSourceHidden(pairing, hidden) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { _state.update { it.copy(note = "Source visibility unavailable; Blender restores automatically after 8 seconds.") } }
                if (hidden) delay(2500)
            } while (hidden && isActive)
        }
    }
    private var loadedRevision: String? = null

    private fun command(value: SceneCommand) { _scene.update { it.copy(command = value, commandSequence = it.commandSequence + 1) } }
    private fun monitor(change: (MonitorCalibration) -> MonitorCalibration) {
        val next = change(_state.value.monitor)
        _state.update { it.copy(monitor = next) }
        _scene.update { it.copy(monitor = next) }
    }
    fun onEvent(event: BridgeUiEvent) {
        when (event) {
            is BridgeUiEvent.EndpointChanged -> _state.update { it.copy(endpoint = event.value) }
            is BridgeUiEvent.TokenChanged -> _state.update { it.copy(token = event.value) }
            is BridgeUiEvent.SourceHidingChanged -> {
                saveSourceOption(event.enabled)
                _state.update { it.copy(hideSource = event.enabled) }; _scene.update { it.copy(hideSource = event.enabled) }
                if (!event.enabled) sourceVisibility(false)
            }
            is BridgeUiEvent.SourceHidden -> if (sourceHidden != event.hidden) sourceVisibility(event.hidden && state.value.hideSource && state.value.room)
            is BridgeUiEvent.HoverChanged -> { _state.update { it.copy(hover = event.enabled) }; _scene.update { it.copy(hover = event.enabled) } }
            is BridgeUiEvent.CollapseChanged -> _state.update { it.copy(collapsed = event.collapsed) }
            is BridgeUiEvent.ConnectionPanelChanged -> _state.update { it.copy(connectionPanel = event.visible, collapsed = false) }
            is BridgeUiEvent.RoomChanged -> {
                _state.update { it.copy(room = event.room, autoRoom = false, enteringRoom = false) }
                if (!event.room && sourceHidden) sourceVisibility(false)
            }
            is BridgeUiEvent.RoomPending -> _state.update { it.copy(enteringRoom = event.pending) }
            is BridgeUiEvent.Feedback -> _state.update { it.copy(note = event.message) }
            is BridgeUiEvent.PoseSaved -> _scene.update { if (event.room) it.copy(roomPose = event.pose) else it.copy(previewPose = event.pose) }
            BridgeUiEvent.Fit -> command(SceneCommand.FIT)
            BridgeUiEvent.SourceSize -> command(SceneCommand.SOURCE_SIZE)
            BridgeUiEvent.AlignMonitor -> if (state.value.room) { monitor { it.copy(enabled = true, editing = true, locked = false) }; _state.update { it.copy(collapsed = false, connectionPanel = false) } }
            BridgeUiEvent.LockMonitor -> { monitor { it.copy(editing = false, locked = true) }; _state.update { it.copy(note = "Alignment locked. Grab the staged model through the monitor.") } }
            BridgeUiEvent.LeaveMonitor -> monitor { it.copy(editing = false, enabled = it.locked) }
            BridgeUiEvent.StageTake -> { sourceVisibility(false); command(SceneCommand.STAGE_TAKE) }
            BridgeUiEvent.ResetTake -> if (state.value.monitor.locked) { sourceVisibility(false); command(SceneCommand.RESET_TAKE) } else _state.update { it.copy(note = "Align and lock the monitor first.") }
            is BridgeUiEvent.MonitorWidth -> if (event.value.isFinite()) monitor { it.copy(width = event.value.coerceIn(.1f, 2f)) }
            is BridgeUiEvent.MonitorHeight -> if (event.value.isFinite()) monitor { it.copy(height = event.value.coerceIn(.1f, 2f)) }
            is BridgeUiEvent.MonitorPose -> monitor { it.copy(position = event.position, quaternion = event.quaternion) }
            BridgeUiEvent.Connect -> open()
            BridgeUiEvent.Refresh -> open(refresh = true)
            BridgeUiEvent.Disconnect -> {
                if (sourceHidden) sourceVisibility(false)
                connection?.cancel()
                connection = null
                _state.update { it.copy(phase = "disconnected", status = "Disconnected", playing = false,
                    note = "Last complete model retained. Connect for current Blender data.") }
            }
            BridgeUiEvent.Reset -> {
                if (sourceHidden) sourceVisibility(false)
                _scene.update { it.copy(reset = it.reset + 1, explosion = 0f) }
                _state.update { it.copy(scale = 1f, explosion = 0f) }
            }
            is BridgeUiEvent.ModelReady -> {
                loadedRevision = event.revision
                _state.update { it.copy(phase = "live", hasModel = true, status = "Live from Blender", fitScale = event.fitScale,
                    note = inspectionHint(it.mode)) }
            }
            is BridgeUiEvent.FitChanged -> _state.update { it.copy(fitScale = event.scale) }
            is BridgeUiEvent.SceneError -> _state.update { it.copy(phase = if (it.hasModel) "stale" else "error", status = event.message, note = event.message) }
            is BridgeUiEvent.InspectionChanged -> _state.update { it.copy(scale = event.scale, note = event.note ?: it.note) }
            is BridgeUiEvent.ModeChanged -> {
                _state.update { it.copy(mode = event.mode, note = inspectionHint(event.mode)) }
                _scene.update { it.copy(mode = event.mode) }
            }
            is BridgeUiEvent.ExplosionChanged -> if (event.amount.isFinite()) {
                val amount = event.amount.coerceIn(0f, 1f)
                _state.update { it.copy(explosion = amount) }
                _scene.update { it.copy(explosion = amount) }
            }
        }
    }

    private fun open(refresh: Boolean = false) {
        connection?.cancel()
        val refreshing = refresh && state.value.phase == "live"
        val pairing = Pairing(state.value.endpoint, state.value.token)
        val flow = try { connect(pairing) } catch (error: IllegalArgumentException) {
            _state.update { it.copy(phase = "error", status = error.message ?: "Pairing details required") }
            return
        }
        _state.update { it.copy(phase = if (refreshing && it.hasModel) "refreshing" else if (it.hasModel) "reconnecting" else "connecting", status = "Connecting to Blender…") }
        connection = viewModelScope.launch {
            flow.collect { update ->
                when (update) {
                    is BridgeUpdate.Status -> _state.update { it.copy(phase = if (refreshing && it.hasModel && update.phase in setOf("connecting", "receiving")) "refreshing" else if (it.hasModel && update.phase == "connecting") "reconnecting" else update.phase, status = update.message,
                        note = if (it.hasModel && update.phase in setOf("stale", "error")) "Last complete model retained. Refresh to reconnect." else it.note) }
                    is BridgeUpdate.Model -> {
                        _scene.update { it.copy(file = update.file, packet = update.packet) }
                        _state.update { it.copy(partCount = update.packet.explodeParts.size) }
                    }
                    is BridgeUpdate.Frame -> {
                        if (_scene.value.file?.name == "${update.packet.revision}.glb") {
                            _scene.update { it.copy(packet = update.packet) }
                        }
                        if (update.packet.sequence % 4L == 0L || update.packet.playing != state.value.playing) {
                            _state.update { it.copy(frame = update.packet.frame, playing = update.packet.playing,
                                modelName = update.packet.modelName, sourceHidden = update.packet.sourceHidden, revision = update.packet.revision?.take(8) ?: "—",
                                phase = if (loadedRevision == update.packet.revision) "live" else it.phase,
                                status = if (loadedRevision == update.packet.revision) "Live from Blender · ${if (update.packet.playing) "Playing" else "Paused"}" else it.status) }
                        }
                    }
                }
            }
        }
    }
    private fun inspectionHint(mode: InspectionMode) = if (mode == InspectionMode.EXPLODE)
        "Pinch with both hands, then spread to separate parts. Bring hands together to assemble."
        else "Gaze + pinch to move. Use two hands to rotate and scale."
}
