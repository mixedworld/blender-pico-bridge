package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.util.Log
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import com.example.blenderpicobridge.p7f82b4c1.domain.model.MonitorHandleDraft
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MonitorHandlesUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
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
internal class MonitorThreshold(private val owner: Entity) : AutoCloseable {
    val anchor = Entity()
    val plane = Entity()
    private val planeMesh = MeshResource.createPlane(1f, 1f).sceneOwned()
    private val barMesh = MeshResource.createBox(Vector3(1f, 1f, 1f)).sceneOwned()
    private val maskBundle = AssetBundle.load("asset://monitor_occlusion.bundle")
    private val maskMaterial = ShaderGraphMaterial.loadFromAssetBundle(
        maskBundle, "monitor_occlusion/Root/MonitorMask/DefaultMaterial"
    ).apply {
        setBlendingMode(BlendingMode.OPAQUE)
        setDepthTest(true); setDepthWrite(true); setCullingMode(MaterialCullingMode.NONE)
    }.sceneOwned()
    private val guideMaterial = UnlitMaterial.create().apply { setBaseColor(Color4(.34f, .38f, .42f, 1f)) }.sceneOwned()
    private val physics = PhysicsMaterialResource().sceneOwned()
    private val hitShape = ShapeResource.createBox(Vector3(1f, 1f, .006f)).sceneOwned()
    private val strips = List(32) { Entity() }
    private val bars = List(66) { Entity() }
    private var last: MonitorCalibration? = null
    private var boundModel: Entity? = null
    private var handlesEditing=false
    private var lastDraft: MonitorHandleDraft?=null

    init {
        anchor.setName("monitorAnchor"); plane.setName("monitorGrabProxy")
        owner.addChild(anchor); anchor.addChild(plane)
        strips.forEachIndexed { i, strip ->
            strip.setName("monitorMaskStrip$i")
            strip.components.set(ModelComponent(planeMesh,maskMaterial))
            plane.addChild(strip)
        }
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

    fun update(value: MonitorCalibration,manualHandles: Boolean=false,draft: MonitorHandleDraft?=null) {
        if (last == value && handlesEditing==manualHandles && lastDraft==draft) return
        lastDraft=draft
        val wasEditable=last?.let { it.enabled && it.editing && !it.locked && !handlesEditing }
        val editable=value.enabled && value.editing && !value.locked && !manualHandles
        handlesEditing=manualHandles
        last = value
        anchor.enabled = value.enabled
        anchor.components[TransformComponent::class.java]!!.apply {
            position = value.position.vector()
            quaternion = value.quaternion.quat()
        }
        val surface=CurvedScreenUseCase(value.width,value.height,value.curveDegrees)
        val segments=surface.strips(32)
        strips.forEachIndexed { index,entity ->
            val segment=segments.getOrNull(index)
            entity.enabled=segment!=null && !manualHandles
            // A depth-writing surface is still a ray blocker if it retains a collider.
            // Keep picking only while aligning; locked masks must let the existing
            // model grab collider receive gaze/pinch even when its pixels are hidden.
            if(editable && segment!=null) {
                if(entity.components[CollisionComponent::class.java]==null)
                    entity.components.set(CollisionComponent(listOf(hitShape),physics))
                if(entity.components[InteractableComponent::class.java]==null)
                    entity.components.set(InteractableComponent())
            } else {
                entity.components.remove(InteractableComponent::class.java)
                entity.components.remove(CollisionComponent::class.java)
            }
            if (segment!=null) entity.components[TransformComponent::class.java]!!.apply {
                position=Vector3(segment.x,0f,segment.z)
                quaternion=Quat(0f,sin(segment.yaw/2),0f,cos(segment.yaw/2))
                scaleVector=Vector3(segment.length,value.height,1f)
            }
        }
        val t=.004f
        val draftEdges=if(manualHandles && draft!=null) MonitorHandlesUseCase().outline(draft,value) else null
        bars.forEachIndexed { index,entity ->
            if(draftEdges!=null) {
                val (from,to)=draftEdges[index]
                // Draft coordinates belong to placementOwner; bars belong to monitorAnchor.
                val a=anchor.convertPositionFrom(from.vector(),owner)
                val b=anchor.convertPositionFrom(to.vector(),owner)
                val delta=b-a
                val length=sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z)
                entity.enabled=value.editing && length>1e-6f
                if(entity.enabled) entity.components[TransformComponent::class.java]!!.apply {
                    val x=delta.x/length;val y=delta.y/length;val z=delta.z/length
                    val w=1+x
                    quaternion=if(w<1e-5f) Quat(0f,1f,0f,0f) else {
                        val n=1/sqrt(w*w+y*y+z*z)
                        Quat(0f,-z*n,y*n,w*n)
                    }
                    position=(a+b)*.5f
                    scaleVector=Vector3(length,t,t)
                }
                return@forEachIndexed
            }
            val edge=index>=64
            val segment=segments.getOrNull(index/2)
            entity.enabled=value.editing && (edge || segment!=null)
            if (edge) entity.components[TransformComponent::class.java]!!.apply {
                scaleVector=Vector3(t,value.height,t)
                position=Vector3((if(index==64) -1 else 1)*surface.chord/2,0f,surface.edgeDepth+.003f)
                quaternion=Quat()
            } else if (segment!=null) entity.components[TransformComponent::class.java]!!.apply {
                scaleVector=Vector3(segment.length,t,t)
                position=Vector3(segment.x+sin(segment.yaw)*.003f,(if(index%2==0) 1 else -1)*value.height/2,segment.z+cos(segment.yaw)*.003f)
                quaternion=Quat(0f,sin(segment.yaw/2),0f,cos(segment.yaw/2))
            }
        }
        if(wasEditable!=editable) Log.i("BlenderBridge", "MONITOR_PICKING editing=$editable maskColliders=${strips.count { it.components[CollisionComponent::class.java]!=null }} renderOcclusion=${!manualHandles} modelGrabCollider=unchanged")
        if (value.enabled && !manualHandles) {
            val bounds=plane.getVisualBounds(anchor,true,true)
            Log.i("BlenderBridge","MONITOR_CURVE owner=monitorAnchor arc=${value.width} chord=${surface.chord} height=${value.height} degrees=${value.curveDegrees} radius=${surface.radius} segments=${segments.size} min=${bounds.min} max=${bounds.max} visual=UNOBSERVED")
        }
    }
    fun owns(entity: Entity?): Boolean {
        var current: Entity?=entity
        while(current!=null) { if(current==anchor) return true;current=current.getParent() }
        return false
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
