package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3

/** Border-only pick targets. The image interior never has a collider. Main thread only. */
internal class RenderFramePicking(private val anchor: Entity, width: Float, height: Float): AutoCloseable {
    private val thickness = .036f
    private val physics = PhysicsMaterialResource().sceneOwned()
    private val horizontal = ShapeResource.createBox(Vector3(width + thickness, thickness, thickness)).sceneOwned()
    private val vertical = ShapeResource.createBox(Vector3(thickness, height + thickness, thickness)).sceneOwned()
    private val positions = listOf(Vector3(0f,height/2,.004f),Vector3(0f,-height/2,.004f),
        Vector3(-width/2,0f,.004f),Vector3(width/2,0f,.004f))
    private val targets = mutableListOf<Entity>()
    private var active = false

    fun setInteractive(value: Boolean) {
        if (active == value) return
        // Fresh targets avoid stale SDK input registrations after lock/unlock or app hiding.
        targets.forEach { it.destroy(true) }; targets.clear(); active = false
        if (!value) return
        try {
            positions.forEachIndexed { i,position ->
                val target = Entity(); anchor.addChild(target); targets.add(target)
                target.setName("renderFrameBorder$i")
                target.components[TransformComponent::class.java]!!.position = position
                target.components.set(CollisionComponent(listOf(if(i<2) horizontal else vertical),physics))
                target.components.set(InteractableComponent())
            }
            active = true
        } catch (error: Exception) {
            targets.forEach { it.destroy(true) }; targets.clear()
            throw error
        }
    }

    fun owns(entity: Entity) = active && targets.contains(entity)

    override fun close() {
        setInteractive(false)
        horizontal.close(); vertical.close(); physics.close()
    }
}
