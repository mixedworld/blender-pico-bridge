package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.util.Log
import android.os.SystemClock
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildMonitorCameraUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildRenderBillboardUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionMode
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ExplosionUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.AnimationTimeUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.GestureContinuityUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ModelReplacementFitUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.animation.AnimationPlayConfig
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.gesture.SpatialTransformValue
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/** Main-thread scene owner. Authored transforms never overwrite the inspection carrier. */
class BridgeSceneRuntime(private val event: (BridgeUiEvent) -> Unit, private val room: Boolean = false,
    private val receiveRenderVideo: com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ReceiveRenderVideoUseCase? = null) {
    val placementOwner = Entity()
    val inspectionCarrier = Entity()
    private var fittedSceneRoot = Entity()
    private var model: Entity? = null
    private val modelVisibility = NativeModelVisibility()
    private var content: SpatialViewContent? = null
    private var shape: ShapeResource? = null
    private var physics: PhysicsMaterialResource? = null
    private val controllers = mutableListOf<AnimationPlaybackController>()
    private val nodes = mutableMapOf<String, Entity>()
    private val animationTime = AnimationTimeUseCase()
    private val explosionLayer = ExplosionUseCase()
    private val gestureContinuity = GestureContinuityUseCase()
    private var mode = InspectionMode.SCALE
    private var explosion = 0f
    private var latest: FramePacket? = null
    private var reservedRadius = 0f
    private val grabBounds = Entity()
    private var monitor: MonitorThreshold? = null
    private var renderWindow: RenderWindowScene?=null
    private var renderState=RenderWindowState()
    private var headPosition: Vector3?=null
    private var headRotation: Quat?=null
    private var renderProjectionValid=true
    private var monitorHandles: MonitorHandles? = null
    private var manualHandles=false
    private var modelPickingSuspended=false
    private var calibration = MonitorCalibration()
    private var commandSequence = 0
    private var hover = false
    private var hideSource = true
    private var sourceHidden = false
    private var restored = false
    private var viewerPlaced = false
    private var homePosition = Vector3(0f, 1.2f, -1.5f)
    private var homeRotation = Quat()
    private var stagedPose: InspectionPose? = null
    private var anchorReference: RigidPose? = null
    private var pendingAnchoredPose: Pair<String?,InspectionPose?>? = null
    private var collisionKey = emptyList<Float>()
    var tools: Entity? = null
    var palm: Entity? = null
    private var toolsPicking: ToolsLauncherPicking?=null
    private var toolsReset=-1
    private var recenterTools=false
    private var headAt=0L
    fun attachTools(entity: Entity) {
        tools=entity;placementOwner.addChild(entity)
        toolsPicking=ToolsLauncherPicking(entity)
    }
    fun measureTools() = toolsPicking?.measure()
    fun attachPalm(entity: Entity) {palm=entity;placementOwner.addChild(entity);entity.enabled=false}
    fun toolsHead(): Pair<List<Float>,List<Float>>? {
        if(android.os.SystemClock.elapsedRealtime()-headAt>300)return null
        val head=headPosition ?: return null
        val forward=headRotation?.rotateVector(Vector3(0f,0f,-1f)) ?: return null
        return head.values() to forward.values()
    }
    fun palmTools(position: List<Float>?) {
        val entity=palm ?: return
        val head=toolsHead()?.first
        if(position==null || head==null){entity.enabled=false;return}
        val q=BuildRenderBillboardUseCase().facing(head,position) ?: run {entity.enabled=false;return}
        entity.components[TransformComponent::class.java]!!.apply {
            this.position=placementOwner.convertPositionFrom(position.vector(),null)
            quaternion=placementOwner.convertRotationFrom(q.quat(),null)
        }
        entity.enabled=true
    }
    private fun placeTools(position: Vector3,rotation: Quat) {
        tools?.components?.get(TransformComponent::class.java)?.apply {
            this.position=placementOwner.convertPositionFrom(position+rotation.rotateVector(Vector3(.45f,-.25f,-.85f)),null)
            quaternion=placementOwner.convertRotationFrom(rotation,null)
        }
        recenterTools=false
    }
    private var revision: String? = null
    private var sequence = -1L
    private var half = Vector3.ZERO
    private var resetCounter = 0
    private var inspectionScale = 1f
    private var linkedViewport = false
    private var linkedSnapshot: InspectionPose? = null
    private var linkedDistance = 1f
    private var linkedRevision: String? = null
    private var lastCameraFrameAt=0L
    private var renderFollowingValid=true
    private val transform get() = inspectionCarrier.components[TransformComponent::class.java]!!

    fun attach(value: SpatialViewContent) {
        content = value
        placementOwner.setName("placementOwner")
        inspectionCarrier.setName("inspectionCarrier")
        fittedSceneRoot.setName("fittedSceneRoot")
        placementOwner.addChild(inspectionCarrier)
        inspectionCarrier.addChild(fittedSceneRoot)
        value.addEntity(placementOwner)
        inspectionCarrier.components.set(InteractableComponent())
        inspectionCarrier.addChild(grabBounds)
        grabBounds.setName("currentModelGrabBounds")
        grabBounds.components.set(InteractableComponent())
        if (room) transform.position = Vector3(0f, 1.2f, -1.5f)
        placementOwner.components.set(EnvironmentLightingSettingsComponent(1f))
        Log.i("BlenderBridge", "SCENE_COORDINATES room=$room ownerWorld=${placementOwner.convertPositionTo(Vector3.ZERO, null)} ownerWorldRotation=${placementOwner.convertRotationTo(Quat(), null)}")
    }

    /** Place once from a valid tracked viewer; afterwards the scene stays world locked. */
    fun placeForViewer(position: Vector3, rotation: Quat) {
        if(room) {
            headPosition=position;headRotation=rotation;headAt=android.os.SystemClock.elapsedRealtime()
            if(recenterTools)placeTools(position,rotation)
            renderWindow?.ensurePlaced(calibration,position,rotation)
            if(!linkedViewport) updateRenderFollowing()
        }
        if (!room || viewerPlaced || content == null) return
        viewerPlaced = true
        homePosition = placementOwner.convertPositionFrom(position + rotation.rotateVector(Vector3(0f, -.2f, -1.5f)), null)
        homeRotation = placementOwner.convertRotationFrom(rotation, null)
        if (!restored) { transform.position = homePosition; transform.quaternion = homeRotation }
        placeTools(position,rotation)
        if (!restored) {
            val monitorPosition = placementOwner.convertPositionFrom(position + rotation.rotateVector(Vector3(0f, -.2f, -1f)), null)
            event(BridgeUiEvent.MonitorPose(monitorPosition.values(), homeRotation.values()))
        }
        Log.i("BlenderBridge", "VIEWER_PLACEMENT head=$position rotation=$rotation home=$homePosition tracking=true worldLocked=true")
    }

    private fun updateRenderFollowing() {
        renderFollowingValid=true
        if(!room || !renderState.enabled || !renderState.faceUser)return
        val head=headPosition ?: return
        val window=renderWindow ?: return
        val points=if(renderState.autoFit) {
            // Use the model's fit-owner axes, then convert all eight corners.
            // A container AABB would inflate the sphere as the model rotates.
            val bounds=model?.getVisualBounds(fittedSceneRoot,true,true)
            if(bounds==null || bounds.isEmpty()) { renderFollowingValid=false;emptyList() }
            else (0 until 8).map { i -> fittedSceneRoot.convertPositionTo(Vector3(
                if(i and 1==0)bounds.min.x else bounds.max.x,
                if(i and 2==0)bounds.min.y else bounds.max.y,
                if(i and 4==0)bounds.min.z else bounds.max.z),null) }
        } else emptyList()
        val separation=(latest?.renderStereoEyeSeparationMm ?: 0f)/1000f
        val eyes=if(separation>0f && headRotation!=null)listOf(-.5f,.5f).map {
            head+headRotation!!.rotateVector(Vector3(it*separation,0f,0f))
        } else emptyList()
        renderFollowingValid=renderFollowingValid && window.follow(head,points,eyes)
        if(!renderFollowingValid)window.setProjectionValid(false)
        else if(renderState.mode==RenderWindowMode.LIVE_DISPLAY)window.setProjectionValid(true)
    }

    /** Screen-aligned view in the exported model's coordinate frame; Entity conversions run on main. */
    fun trackView(headPosition: Vector3) {
        if(!room || !linkedViewport || model==null) return
        val remote=renderState.enabled && renderState.mode==RenderWindowMode.SCENE_ALIGNED
        updateRenderFollowing()
        if(remote && !renderFollowingValid) {
            if(renderProjectionValid) { renderProjectionValid=false;event(BridgeUiEvent.RenderProjection(false)) }
            return
        }
        if(!remote && (!calibration.enabled || !calibration.locked))return
        val now=SystemClock.elapsedRealtime()
        if(now-lastCameraFrameAt<11) return
        lastCameraFrameAt=now
        val screen=if(remote) renderWindow?.anchor ?: return else monitor?.anchor ?: return
        try {
            val eye=screen.convertPositionFrom(headPosition,null)
            val position=fittedSceneRoot.convertPositionFrom(headPosition,null)
            val rotation=fittedSceneRoot.convertRotationFrom(screen.convertRotationTo(Quat(),null),null)
            val scale=fittedSceneRoot.convertScaleTo(Vector3(1f,1f,1f),null)
            require(scale.x>0 && abs(scale.y-scale.x)<scale.x*.001f && abs(scale.z-scale.x)<scale.x*.001f)
            var frame=if(remote) {
                val screenScale=screen.convertScaleTo(Vector3(1f,1f,1f),null).x
                com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildRenderCameraUseCase()(
                    (eye*screenScale).values(),position.values(),rotation.values(),scale.x,.9f*screenScale,
                    renderState.aspect,latest?.renderCrop ?: listOf(0f,0f,1f,1f))
            } else BuildMonitorCameraUseCase()(eye.values(),position.values(),rotation.values(),scale.x,calibration,hideSource)
            if(remote && (latest?.renderStereoEyeSeparationMm ?: 0f)>0f) {
                // Manual symmetric rig in tracked HMD-local metres, not the screen's local X.
                // Convert each eye through public Entity APIs before building its off-axis frustum.
                val separation=latest!!.renderStereoEyeSeparationMm/1000f
                val headQ=headRotation ?: return
                val screenScale=screen.convertScaleTo(Vector3(1f,1f,1f),null).x
                val eyes=listOf(-.5f,.5f).map { side ->
                    val globalEye=headPosition+headQ.rotateVector(Vector3(side*separation,0f,0f))
                    com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildRenderCameraUseCase()(
                        (screen.convertPositionFrom(globalEye,null)*screenScale).values(),
                        fittedSceneRoot.convertPositionFrom(globalEye,null).values(),rotation.values(),scale.x,
                        .9f*screenScale,renderState.aspect,latest!!.renderCrop)
                }
                frame=frame.copy(eyes=eyes)
            }
            if(remote) {
                renderWindow?.setProjectionValid(true)
                if(!renderProjectionValid) { renderProjectionValid=true;event(BridgeUiEvent.RenderProjection(true)) }
            }
            event(BridgeUiEvent.TrackedCameraFrame(frame))
        } catch(_: IllegalArgumentException) {
            if(remote) {
                renderWindow?.setProjectionValid(false)
                if(renderProjectionValid) { renderProjectionValid=false;event(BridgeUiEvent.RenderProjection(false)) }
                return
            }
            event(BridgeUiEvent.LinkViewport(false))
            event(BridgeUiEvent.Feedback("Camera sync stopped. Stand in front of the locked monitor; use uniform model scale."))
        }
    }

    /** Contacts are Stage/global meters; the monitor anchor is owned by placementOwner. */
    fun monitorFromTouches(request: com.example.blenderpicobridge.p7f82b4c1.domain.model.TouchAlignmentRequest,
        points: List<List<Float>>): MonitorCalibration {
        check(room && content != null)
        val fit=com.example.blenderpicobridge.p7f82b4c1.domain.usecase.FitTouchedScreenUseCase()(points,request.board)
        val p=placementOwner.convertPositionFrom(Vector3(fit.center[0],fit.center[1],fit.center[2]),null)
        val q=placementOwner.convertRotationFrom(Quat(fit.rotation[0],fit.rotation[1],fit.rotation[2],fit.rotation[3]),null)
        return calibration.copy(enabled=true,editing=true,locked=false,width=fit.width,height=fit.height,curveDegrees=0f,position=p.values(),quaternion=q.values())
    }

    /** The calibrated pose uses exactly the HMD provider's global Full Space frame. */
    fun monitorFromWorld(request: WorldMonitorRequest): MonitorCalibration {
        check(room && content!=null)
        val world=request.alignment.globalFromMarker
        val q=world.quaternion()
        val position=placementOwner.convertPositionFrom(Vector3(world.t[0].toFloat(),world.t[1].toFloat(),world.t[2].toFloat()),null)
        val rotation=placementOwner.convertRotationFrom(Quat(q[0].toFloat(),q[1].toFloat(),q[2].toFloat(),q[3].toFloat()),null)
        Log.i("BridgeWorld","apply samples=${request.alignment.samples} positionRms=${request.alignment.positionRms} angleRms=${request.alignment.angleRms} owner=placementOwner")
        return calibration.copy(enabled=true,editing=false,locked=true,width=request.estimate.arcWidth,height=request.estimate.height,
            curveDegrees=request.estimate.curveDegrees,position=position.values(),quaternion=rotation.values())
    }

    /** Capture all five controls in Stage metres through the public Entity conversion APIs. */
    fun alignmentSnapshot(request: AlignmentSaveRequest): AlignmentSnapshot {
        check(room && content!=null)
        fun global(p: List<Float>)=placementOwner.convertPositionTo(p.vector(),null).values()
        fun rotation(q: List<Float>)=placementOwner.convertRotationTo(q.quat(),null).values()
        val stage=stagedPose?.let { it.copy(position=global(it.position),quaternion=rotation(it.quaternion)) }
        return AlignmentSnapshot(request.monitor.copy(position=global(request.monitor.position),quaternion=rotation(request.monitor.quaternion)),
            request.handles.copy(corners=request.handles.corners.map(::global)),stage,revision)
    }

    /** Called only on main, only after PICO has localized the saved UUID in the current Stage. */
    fun anchoredAlignment(saved: SavedAlignment, reference: RigidPose, restoring: Boolean) {
        check(room && content!=null)
        val space=com.example.blenderpicobridge.p7f82b4c1.domain.usecase.AlignmentSpaceUseCase()
        fun local(p: List<Float>)=placementOwner.convertPositionFrom(p.vector(),null).values()
        fun rotation(q: List<Float>)=placementOwner.convertRotationFrom(q.quat(),null).values()
        fun localPose(p: InspectionPose)=p.copy(position=local(p.position),quaternion=rotation(p.quaternion))
        val previous=anchorReference
        if(previous!=null) {
            // A room recenter moves the frame, the current inspection and Reset take together.
            val delta=reference*previous.inverse()
            fun moved(p: InspectionPose): InspectionPose {
                val world=p.copy(position=placementOwner.convertPositionTo(p.position.vector(),null).values(),
                    quaternion=placementOwner.convertRotationTo(p.quaternion.quat(),null).values())
                return localPose(space.inspection(world,delta))
            }
            if(model!=null) restorePose(moved(snapshot()))
            stagedPose=stagedPose?.let(::moved)
            pendingAnchoredPose=pendingAnchoredPose?.let { it.first to it.second?.let(::moved) }
        }
        val global=space(saved.relative,reference)
        val localized=global.copy(monitor=global.monitor.copy(position=local(global.monitor.position),quaternion=rotation(global.monitor.quaternion)),
            handles=global.handles.copy(corners=global.handles.corners.map(::local)),stagedPose=global.stagedPose?.let(::localPose))
        if(previous==null && restoring) {
            restored=true // Initial HMD placement must not overwrite the localized frame or its fit.
            pendingAnchoredPose=localized.revision to localized.stagedPose
        }
        anchorReference=reference
        calibration=localized.monitor.copy(enabled=true,editing=false,locked=true)
        event(BridgeUiEvent.AlignmentRestored(calibration,localized.handles))
        Log.i("BlenderBridge","ALIGNMENT_LOCALIZED restoring=$restoring corners=${localized.handles.corners.size} curve=${calibration.curveDegrees}")
    }

    fun detachAlignmentAnchor() { anchorReference=null;pendingAnchoredPose=null }

    fun measure() {
        if (room) return
        val value = content ?: return
        val corner = value.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, value.localSpatialCoordinateSpace)
        half = Vector3(abs(corner.x) * .88f, abs(corner.y) * .88f, abs(corner.z) * .88f)
    }

    suspend fun replace(file: File, packet: FramePacket) {
        if (revision == packet.revision) return
        val incoming = Entity.loadSuspend(file)
        val nextFit = Entity()
        nextFit.setName("fittedSceneRoot")
        nextFit.enabled = false
        nextFit.addChild(incoming)
        inspectionCarrier.addChild(nextFit)
        val nextControllers = mutableListOf<AnimationPlaybackController>()
        val nextNodes = mutableMapOf<String, Entity>()
        var nextShape: ShapeResource? = null
        var committed = false
        try {
            measure()
            require(room || min(half.x, min(half.y, half.z)) > .01f) { "Model volume has no measurable depth" }
            fun visit(entity: Entity) {
                nextNodes[entity.getName()] = entity
                entity.getAnimationResources().firstOrNull()?.let { clip ->
                    nextControllers.add(entity.playAnimation(clip, AnimationPlayConfig(transitionDuration = 0f)).also { it.pause() })
                }
                entity.getChildren().forEach(::visit)
            }
            visit(incoming)
            // Validate/seek the incoming scene before retiring the last complete model.
            applyPose(packet, nextControllers, nextNodes, 0f)
            val bounds = incoming.getVisualBounds(nextFit, true, false)
            require(!bounds.isEmpty() && bounds.boundingSphereRadius > 0f) { "Model has no visible mesh" }
            applyPose(packet, nextControllers, nextNodes, 1f)
            val expanded = incoming.getVisualBounds(nextFit, true, false)
            val difference = expanded.center - bounds.center
            val radius = max(bounds.boundingSphereRadius, expanded.boundingSphereRadius +
                sqrt(difference.x * difference.x + difference.y * difference.y + difference.z * difference.z))
            require(radius.isFinite() && radius > 0f) { "Exploded model has invalid bounds" }
            // A centered sphere fits under every rotation. Preserve inspection pose by reducing
            // available radius for the current displacement and inspection scale.
            val position = transform.position
            val available = min(half.x - abs(position.x), min(half.y - abs(position.y), half.z - abs(position.z)))
            require(room || available > .001f) { "Reset the view to provide space for this model" }
            val previousPose = if (room && model != null) snapshot() else null
            val replacementFit = ModelReplacementFitUseCase()(room, previousPose, bounds.center.values(),
                bounds.boundingSphereRadius, available, inspectionScale)
            val fit = replacementFit.scale
            nextFit.components[TransformComponent::class.java]!!.apply {
                scaleVector = Vector3(fit, fit, fit)
                this.position = replacementFit.offset.vector()
            }
            val readyBounds = incoming.getVisualBounds(placementOwner, true, false)
            // Preview initially fits the current assembled model. Separation may refit later.
            applyPose(packet, nextControllers, nextNodes, explosion)
            // Sphere from both measured visual envelopes reserves all intermediate amounts
            // and every inspection rotation; collision remains usable on separated parts.
            val currentBounds = incoming.getVisualBounds(inspectionCarrier, true, false)
            nextShape = ShapeResource.createBox(currentBounds.halfExtent * 2f).sceneOwned()
            grabBounds.components[TransformComponent::class.java]!!.position = currentBounds.center
            Log.i("BlenderBridge", "EXPLOSION_ENVELOPE parts=${packet.explodeParts.size} sourceMin=${bounds.min} sourceMax=${bounds.max} fullMin=${expanded.min} fullMax=${expanded.max} fit=$fit owner=placementOwner safeHalf=$half fullVisualMin=${readyBounds.min} fullVisualMax=${readyBounds.max} fullContained=${within(readyBounds)}")
            applyPose(packet, nextControllers, nextNodes, explosion)
            require(within(incoming.getVisualBounds(placementOwner, true, false))) { "Inspection pose does not fit" }
            if (physics == null) physics = PhysicsMaterialResource().sceneOwned()
            if(!modelPickingSuspended) grabBounds.components.set(CollisionComponent(collisionShape = listOf(nextShape), physicsMaterial = physics!!))
            val previous = fittedSceneRoot
            val previousShape = shape
            val previousControllers = controllers.toList()
            fittedSceneRoot = nextFit
            model = incoming
            modelVisibility.bind(incoming)
            shape = nextShape
            nodes.clear(); nodes.putAll(nextNodes)
            controllers.clear(); controllers.addAll(nextControllers)
            revision = packet.revision
            sequence = packet.sequence
            latest = packet
            reservedRadius = radius * fit
            monitor?.bindModel(incoming)
            nextFit.enabled = true
            previous.enabled = false
            committed = true
            // Cleanup cannot turn a ready replacement into a partially installed scene.
            previousControllers.forEach { runCatching { it.stop(); it.close() } }
            runCatching { previous.destroy(true) }
            runCatching { previousShape?.close() }
            Log.i("BlenderBridge", "MODEL revision=${revision?.take(8)} nodes=${nodes.size} textures=${packet.textureCount} controllers=${controllers.size} fit=$fit half=$half contained=${contained()}")
            event(BridgeUiEvent.ModelReady(packet.revision!!, fit))
            event(BridgeUiEvent.InspectionChanged(inspectionScale))
            if (previousPose != null) savePose()
            Log.i("BlenderBridge", "MODEL_SWAP preserveRoomFit=${previousPose!=null} fit=$fit offset=${replacementFit.offset} carrier=${transform.position} inspectionScale=$inspectionScale")
        } catch (error: Exception) {
            if (!committed) {
                nextControllers.forEach { runCatching { it.stop(); it.close() } }
                nextFit.destroy(true)
                nextShape?.close()
            }
            throw error
        }
    }

    private fun applyPose(packet: FramePacket, playback: List<AnimationPlaybackController>, targetNodes: Map<String, Entity>, amount: Float = explosion) {
        playback.forEach { it.setTime(animationTime(packet.timeSeconds, it.getDuration())) }
        explosionLayer.poses(packet, amount).forEach { pose ->
            targetNodes[pose.name]?.components?.get(TransformComponent::class.java)?.apply {
                position = Vector3(pose.position[0], pose.position[1], pose.position[2])
                quaternion = Quat(pose.quaternion[0], pose.quaternion[1], pose.quaternion[2], pose.quaternion[3])
                scaleVector = Vector3(pose.scale[0], pose.scale[1], pose.scale[2])
            }
        }
    }

    fun apply(packet: FramePacket, force: Boolean = false) {
        if (packet.revision != revision || (!force && packet.sequence == sequence)) return
        sequence = packet.sequence
        latest = packet
        applyPose(packet, controllers, nodes)
        if (!linkedViewport && !contained()) {
            reset()
            // Animated bounds can exceed the initial sphere. Keep the current pose contained.
            val bounds = model?.getVisualBounds(inspectionCarrier, true, true) ?: return
            val factor = min(half.x / (abs(bounds.center.x) + bounds.halfExtent.x),
                min(half.y / (abs(bounds.center.y) + bounds.halfExtent.y), half.z / (abs(bounds.center.z) + bounds.halfExtent.z))) * .98f
            fittedSceneRoot.components[TransformComponent::class.java]!!.apply {
                scaleVector = scaleVector * factor
                position = position * factor
                reservedRadius *= factor
                event(BridgeUiEvent.FitChanged(scaleVector.x))
            }
            event(BridgeUiEvent.InspectionChanged(1f, "Animated model refitted to the available volume."))
        }
        updateRenderFollowing()
        if (force || sequence % 10L == 0L) { updateGrabBounds(); updateSourceVisibility() }
        if (sequence % 100L == 0L && room) Log.i("BlenderBridge", "POSE_DIAGNOSTIC position=${transform.position} rotation=${transform.quaternion} visible=${model?.enabled} fitEnabled=${fittedSceneRoot.enabled} sourceHidden=$sourceHidden")
        if (sequence % 20L == 0L) Log.i("BlenderBridge", "FRAME frame=${packet.frame} time=${packet.timeSeconds} playing=${packet.playing} sequence=$sequence contained=${contained()}")
    }

    fun setInspection(nextMode: InspectionMode, amount: Float) {
        mode = nextMode
        if (explosion == amount) return
        explosion = amount
        latest?.let { apply(it, force = true) }
        Log.i("BlenderBridge", "INSPECTION mode=$mode explosion=$explosion scale=$inspectionScale contained=${contained()}")
    }

    fun resetIfChanged(counter: Int) { if (counter != resetCounter) { resetCounter = counter; reset() } }
    private fun reset() {
        transform.position = if (room) homePosition else Vector3.ZERO
        transform.quaternion = if (room) homeRotation else Quat()
        transform.scaleVector = Vector3(1f, 1f, 1f)
        inspectionScale = 1f
    }

    private fun contained(): Boolean {
        val bounds = model?.getVisualBounds(placementOwner, true, true) ?: return true
        return within(bounds)
    }

    private fun within(bounds: com.pico.spatial.core.ecs.BoundingBox): Boolean =
        room || (abs(bounds.center.x) + bounds.halfExtent.x <= half.x + .001f &&
            abs(bounds.center.y) + bounds.halfExtent.y <= half.y + .001f &&
            abs(bounds.center.z) + bounds.halfExtent.z <= half.z + .001f)

    fun gesturePointers(ids: Set<Long>) = gestureContinuity.pointers(ids)
    fun beginGesture() = gestureContinuity.rebase()

    fun manipulate(value: SpatialTransformValue) {
        val target=value.targetEntity ?: return
        if(!accepts(target)) return
        Log.i("BlenderBridge", "HAND_TRANSFORM drag=${value.dragAmount} scale=${value.scaleValue} rotation=${value.rotation}")
        val owner = content ?: return
        measure()
        val oldPosition = transform.position
        val oldRotation = transform.quaternion
        val oldScale = inspectionScale
        val origin = owner.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, owner.localSpatialCoordinateSpace)
        val moved = owner.convertPosition(Vector3(value.dragAmount.x, value.dragAmount.y, value.dragAmount.z), ViewCoordinateSpace.Local, owner.localSpatialCoordinateSpace)
        val basis = owner.convertRotation(Quat(), ViewCoordinateSpace.Global, owner.localSpatialCoordinateSpace)
        // Full public conversion accounts for the reflection between View and ECS axes.
        val converted = owner.convertRotation(value.rotation.toQuaternion(), ViewCoordinateSpace.Global, owner.localSpatialCoordinateSpace)
        val rotation = converted * basis.conjugate()
        if (!gestureContinuity.accept((moved-origin).values(),value.scaleValue,rotation.values())) {
            Log.i("BlenderBridge", "HAND_REBASE skipped discontinuous or transition sample")
            return
        }
        if(toolsPicking?.owns(target)==true) {
            tools?.components?.get(TransformComponent::class.java)?.apply {
                position=position+placementOwner.convertPositionFrom(moved,null)-placementOwner.convertPositionFrom(origin,null)
            }
            return
        }
        if(model==null)return
        if(renderWindow?.owns(target)==true) {
            renderWindow?.manipulate(moved-origin,rotation,value.scaleValue)
            return
        }
        monitorHandles?.index(value.targetEntity)?.let { index ->
            event(BridgeUiEvent.MonitorHandleMoved(index,(moved-origin).values()))
            return
        }
        if (calibration.editing && !manualHandles && monitor?.owns(value.targetEntity)==true) {
            monitor?.anchor?.components?.get(TransformComponent::class.java)?.apply {
                position = position + moved - origin; quaternion = rotation * quaternion
                event(BridgeUiEvent.MonitorPose(position.values(), quaternion.values()))
            }
            return
        }
        if (linkedViewport && !room) {
            val anchor = if (room) homeRotation else Quat()
            val pan = anchor.conjugate().rotateVector(moved-origin) * (1f / max(if (room) calibration.height else half.y*2f,.1f))
            val relative = anchor.conjugate()*rotation*anchor
            val zoom = if (mode == InspectionMode.SCALE) value.scaleValue else 1f
            if (zoom.isFinite() && zoom > 0f) event(BridgeUiEvent.MoveViewport(ViewportDelta(pan.values(),relative.values(),zoom)))
            if (mode == InspectionMode.EXPLODE) {
                val amount = explosionLayer.fromSpan(explosion,value.scaleValue)
                setInspection(mode,amount); event(BridgeUiEvent.ExplosionChanged(amount))
            }
            return
        }
        if (mode == InspectionMode.EXPLODE) {
            val amount = explosionLayer.fromSpan(explosion, value.scaleValue)
            setInspection(mode, amount)
            event(BridgeUiEvent.ExplosionChanged(amount))
        } else if (value.scaleValue.isFinite() && value.scaleValue > 0f) {
            inspectionScale = (inspectionScale * value.scaleValue).coerceIn(.1f, 10f)
        }
        transform.position = oldPosition + moved - origin
        val nextRotation = rotation * oldRotation
        val rotationLength = sqrt(nextRotation.x*nextRotation.x + nextRotation.y*nextRotation.y + nextRotation.z*nextRotation.z + nextRotation.w*nextRotation.w)
        transform.quaternion = Quat(nextRotation.x/rotationLength,nextRotation.y/rotationLength,nextRotation.z/rotationLength,nextRotation.w/rotationLength)
        transform.scaleVector = Vector3(inspectionScale, inspectionScale, inspectionScale)
        if (!contained()) {
            inspectionScale = oldScale
            transform.position = oldPosition
            transform.quaternion = oldRotation
            transform.scaleVector = Vector3(oldScale, oldScale, oldScale)
            event(BridgeUiEvent.InspectionChanged(oldScale, "Volume limit reached. Enlarge the window or reset the view."))
        } else {
            event(BridgeUiEvent.InspectionChanged(inspectionScale)); savePose(); updateSourceVisibility()
            updateRenderFollowing();headPosition?.let(::trackView)
        }
    }

    fun accepts(entity: Entity): Boolean {
        if (toolsPicking?.owns(entity)==true) return true
        if(renderWindow?.owns(entity)==true)return true
        if(monitorHandles?.index(entity)!=null) return true
        if (monitor?.owns(entity)==true) return calibration.editing && !manualHandles
        if(calibration.editing && manualHandles) return false
        var current: Entity? = entity
        while (current != null) { if (current == inspectionCarrier) return true; current = current.getParent() }
        return false
    }

    fun preferences(feed: SceneFeed) {
        toolsPicking?.measure()
        if(toolsReset!=feed.toolsReset) {
            toolsReset=feed.toolsReset;recenterTools=true
            if(headPosition!=null && headRotation!=null)placeTools(headPosition!!,headRotation!!)
        }
        val seedRenderCamera=room && feed.linkViewport &&
            (!linkedViewport || renderState!=feed.renderWindow)
        renderState=feed.renderWindow
        if(room && receiveRenderVideo!=null && renderState.enabled) {
            if(renderWindow==null || kotlin.math.abs(renderWindow!!.aspect-renderState.aspect)>.001f) {
                val pose=renderWindow?.pose();val manual=renderWindow?.manualPlacement();renderWindow?.close()
                renderWindow=RenderWindowScene(placementOwner,renderState.aspect,receiveRenderVideo) { event(BridgeUiEvent.RenderStatus(it)) }
                pose?.let { renderWindow?.restore(it) }
                renderWindow?.restoreManualPlacement(manual)
            }
        }
        renderWindow?.update(renderState,feed.renderPairing,feed.monitor,headPosition,headRotation)
        if (feed.linkViewport != linkedViewport && model != null) {
            linkedViewport = feed.linkViewport
            if (linkedViewport) {
                linkedSnapshot=snapshot();linkedDistance=latest?.viewport?.distance ?: 1f;linkedRevision=revision

            }
            else { if (!room && linkedRevision == revision) linkedSnapshot?.let(::restorePose); linkedSnapshot = null; linkedRevision = null }
        }
        modelVisibility.setVisible(feed.modelVisible)
        val showHover = feed.hover && feed.modelVisible
        if (hover != showHover) {
            hover = showHover
            if (hover) inspectionCarrier.components.set(HoverEffectComponent())
            else inspectionCarrier.components.remove(HoverEffectComponent::class.java)
        }
        hideSource = feed.hideSource && !feed.monitor.editing && !feed.renderWindow.enabled && (room || !linkedViewport)
        calibration = feed.monitor
        setModelPickingSuspended(room && calibration.editing)
        manualHandles=room && feed.monitorHandles!=null && calibration.editing && !calibration.locked
        if(room && monitorHandles==null && feed.monitorHandles!=null) monitorHandles=MonitorHandles(placementOwner)
        monitorHandles?.update(if(room) feed.monitorHandles else null,calibration)
        if (room && calibration.enabled && feed.touchAlignment == null) {
            if (monitor == null) monitor = MonitorThreshold(placementOwner).also { mask -> model?.let(mask::bindModel) }
            model?.let { monitor?.bindModel(it) }
            monitor?.update(calibration,manualHandles,feed.monitorHandles)
        } else { monitor?.update(calibration.copy(enabled = false)); monitor?.unbindModel() }
        if (!restored && model != null) {
            restored = true
            (if (room) feed.roomPose else feed.previewPose)?.let(::restorePose)
        }
        if(model!=null && pendingAnchoredPose!=null && calibration.enabled && calibration.locked) {
            val pending=pendingAnchoredPose!!;pendingAnchoredPose=null
            if(pending.first==revision && pending.second!=null) {
                stagedPose=pending.second;restorePose(pending.second!!)
                event(BridgeUiEvent.Feedback("Saved viewport frame, curvature and staged fit restored."))
            } else stageTake() // A different scene gets a fresh fit behind the retained frame.
            commandSequence=feed.commandSequence // Do not replay an old Stage command after restoring.
        }
        if (model != null && commandSequence != feed.commandSequence) {
            commandSequence = feed.commandSequence
            when (feed.command) {
                SceneCommand.SOURCE_SIZE -> { check(room) { "Source size requires Room mode" }; fit(1f); reset(); event(BridgeUiEvent.InspectionChanged(1f)); savePose() }
                SceneCommand.FIT -> { reset(); val bounds = model!!.getVisualBounds(fittedSceneRoot, true, true); fit(min(half.x, min(half.y, half.z)) / (bounds.boundingSphereRadius * 1.2f)); event(BridgeUiEvent.InspectionChanged(1f)); savePose() }
                SceneCommand.STAGE_TAKE -> {
                    stageTake()
                    feed.debugReveal?.let { fraction ->
                        val anchor = monitor!!.anchor
                        val box = model!!.getVisualBounds(anchor, true, true)
                        val delta = Vector3(0f, 0f, (box.halfExtent.z * 2f + .06f) * fraction)
                        transform.position = transform.position + placementOwner.convertPositionFrom(delta, anchor) - placementOwner.convertPositionFrom(Vector3.ZERO, anchor)
                        logMonitorBounds(); savePose()
                        Log.i("BlenderBridge", "DEBUG_MONITOR_PROBE reveal=$fraction simulatedHandInput=false")
                    }
                }
                SceneCommand.RESET_TAKE -> stagedPose?.let { restorePose(it); fitBehindMonitor(); event(BridgeUiEvent.Feedback("Model reset behind monitor.")); logMonitorBounds() }
                    ?: stageTake()
                SceneCommand.NONE -> Unit
            }
        }
        if (linkedViewport && room && !feed.renderWindow.enabled && stagedPose==null && calibration.enabled && calibration.locked && model!=null) stageTake()
        if (linkedViewport && !room) latest?.let { packet ->
            val view = packet.viewport
            val source = linkedSnapshot
            if (view != null && source != null && packet.viewQuaternion != null) {
                val fit = (source.fitScale*linkedDistance/view.distance).coerceIn(.00001f,1000f)
                val pivot = Vector3(view.pivot[0],view.pivot[2],-view.pivot[1])
                transform.position = source.position.vector()
                transform.quaternion = (if (room) homeRotation else Quat())*packet.viewQuaternion.quat()
                transform.scaleVector = Vector3(1f,1f,1f); inspectionScale = 1f
                fittedSceneRoot.components[TransformComponent::class.java]!!.apply { scaleVector=Vector3(fit,fit,fit);position=pivot*(-fit) }
                updateGrabBounds()
            }
        }
        updateSourceVisibility()
        updateRenderFollowing()
        // A stationary emulator may emit no new HMD sample after enabling/recentering.
        // Seed once from the last valid tracked pose; later motion retains the fast path.
        if(seedRenderCamera && renderState.enabled && linkedViewport) headPosition?.let(::trackView)
    }

    private fun fit(scale: Float) {
        val bounds = model?.getVisualBounds(fittedSceneRoot, true, true) ?: return
        require(!bounds.isEmpty() && scale.isFinite() && scale > 0f)
        fittedSceneRoot.components[TransformComponent::class.java]!!.apply {
            scaleVector = Vector3(scale, scale, scale); position = bounds.center * -scale
        }
        event(BridgeUiEvent.FitChanged(scale)); updateGrabBounds()
    }

    private fun stageTake() {
        check(room && calibration.enabled) { "Align the monitor in Room mode first." }
        val planeOwner = monitor?.anchor ?: error("Monitor mask unavailable")
        val planeTransform = planeOwner.components[TransformComponent::class.java]!!
        transform.apply {
            position = planeTransform.position
            quaternion = planeTransform.quaternion * (latest?.viewQuaternion?.quat() ?: Quat())
            scaleVector = Vector3(1f, 1f, 1f)
        }
        inspectionScale = 1f
        event(BridgeUiEvent.InspectionChanged(1f))
        fit(1f)
        var bounds = model!!.getVisualBounds(planeOwner, true, true)
        require(!bounds.isEmpty()) { "No visible model to stage" }
        val scale = min(CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees).chord * .8f / (bounds.halfExtent.x * 2f), calibration.height * .8f / (bounds.halfExtent.y * 2f))
        fit(scale)
        bounds = model!!.getVisualBounds(planeOwner, true, true)
        val delta = Vector3(-bounds.center.x, -bounds.center.y, -.03f - bounds.max.z)
        val origin = placementOwner.convertPositionFrom(Vector3.ZERO, planeOwner)
        val endpoint = placementOwner.convertPositionFrom(delta, planeOwner)
        transform.position = transform.position + endpoint - origin
        stagedPose = snapshot(); savePose(); updateGrabBounds(); logMonitorBounds()
        event(BridgeUiEvent.Feedback("Model matched to Blender view and staged behind monitor. Lock alignment, then grab and pull."))
    }

    private fun fitBehindMonitor() {
        val owner = monitor?.anchor ?: return
        var bounds = model?.getVisualBounds(owner, true, true) ?: return
        val ratio = min(CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees).chord * .8f / (bounds.halfExtent.x * 2f), calibration.height * .8f / (bounds.halfExtent.y * 2f))
        if (ratio < .999f) {
            val fitted = fittedSceneRoot.components[TransformComponent::class.java]!!
            fit(fitted.scaleVector.x * ratio)
            bounds = model!!.getVisualBounds(owner, true, true)
        }
        val delta = Vector3(-bounds.center.x, -bounds.center.y, -.03f - bounds.max.z)
        transform.position = transform.position + placementOwner.convertPositionFrom(delta, owner) - placementOwner.convertPositionFrom(Vector3.ZERO, owner)
        updateGrabBounds(); savePose()
    }

    private fun updateSourceVisibility() {
        val anchor = monitor?.anchor
        val hidden = room && hideSource && calibration.locked && stagedPose != null && anchor != null &&
            (model?.getVisualBounds(anchor, true, true)?.min?.z ?: -1f) > CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees).edgeDepth+.015f
        if (hidden != sourceHidden) {
            sourceHidden = hidden
            Log.i("BlenderBridge", "SOURCE_VISIBILITY requested=$hidden carrier=${transform.position} modelEnabled=${model?.enabled}")
            event(BridgeUiEvent.SourceHidden(hidden))
        }
    }
    private fun logMonitorBounds() {
        val anchor = monitor?.anchor ?: return
        val bounds = model?.getVisualBounds(anchor, true, true) ?: return
        val fitsXY = abs(bounds.center.x) + bounds.halfExtent.x <= CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees).chord * .4f + .001f &&
            abs(bounds.center.y) + bounds.halfExtent.y <= calibration.height * .4f + .001f
        Log.i("BlenderBridge", "MONITOR_TAKE owner=monitorAnchor min=${bounds.min} max=${bounds.max} plane=${calibration.width}x${calibration.height} planeWorld=${anchor.convertPositionTo(Vector3.ZERO, null)} modelWorld=${model!!.getVisualBounds(null, true, true).center} gap=.03 behind=${bounds.max.z <= -.029f} fitsXY=$fitsXY visual=UNOBSERVED")
    }

    private fun snapshot(): InspectionPose {
        val fitted = fittedSceneRoot.components[TransformComponent::class.java]!!
        return InspectionPose(transform.position.values(), transform.quaternion.values(), inspectionScale,
            fitted.scaleVector.x, fitted.position.values())
    }
    private fun savePose() = event(BridgeUiEvent.PoseSaved(room, snapshot()))
    private fun restorePose(pose: InspectionPose) {
        transform.apply { position = pose.position.vector(); quaternion = pose.quaternion.quat(); scaleVector = Vector3(pose.scale, pose.scale, pose.scale) }
        inspectionScale = pose.scale
        fittedSceneRoot.components[TransformComponent::class.java]!!.apply { scaleVector = Vector3(pose.fitScale, pose.fitScale, pose.fitScale); position = pose.fitPosition.vector() }
        event(BridgeUiEvent.FitChanged(pose.fitScale)); event(BridgeUiEvent.InspectionChanged(pose.scale)); updateGrabBounds(); savePose()
    }
    private fun setModelPickingSuspended(suspended: Boolean) {
        if(modelPickingSuspended==suspended) return
        modelPickingSuspended=suspended
        if(suspended) {
            grabBounds.components.remove(InteractableComponent::class.java)
            grabBounds.components.remove(CollisionComponent::class.java)
        } else {
            grabBounds.components.set(InteractableComponent())
            collisionKey=emptyList() // Restore even if the staged bounds have not changed.
            updateGrabBounds()
        }
        Log.i("BlenderBridge","MODEL_PICKING suspended=$suspended collider=${grabBounds.components[CollisionComponent::class.java]!=null}")
    }
    private fun updateGrabBounds() {
        if(modelPickingSuspended) return
        val bounds = model?.getVisualBounds(inspectionCarrier, true, true) ?: return
        if (bounds.isEmpty()) return
        val key = bounds.center.values() + bounds.halfExtent.values()
        if (collisionKey.size == key.size && key.indices.all { abs(key[it] - collisionKey[it]) < .01f }) return
        val next = ShapeResource.createBox(bounds.halfExtent * 2f).sceneOwned()
        grabBounds.components[TransformComponent::class.java]!!.position = bounds.center
        grabBounds.components.set(CollisionComponent(listOf(next), physics!!))
        shape?.close(); shape = next; collisionKey = key
        Log.i("BlenderBridge", "GRAB_BOUNDS owner=inspectionCarrier center=${bounds.center} size=${bounds.halfExtent * 2f}")

    }

    fun setRenderActive(active: Boolean) {
        renderWindow?.setActive(active)
        if(!active && renderState.enabled) event(BridgeUiEvent.RenderStatus(RenderVideoStatus("Render paused while app is hidden")))
    }

    fun close() {
        toolsPicking?.close();toolsPicking=null;palm=null;tools=null
        renderWindow?.close();renderWindow=null
        if (model != null) savePose()
        monitorHandles?.close();monitorHandles=null
        monitor?.close(); monitor = null
        controllers.forEach { it.stop(); it.close() }
        controllers.clear()
        content?.removeEntity(placementOwner)
        modelVisibility.clear()
        placementOwner.destroy(true)
        shape?.close()
        physics?.close()
        content = null
    }
}
