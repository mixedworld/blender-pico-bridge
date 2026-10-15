package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionMode
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ExplosionUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.AnimationTimeUseCase
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
class BridgeSceneRuntime(private val event: (BridgeUiEvent) -> Unit, private val room: Boolean = false) {
    val placementOwner = Entity()
    val inspectionCarrier = Entity()
    private var fittedSceneRoot = Entity()
    private var model: Entity? = null
    private var content: SpatialViewContent? = null
    private var shape: ShapeResource? = null
    private var physics: PhysicsMaterialResource? = null
    private val controllers = mutableListOf<AnimationPlaybackController>()
    private val nodes = mutableMapOf<String, Entity>()
    private val animationTime = AnimationTimeUseCase()
    private val explosionLayer = ExplosionUseCase()
    private var mode = InspectionMode.SCALE
    private var explosion = 0f
    private var latest: FramePacket? = null
    private var reservedRadius = 0f
    private val grabBounds = Entity()
    private var monitor: MonitorThreshold? = null
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
    private var collisionKey = emptyList<Float>()
    var tools: Entity? = null
    private var revision: String? = null
    private var sequence = -1L
    private var half = Vector3.ZERO
    private var resetCounter = 0
    private var inspectionScale = 1f
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
        if (!room || viewerPlaced || content == null) return
        viewerPlaced = true
        homePosition = placementOwner.convertPositionFrom(position + rotation.rotateVector(Vector3(0f, -.2f, -1.5f)), null)
        homeRotation = placementOwner.convertRotationFrom(rotation, null)
        if (!restored) { transform.position = homePosition; transform.quaternion = homeRotation }
        tools?.components?.get(TransformComponent::class.java)?.apply {
            this.position = placementOwner.convertPositionFrom(position + rotation.rotateVector(Vector3(.7f, -.3f, -1f)), null)
            quaternion = homeRotation
        }
        if (!restored) {
            val monitorPosition = placementOwner.convertPositionFrom(position + rotation.rotateVector(Vector3(0f, -.2f, -1f)), null)
            event(BridgeUiEvent.MonitorPose(monitorPosition.values(), homeRotation.values()))
        }
        Log.i("BlenderBridge", "VIEWER_PLACEMENT head=$position rotation=$rotation home=$homePosition tracking=true worldLocked=true")
    }

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
            val fit = if (room) 1f else available / (bounds.boundingSphereRadius * 1.2f * inspectionScale)
            nextFit.components[TransformComponent::class.java]!!.apply {
                scaleVector = Vector3(fit, fit, fit)
                this.position = bounds.center * -fit
            }
            val readyBounds = incoming.getVisualBounds(placementOwner, true, false)
            // Preview initially fits the current assembled model. Separation may refit later.
            applyPose(packet, nextControllers, nextNodes, explosion)
            // Sphere from both measured visual envelopes reserves all intermediate amounts
            // and every inspection rotation; collision remains usable on separated parts.
            val currentBounds = incoming.getVisualBounds(inspectionCarrier, true, false)
            nextShape = ShapeResource.createBox(currentBounds.halfExtent * 2f)
            grabBounds.components[TransformComponent::class.java]!!.position = currentBounds.center
            Log.i("BlenderBridge", "EXPLOSION_ENVELOPE parts=${packet.explodeParts.size} sourceMin=${bounds.min} sourceMax=${bounds.max} fullMin=${expanded.min} fullMax=${expanded.max} fit=$fit owner=placementOwner safeHalf=$half fullVisualMin=${readyBounds.min} fullVisualMax=${readyBounds.max} fullContained=${within(readyBounds)}")
            applyPose(packet, nextControllers, nextNodes, explosion)
            require(within(incoming.getVisualBounds(placementOwner, true, false))) { "Inspection pose does not fit" }
            if (physics == null) physics = PhysicsMaterialResource()
            grabBounds.components.set(CollisionComponent(collisionShape = listOf(nextShape), physicsMaterial = physics!!))
            val previous = fittedSceneRoot
            val previousShape = shape
            val previousControllers = controllers.toList()
            fittedSceneRoot = nextFit
            model = incoming
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
        if (!contained()) {
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

    fun manipulate(value: SpatialTransformValue) {
        if (model == null) return
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
        if (value.targetEntity == tools) {
            tools?.components?.get(TransformComponent::class.java)?.apply { position = position + moved - origin; quaternion = rotation * quaternion }
            return
        }
        if (calibration.editing && value.targetEntity == monitor?.plane) {
            monitor?.anchor?.components?.get(TransformComponent::class.java)?.apply {
                position = position + moved - origin; quaternion = rotation * quaternion
                event(BridgeUiEvent.MonitorPose(position.values(), quaternion.values()))
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
        transform.quaternion = rotation * oldRotation
        transform.scaleVector = Vector3(inspectionScale, inspectionScale, inspectionScale)
        if (!contained()) {
            inspectionScale = oldScale
            transform.position = oldPosition
            transform.quaternion = oldRotation
            transform.scaleVector = Vector3(oldScale, oldScale, oldScale)
            event(BridgeUiEvent.InspectionChanged(oldScale, "Volume limit reached. Enlarge the window or reset the view."))
        } else { event(BridgeUiEvent.InspectionChanged(inspectionScale)); savePose(); updateSourceVisibility() }
    }

    fun accepts(entity: Entity): Boolean {
        if (entity == monitor?.plane || entity == tools) return true
        var current: Entity? = entity
        while (current != null) { if (current == inspectionCarrier) return true; current = current.getParent() }
        return false
    }

    fun preferences(feed: SceneFeed) {
        if (hover != feed.hover) {
            hover = feed.hover
            if (hover) inspectionCarrier.components.set(HoverEffectComponent())
            else inspectionCarrier.components.remove(HoverEffectComponent::class.java)
        }
        hideSource = feed.hideSource
        calibration = feed.monitor
        if (room && calibration.enabled) {
            if (monitor == null) monitor = MonitorThreshold(placementOwner).also { mask -> model?.let(mask::bindModel) }
            model?.let { monitor?.bindModel(it) }
            monitor?.update(calibration)
        } else { monitor?.update(calibration.copy(enabled = false)); monitor?.unbindModel() }
        if (!restored && model != null) {
            restored = true
            (if (room) feed.roomPose else feed.previewPose)?.let(::restorePose)
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
        updateSourceVisibility()
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
        val scale = min(calibration.width * .8f / (bounds.halfExtent.x * 2f), calibration.height * .8f / (bounds.halfExtent.y * 2f))
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
        val ratio = min(calibration.width * .8f / (bounds.halfExtent.x * 2f), calibration.height * .8f / (bounds.halfExtent.y * 2f))
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
            (model?.getVisualBounds(anchor, true, true)?.min?.z ?: -1f) > .015f
        if (hidden != sourceHidden) {
            sourceHidden = hidden
            Log.i("BlenderBridge", "SOURCE_VISIBILITY requested=$hidden carrier=${transform.position} modelEnabled=${model?.enabled}")
            event(BridgeUiEvent.SourceHidden(hidden))
        }
    }
    private fun logMonitorBounds() {
        val anchor = monitor?.anchor ?: return
        val bounds = model?.getVisualBounds(anchor, true, true) ?: return
        val fitsXY = abs(bounds.center.x) + bounds.halfExtent.x <= calibration.width * .4f + .001f &&
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
    private fun updateGrabBounds() {
        val bounds = model?.getVisualBounds(inspectionCarrier, true, true) ?: return
        if (bounds.isEmpty()) return
        val key = bounds.center.values() + bounds.halfExtent.values()
        if (collisionKey.size == key.size && key.indices.all { abs(key[it] - collisionKey[it]) < .01f }) return
        val next = ShapeResource.createBox(bounds.halfExtent * 2f)
        grabBounds.components[TransformComponent::class.java]!!.position = bounds.center
        grabBounds.components.set(CollisionComponent(listOf(next), physics!!))
        shape?.close(); shape = next; collisionKey = key
        Log.i("BlenderBridge", "GRAB_BOUNDS owner=inspectionCarrier center=${bounds.center} size=${bounds.halfExtent * 2f}")
    }

    fun close() {
        if (model != null) savePose()
        monitor?.close(); monitor = null
        controllers.forEach { it.stop(); it.close() }
        controllers.clear()
        content?.removeEntity(placementOwner)
        placementOwner.destroy(true)
        shape?.close()
        physics?.close()
        content = null
    }
}
