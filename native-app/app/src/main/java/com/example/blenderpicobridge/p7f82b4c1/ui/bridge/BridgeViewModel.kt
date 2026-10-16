package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MergeViewportDeltaUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ScanCameraUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MonitorHandlesUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BridgeViewModel(pairing: Pairing, private val connect: ConnectBridgeUseCase,
    initialMode: InspectionMode = InspectionMode.SCALE, initialExplosion: Float = 0f, initialRoom: Boolean = false,
    initialMonitor: Boolean = false, debugReveal: Float? = null, initiallyCollapsed: Boolean = false, hideSourceInitial: Boolean = true,
    private val saveSourceOption: (Boolean) -> Unit = {}, private val camera: ScanCameraUseCase? = null,
    initialMarkerSettings: MarkerSettings = MarkerSettings(),
    private val saveMarkerSettings: (MarkerSettings)->Unit = {}) : ViewModel() {
    private val _state = MutableStateFlow(BridgeUiState(endpoint = pairing.endpoint, token = pairing.token,
        mode = initialMode, explosion = initialExplosion.coerceIn(0f, 1f),markerSettings=initialMarkerSettings,
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
    private var scanJob: Job? = null
    private val markerSettings=MutableStateFlow(initialMarkerSettings)
    private var markerBoard: CalibrationBoard?=null
    private fun closeMarkers() { stopScan();markerBoard=null;_scene.update { it.copy(worldMonitor=null) };_state.update { it.copy(markerPhase=MarkerPhase.NONE,markerFrame=null) } }
    private fun setMarkerSettings(value: MarkerSettings) {
        if (state.value.markerPhase==MarkerPhase.REVIEW) return
        markerSettings.value=value
        _state.update { it.copy(markerSettings=value,markerFrame=null,scanStatus="Settings changed · hold steady for a new estimate") }
        saveMarkerSettings(value)
    }
    private var scannedSession: String? = null
    private var viewportJob: Job? = null
    private var pendingViewport: ViewportDelta? = null
    private var pendingCamera: TrackedViewportCamera? = null
    private fun linkViewport(enabled: Boolean) {
        val previous=viewportJob
        previous?.cancel();pendingViewport=null;pendingCamera=null
        val packet=_scene.value.packet
        val headCamera=state.value.room
        val capability=if(headCamera) packet?.cameraSyncVersion==1 && packet.cameraControlEnabled && state.value.monitor.enabled && state.value.monitor.locked
            else packet?.viewportControlEnabled==true
        val allowed=enabled && capability
        _state.update { it.copy(linkViewport=allowed,note=if(enabled && !allowed)
            (if(headCamera) "Enable Blender viewport control, then align and lock the monitor in Room." else "Enable Allow PICO viewport control in Blender first.") else it.note) }
        _scene.update { it.copy(linkViewport=allowed) }
        if(allowed && sourceHidden && !headCamera) sourceVisibility(false)
        if(allowed && packet!=null) viewportJob=viewModelScope.launch {
            previous?.join() // Release the previous stream before acquiring another.
            val pairing=Pairing(state.value.endpoint,state.value.token)
            var missingFrames=0
            val stream=java.util.UUID.randomUUID().toString()
            var sequence=0L
            try {
                while(isActive) {
                    delay(if(headCamera) 33 else 75)
                    if(headCamera) {
                        val frame=pendingCamera
                        if(frame==null) {
                            check(++missingFrames<60) { "Head tracking unavailable" }
                            continue
                        }
                        missingFrames=0
                        pendingCamera=null
                        connect.viewCamera(pairing,packet.session,requireNotNull(packet.revision),stream,++sequence,frame)
                    } else {
                        val delta=pendingViewport ?: continue
                        pendingViewport=null
                        connect.viewportDelta(pairing,packet.session,java.util.UUID.randomUUID().toString(),delta)
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                _state.update { it.copy(linkViewport=false,note="Viewport link stopped. Check Blender's control setting and reconnect.") }
                _scene.update { it.copy(linkViewport=false) }
            } finally {
                if(headCamera && sequence>0) withContext(NonCancellable) {
                    runCatching { connect.viewCamera(pairing,packet.session,requireNotNull(packet.revision),stream,++sequence,null) }
                }
            }
        }
    }
    private fun stopScan() { scanJob?.cancel(); scanJob = null; _state.update { it.copy(scanPurpose = null) } }
    private fun requestScan(purpose: ScanPurpose) {
        clearHandles();stopScan()
        _state.update { it.copy(scanPurpose = purpose, scanRequest = it.scanRequest+1, measuredScreen = null, scanStatus = "Camera access required") }
    }
    private fun startScan() {
        val purpose = state.value.scanPurpose ?: return
        val repository = camera ?: run { _state.update { it.copy(scanStatus = "Camera unavailable. Use manual setup.") }; return }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                repository(purpose,_scene.value.packet?.board,markerSettings).collect { update ->
                    when (update) {
                        is ScanUpdate.Status -> _state.update { it.copy(scanStatus = update.text) }
                        is ScanUpdate.Paired -> {
                            scannedSession = update.pairing.expectedSession
                            _state.update { it.copy(endpoint = update.pairing.endpoint,token = update.pairing.token,
                                scanPurpose = null,scanStatus = "QR found · connecting…") }
                            open()
                        }
                        is ScanUpdate.Markers -> if (state.value.markerPhase==MarkerPhase.SCAN && update.frame.settings==markerSettings.value) {
                            _state.update { it.copy(markerFrame=update.frame,scanStatus=update.frame.status) }
                        }
                        is ScanUpdate.Measured -> _state.update { it.copy(scanPurpose = null,measuredScreen = update.screen,
                            scanStatus = "Screen measured. Use this size, then confirm placement manually.") }
                    }
                }
            } catch (_: TimeoutCancellationException) { _state.update { it.copy(scanPurpose = null,scanStatus = "Camera/depth unavailable or scan timed out. Retry or use manual setup.") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(scanPurpose = null,scanStatus = "Camera/depth unavailable. Check permissions and retry, or use manual setup.") } }
        }
    }
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
    private val handles=MonitorHandlesUseCase()
    private fun beginHandles() {
        _scene.update { it.copy(monitorHandles=handles.from(state.value.monitor)) }
        _state.update { it.copy(handleError=null) }
    }
    private fun clearHandles() {
        _scene.update { it.copy(monitorHandles=null) };_state.update { it.copy(handleError=null) }
    }
    private fun moveHandle(event: BridgeUiEvent.MonitorHandleMoved) {
        if(!state.value.room || !state.value.monitor.editing || state.value.monitor.locked) return
        val draft=_scene.value.monitorHandles ?: return
        val moved=runCatching { handles.move(draft,event.index,event.delta) }.getOrElse {
            _state.update { s -> s.copy(note=it.message ?: "Handle movement unavailable") };return
        }
        val fit=runCatching { handles.fit(moved,state.value.monitor) }
        val calibration=fit.getOrNull() ?: state.value.monitor
        _state.update { it.copy(monitor=calibration,handleError=fit.exceptionOrNull()?.message) }
        _scene.update { it.copy(monitor=calibration,monitorHandles=moved) }
    }
    private var touchSequence=0
    private fun cancelTouch(request: Int? = null) {
        if (request != null && _scene.value.touchAlignment?.id != request) return
        _scene.update { it.copy(touchAlignment=null) }
        _state.update { it.copy(touchPhase=TouchAlignmentPhase.NONE,touchCount=0,touchedMonitor=null) }
    }
    private fun setupTouch() {
        closeMarkers();cancelTouch();linkViewport(false);clearHandles()
        if (state.value.room && state.value.monitor.editing) _state.update { it.copy(touchPhase=TouchAlignmentPhase.SETUP,touchApplied=false) }
    }
    private fun startTouch() {
        val board=_scene.value.packet?.board
        if (!state.value.room || !state.value.monitor.editing || board == null) {
            _state.update { it.copy(touchPhase=TouchAlignmentPhase.INVALID,touchStatus="Show the four Blender markers, then restart.") };return
        }
        stopScan();touchSequence++
        _state.update { it.copy(touchPhase=TouchAlignmentPhase.CAPTURE,touchCount=0,touchedMonitor=null,touchStatus="Bring the touching fingertip into view.") }
        _scene.update { it.copy(touchAlignment=TouchAlignmentRequest(touchSequence,state.value.touchingHand,board)) }
    }
    fun onEvent(event: BridgeUiEvent) {
        when (event) {
            BridgeUiEvent.OpenMarkerAlignment -> if (state.value.room && state.value.monitor.editing) {
                stopScan();cancelTouch();linkViewport(false);clearHandles()
                _state.update { it.copy(markerPhase=MarkerPhase.SETUP,markerFrame=null,scanStatus="") }
            }
            is BridgeUiEvent.MarkerSideChanged -> if (event.value.isFinite()) setMarkerSettings(markerSettings.value.copy(sideMm=event.value.coerceIn(10f,200f),confirmed=true))
            is BridgeUiEvent.MarkerCurveChanged -> if (event.value.isFinite()) setMarkerSettings(markerSettings.value.copy(curveDegrees=event.value.coerceIn(0f,120f)))
            BridgeUiEvent.StartMarkerScan -> if (state.value.room && state.value.monitor.editing) {
                if (!markerSettings.value.confirmed) _state.update { it.copy(scanStatus="Set the measured black-square side in millimetres first.") }
                else if (_scene.value.packet?.board==null) _state.update { it.copy(scanStatus="Show Blender's calibration markers first.") }
                else {
                    markerBoard=_scene.value.packet?.board
                    _state.update { it.copy(markerPhase=MarkerPhase.SCAN,markerFrame=null) }
                    requestScan(ScanPurpose.MARKER_POSE)
                }
            }
            BridgeUiEvent.PauseMarkerReview -> state.value.markerFrame?.takeIf { it.settings==markerSettings.value && it.stableSamples>=8 && it.estimate!=null && (!it.worldRequired || it.worldAlignment!=null) }?.let {
                stopScan();_state.update { s -> s.copy(markerPhase=MarkerPhase.REVIEW) }
            }
            BridgeUiEvent.UseMarkerGeometry -> if (state.value.markerPhase==MarkerPhase.REVIEW) state.value.markerFrame?.takeIf {
                it.settings==markerSettings.value && it.stableSamples>=8
            }?.let { frame -> frame.estimate?.let { fit ->
                if (runCatching { CurvedScreenUseCase(fit.arcWidth,fit.height,fit.curveDegrees) }.isSuccess) {
                    val world=frame.worldAlignment
                    if(world!=null && markerBoard!=null && state.value.room) {
                        _scene.update { it.copy(worldMonitor=WorldMonitorRequest(state.value.scanRequest,markerBoard!!,frame.settings,fit,world)) }
                    } else if(!frame.worldRequired) {
                        monitor { it.copy(width=fit.arcWidth,height=fit.height,curveDegrees=fit.curveDegrees,enabled=true,editing=true,locked=false) }
                        closeMarkers();beginHandles();_state.update { it.copy(note="Size and curve applied. Place the surface on your monitor, then Lock alignment.") }
                    }
                }
            } }
            is BridgeUiEvent.WorldMonitorApplied -> if(_scene.value.worldMonitor==event.request && state.value.room &&
                markerSettings.value==event.request.settings && _scene.value.packet?.board==event.request.board) {
                monitor { event.calibration };clearHandles()
                closeMarkers();_state.update { it.copy(note="Monitor aligned automatically and world locked. Stage the model to start a take.") }
            }
            BridgeUiEvent.BackFromMarkers -> { closeMarkers();if(state.value.monitor.editing) beginHandles() }
            BridgeUiEvent.OpenTouchAlignment, BridgeUiEvent.RestartTouchAlignment -> setupTouch()
            is BridgeUiEvent.TouchHandChanged -> if (state.value.touchPhase == TouchAlignmentPhase.SETUP) _state.update { it.copy(touchingHand=event.hand) }
            BridgeUiEvent.StartTouchAlignment -> startTouch()
            is BridgeUiEvent.CancelTouchAlignment -> cancelTouch(event.request)
            is BridgeUiEvent.TouchProgress -> if (_scene.value.touchAlignment?.id == event.request) _state.update { it.copy(touchCount=event.count,touchStatus=event.status) }
            is BridgeUiEvent.TouchMeasured -> if (_scene.value.touchAlignment?.id == event.request) {
                _scene.update { it.copy(touchAlignment=null) }
                _state.update { it.copy(touchPhase=TouchAlignmentPhase.REVIEW,touchCount=4,touchedMonitor=event.calibration) }
            }
            is BridgeUiEvent.TouchInvalid -> if (_scene.value.touchAlignment?.id == event.request) {
                _scene.update { it.copy(touchAlignment=null) }
                _state.update { it.copy(touchPhase=TouchAlignmentPhase.INVALID,touchedMonitor=null,touchStatus=event.message) }
            }
            BridgeUiEvent.UseTouchedAlignment -> state.value.touchedMonitor?.let { result ->
                monitor { result.copy(enabled=true,editing=true,locked=false) }
                cancelTouch();beginHandles()
                _state.update { it.copy(touchApplied=true,note="Touch alignment applied. Check the monitor plane, then choose Lock alignment in the alignment controls.") }
            }
            is BridgeUiEvent.LinkViewport -> linkViewport(event.enabled)
            is BridgeUiEvent.TrackedCameraFrame -> if(state.value.linkViewport && state.value.room) { pendingCamera=event.camera }
            is BridgeUiEvent.MoveViewport -> if (state.value.linkViewport) runCatching { MergeViewportDeltaUseCase()(pendingViewport,event.delta) }.getOrNull()?.let { pendingViewport = it }
            is BridgeUiEvent.ScanRequested -> { cancelTouch();requestScan(event.purpose) }
            BridgeUiEvent.ScanAuthorized -> startScan()
            is BridgeUiEvent.ScanFailed -> { stopScan(); _state.update { it.copy(scanStatus = event.message) } }
            BridgeUiEvent.StopScan -> {
                stopScan();cancelTouch()
                if(state.value.markerPhase==MarkerPhase.SCAN || state.value.markerFrame?.worldRequired==true) {
                    _scene.update { it.copy(worldMonitor=null) }
                    _state.update { it.copy(markerPhase=MarkerPhase.SETUP,markerFrame=null) }
                }
            }
            BridgeUiEvent.ManualConnection -> { stopScan(); _state.update { it.copy(manualConnection = true) } }
            BridgeUiEvent.UseMeasuredSize -> state.value.measuredScreen?.let { size ->
                monitor { it.copy(width = size.width,height = size.height,editing = true,locked = false,enabled = true) };beginHandles()
                _state.update { it.copy(note = "Depth size applied. Place the plane and confirm alignment; world pose is not automatic.",measuredScreen = null) }
            }
            is BridgeUiEvent.EndpointChanged -> { scannedSession = null; _state.update { it.copy(endpoint = event.value) } }
            is BridgeUiEvent.TokenChanged -> { scannedSession = null; _state.update { it.copy(token = event.value) } }
            is BridgeUiEvent.SourceHidingChanged -> {
                saveSourceOption(event.enabled)
                _state.update { it.copy(hideSource = event.enabled) }; _scene.update { it.copy(hideSource = event.enabled) }
                if (!event.enabled) sourceVisibility(false)
            }
            is BridgeUiEvent.SourceHidden -> if (sourceHidden != event.hidden) sourceVisibility(event.hidden && state.value.hideSource && state.value.room)
            is BridgeUiEvent.HoverChanged -> { _state.update { it.copy(hover = event.enabled) }; _scene.update { it.copy(hover = event.enabled) } }
            is BridgeUiEvent.CollapseChanged -> { if (event.collapsed) { closeMarkers();cancelTouch() }; _state.update { it.copy(collapsed = event.collapsed) } }
            is BridgeUiEvent.ConnectionPanelChanged -> {
                closeMarkers(); cancelTouch(); _state.update { it.copy(connectionPanel = event.visible, collapsed = false, manualConnection = false) }
                if (event.visible) requestScan(ScanPurpose.PAIRING)
            }
            is BridgeUiEvent.RoomChanged -> {
                if (!event.room) { closeMarkers();cancelTouch();clearHandles() }
                linkViewport(false)
                _state.update { it.copy(room = event.room, autoRoom = false, enteringRoom = false) }
                if (!event.room && sourceHidden) sourceVisibility(false)
            }
            is BridgeUiEvent.RoomPending -> _state.update { it.copy(enteringRoom = event.pending) }
            is BridgeUiEvent.Feedback -> _state.update { it.copy(note = event.message) }
            is BridgeUiEvent.PoseSaved -> _scene.update { if (event.room) it.copy(roomPose = event.pose) else it.copy(previewPose = event.pose) }
            BridgeUiEvent.Fit -> command(SceneCommand.FIT)
            BridgeUiEvent.SourceSize -> command(SceneCommand.SOURCE_SIZE)
            BridgeUiEvent.AlignMonitor -> if (state.value.room) {
                linkViewport(false);closeMarkers();cancelTouch();if(sourceHidden) sourceVisibility(false)
                monitor { it.copy(enabled=true,editing=true,locked=false) };beginHandles()
                _state.update { it.copy(collapsed=false,connectionPanel=false,note="Place cyan handles on Blender’s 3D viewport corners, not the monitor corners; adjust the amber handle for curve.") }
            }
            is BridgeUiEvent.MonitorHandleMoved -> moveHandle(event)
            BridgeUiEvent.LockMonitor -> if(state.value.handleError==null) { closeMarkers();cancelTouch();clearHandles(); monitor { it.copy(editing = false, locked = true) }; _state.update { it.copy(note = "Alignment locked. Grab the staged model through the monitor.") } }
            BridgeUiEvent.LeaveMonitor -> { closeMarkers(); cancelTouch();clearHandles(); monitor { it.copy(editing = false, enabled = it.locked) } }
            BridgeUiEvent.StageTake -> if(state.value.handleError==null) { sourceVisibility(false); command(SceneCommand.STAGE_TAKE) }
            BridgeUiEvent.ResetTake -> if (state.value.monitor.locked) { sourceVisibility(false); command(SceneCommand.RESET_TAKE) } else _state.update { it.copy(note = "Align and lock the monitor first.") }
            is BridgeUiEvent.MonitorWidth -> if (event.value.isFinite()) { monitor { it.copy(width = event.value.coerceIn(.1f, 2f)) };if(state.value.monitor.editing) beginHandles() }
            is BridgeUiEvent.MonitorHeight -> if (event.value.isFinite()) { monitor { it.copy(height = event.value.coerceIn(.1f, 2f)) };if(state.value.monitor.editing) beginHandles() }
            is BridgeUiEvent.MonitorPose -> monitor { it.copy(position = event.position, quaternion = event.quaternion) }
            BridgeUiEvent.Connect -> { stopScan(); open() }
            BridgeUiEvent.Refresh -> { stopScan(); open(refresh = true) }
            BridgeUiEvent.Disconnect -> {
                closeMarkers();cancelTouch();clearHandles()
                linkViewport(false)
                stopScan()
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
        linkViewport(false)
        connection?.cancel()
        val refreshing = refresh && state.value.phase == "live"
        val pairing = Pairing(state.value.endpoint, state.value.token,scannedSession)
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
                        linkViewport(false)
                        _scene.update { it.copy(file = update.file, packet = update.packet) }
                        _state.update { it.copy(partCount = update.packet.explodeParts.size) }
                    }
                    is BridgeUpdate.Frame -> {
                        if (state.value.markerPhase!=MarkerPhase.NONE && markerBoard!=null && update.packet.board!=markerBoard) {
                            stopScan();_state.update { it.copy(markerPhase=MarkerPhase.SETUP,markerFrame=null,scanStatus="Blender marker layout changed. Scan again without resizing its window.") }
                        }
                        val viewportAllowed=if(state.value.room) update.packet.cameraSyncVersion==1 && update.packet.cameraControlEnabled else update.packet.viewportControlEnabled
                        if(!viewportAllowed && state.value.linkViewport) linkViewport(false)
                        _state.update { it.copy(viewportAllowed=viewportAllowed) }
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
