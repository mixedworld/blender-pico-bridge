package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.MonitorCalibration
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.ecs.resource.ShaderGraphMaterial
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Color4

/** Dedicated depth occluder. Source model materials and their draw sorting stay authored. */
internal class MonitorThreshold(owner: Entity) : AutoCloseable {
    val anchor = Entity()
    val plane = Entity()
    private val planeMesh = MeshResource.createPlane(1f, 1f)
    private val barMesh = MeshResource.createBox(Vector3(1f, 1f, 1f))
    private val maskBundle = AssetBundle.load("asset://monitor_occlusion.bundle")
    private val maskMaterial = ShaderGraphMaterial.loadFromAssetBundle(
        maskBundle, "monitor_occlusion/Root/MonitorMask/DefaultMaterial"
    ).apply {
        setBlendingMode(BlendingMode.OPAQUE)
        setDepthTest(true); setDepthWrite(true); setCullingMode(MaterialCullingMode.NONE)
    }
    private val guideMaterial = UnlitMaterial.create().apply { setBaseColor(Color4(.34f, .38f, .42f, 1f)) }
    private val physics = PhysicsMaterialResource()
    private val hitShape = ShapeResource.createBox(Vector3(1f, 1f, .006f))
    private val bars = List(4) { Entity() }
    private var last: MonitorCalibration? = null
    private var boundModel: Entity? = null

    init {
        anchor.setName("monitorAnchor"); plane.setName("monitorGrabProxy")
        owner.addChild(anchor); anchor.addChild(plane)
        plane.components.set(ModelComponent(planeMesh, maskMaterial))
        plane.components.set(InteractableComponent())
        plane.components.set(CollisionComponent(listOf(hitShape), physics))
        bars.forEach { it.components.set(ModelComponent(barMesh, guideMaterial)); anchor.addChild(it) }
        anchor.enabled = false
        Log.i("BlenderBridge", "MONITOR_MASK kind=occlusion-surface material=monitor_occlusion/Root/MonitorMask/DefaultMaterial blending=OPAQUE depthTest=true depthWrite=true culling=NONE sourceMaterials=unchanged visual=UNOBSERVED")
    }

    fun bindModel(model: Entity) {
        if (boundModel == model) return
        boundModel = model
        val materials = linkedSetOf<PhysicallyBasedMaterial>()
        var meshes = 0
        fun visit(entity: Entity) {
            entity.components[ModelComponent::class.java]?.let { visual ->
                meshes++
                visual.materials.forEach { if (it is PhysicallyBasedMaterial) materials.add(it) }
            }
            entity.getChildren().forEach(::visit)
        }
        visit(model)
        // The dedicated occlusion material owns the mask pass. Never force imported
        // opaque bodywork or glass into a different rendering pass/draw order.
        Log.i("BlenderBridge", "MONITOR_RENDER_CONTRACT meshes=$meshes pbrMaterials=${materials.size} blending=${materials.groupingBy { it.getBlendingMode() }.eachCount()} sourceMaterials=unchanged customDrawGroup=false")
    }

    fun unbindModel() { boundModel = null }

    fun update(value: MonitorCalibration) {
        if (last == value) return
        last = value
        anchor.enabled = value.enabled
        anchor.components[TransformComponent::class.java]!!.apply {
            position = value.position.vector()
            quaternion = value.quaternion.quat()
        }
        plane.components[TransformComponent::class.java]!!.scaleVector = Vector3(value.width, value.height, 1f)
        if (value.enabled) {
            val bounds = plane.getVisualBounds(anchor, false, true)
            Log.i("BlenderBridge", "MONITOR_MASK_BOUNDS owner=monitorAnchor min=${bounds.min} max=${bounds.max} width=${value.width} height=${value.height}")
        }
        val t = .004f
        bars.forEachIndexed { index, entity ->
            entity.enabled = value.editing
            entity.components[TransformComponent::class.java]!!.apply {
                scaleVector = if (index < 2) Vector3(value.width, t, t) else Vector3(t, value.height, t)
                position = when (index) {
                    0 -> Vector3(0f, value.height / 2f, .003f)
                    1 -> Vector3(0f, -value.height / 2f, .003f)
                    2 -> Vector3(value.width / 2f, 0f, .003f)
                    else -> Vector3(-value.width / 2f, 0f, .003f)
                }
            }
        }
    }
    override fun close() {
        anchor.destroy(true)
        hitShape.close(); physics.close(); planeMesh.close(); barMesh.close()
        unbindModel()
        maskMaterial.close(); maskBundle.close(); guideMaterial.close()
    }
}

internal fun List<Float>.vector() = Vector3(this[0], this[1], this[2])
internal fun List<Float>.quat() = Quat(this[0], this[1], this[2], this[3])
internal fun Vector3.values() = listOf(x, y, z)
internal fun Quat.values() = listOf(x, y, z, w)
