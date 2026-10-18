package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3

/** Only the attachment's bottom grip is spatially draggable; the Tools button keeps its click. */
internal class ToolsLauncherPicking(private val attachment: Entity): AutoCloseable {
    private val physics=PhysicsMaterialResource().sceneOwned()
    private var shape: ShapeResource?=null
    private var target: Entity?=null
    private var dimensions: Pair<Float,Float>?=null
    fun measure() {
        val bounds=attachment.getVisualBounds(attachment,true,true)
        val width=bounds.halfExtent.x*2f;val height=bounds.halfExtent.y*2f
        if(!width.isFinite() || !height.isFinite() || width<=.001f || height<=.001f)return
        val next=width to height
        if(dimensions==next)return
        target?.destroy(true);target=null;shape?.close()
        // The lower 64 dp content of the 144 dp attachment are the labelled grip.
        val gripHeight=height*(64f/144f)
        shape=ShapeResource.createBox(Vector3(width,gripHeight,.025f)).sceneOwned()
        target=Entity().also {
            attachment.addChild(it);it.setName("toolsLauncherGrip")
            it.components[TransformComponent::class.java]!!.position=Vector3(bounds.center.x,bounds.center.y-height/2f+height*(8f/144f)+gripHeight/2f,.012f)
            it.components.set(CollisionComponent(listOf(requireNotNull(shape)),physics))
            it.components.set(InteractableComponent())
        }
        dimensions=next
    }
    fun owns(entity: Entity)=entity==target
    override fun close(){target?.destroy(true);target=null;shape?.close();shape=null;physics.close()}
}
