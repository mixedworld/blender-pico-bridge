package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MonitorHandlesUseCase
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*

/** Main-thread guide owner. Resources are shared, retained once, then closed after the entities. */
internal class MonitorHandles(owner: Entity): AutoCloseable {
    private val root=Entity()
    private val handles=mutableListOf<Entity>()
    private val mesh=MeshResource.createBox(Vector3(.014f,.014f,.014f)).sceneOwned()
    private var hitShape: ShapeResource?=null
    private val physics=PhysicsMaterialResource().sceneOwned()
    private val cornerMaterial=UnlitMaterial.create().apply { setBaseColor(Color4(.1f,.85f,.9f,1f)) }.sceneOwned()
    private val curveMaterial=UnlitMaterial.create().apply { setBaseColor(Color4(1f,.65f,.1f,1f)) }.sceneOwned()
    private var active=false
    private var session=0
    init {
        root.setName("monitorAlignmentHandles");owner.addChild(root);root.enabled=false
    }
    fun index(entity: Entity?): Int?=handles.indexOf(entity).takeIf { active && it>=0 }
    fun update(draft: MonitorHandleDraft?,calibration: MonitorCalibration) {
        val enabled=draft!=null && calibration.enabled && calibration.editing && !calibration.locked
        if(enabled!=active || (enabled && handles.size!=5)) {
            // Retire old input registrations completely. Reopening must not reuse
            // disabled native pick targets or a gesture's previous target identity.
            handles.forEach { it.destroy(true) };handles.clear()
            hitShape?.close();hitShape=null
            active=false
            root.enabled=enabled
            if(enabled) {
                session++
                val shape=ShapeResource.createBox(Vector3(.036f,.036f,.036f)).sceneOwned().also { hitShape=it }
                try { repeat(5) { i ->
                    val entity=Entity();root.addChild(entity);handles+=entity
                    entity.setName(listOf("monitorTopLeft","monitorTopRight","monitorBottomRight","monitorBottomLeft","monitorTopCurve")[i])
                    entity.components.set(ModelComponent(mesh,if(i==4) curveMaterial else cornerMaterial))
                    entity.components.set(CollisionComponent(listOf(shape),physics))
                    entity.components.set(InteractableComponent())
                    entity.components.set(HoverEffectComponent())
                }
                active=true
                } catch(error: Exception) {
                    handles.forEach { it.destroy(true) };handles.clear()
                    hitShape?.close();hitShape=null;root.enabled=false
                    throw error
                }
            }
            Log.i("BlenderBridge","MONITOR_HANDLES active=$active session=$session count=${handles.size} freshTargets=$enabled owner=placementOwner")
        }
        root.enabled=enabled
        if(enabled) MonitorHandlesUseCase().positions(requireNotNull(draft),calibration).forEachIndexed { i,p ->
            handles[i].components[TransformComponent::class.java]!!.position=p.vector()
        }
    }
    override fun close() {
        root.destroy(true);handles.clear();hitShape?.close();hitShape=null;physics.close();mesh.close();cornerMaterial.close();curveMaterial.close()
    }
}
