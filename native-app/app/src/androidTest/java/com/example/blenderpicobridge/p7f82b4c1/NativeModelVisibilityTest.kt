package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.NativeModelVisibility
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.sceneOwned
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeModelVisibilityTest {
    @Test fun renderOnlyHidingKeepsBoundsPickingTransformsAndReplacement() {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { verifyVisibility() } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw AssertionError("Native visibility check failed", it) }
    }

    private fun verifyVisibility() {
        val owner = Entity()
        val model = Entity(); owner.addChild(model)
        val child = Entity(); model.addChild(child)
        val authoredHidden = Entity(); model.addChild(authoredHidden)
        val replacement = Entity(); owner.addChild(replacement)
        val unrelatedVideo = Entity(); owner.addChild(unrelatedVideo)
        val grabProxy = Entity(); owner.addChild(grabProxy)
        val mesh = MeshResource.createBox(Vector3(.3f, .2f, .1f)).sceneOwned()
        val material = UnlitMaterial.create().sceneOwned()
        val shape = ShapeResource.createBox(Vector3(.3f, .2f, .1f)).sceneOwned()
        val physics = PhysicsMaterialResource().sceneOwned()
        val visibility = NativeModelVisibility()
        try {
            listOf(child, authoredHidden, replacement, unrelatedVideo).forEach {
                it.components.set(ModelComponent(mesh, material))
            }
            authoredHidden.components[ModelComponent::class.java]!!.isVisible = false
            grabProxy.components.set(CollisionComponent(listOf(shape), physics))
            grabProxy.components.set(InteractableComponent())
            val bounds = model.getVisualBounds(owner, true, true)
            assertFalse(bounds.isEmpty())
            visibility.bind(model)
            repeat(3) {
                visibility.setVisible(false)
                assertFalse(child.components[ModelComponent::class.java]!!.isVisible)
                assertTrue(model.enabled); assertTrue(child.enabled); assertTrue(grabProxy.enabled)
                assertNotNull(grabProxy.components[CollisionComponent::class.java])
                assertNotNull(grabProxy.components[InteractableComponent::class.java])
                val hiddenBounds = model.getVisualBounds(owner, true, true)
                assertFalse(hiddenBounds.isEmpty())
                assertEquals(bounds.halfExtent.x, hiddenBounds.halfExtent.x, .0001f)
                // Animation/explosion and carrier movement remain ordinary transform updates.
                owner.components[TransformComponent::class.java]!!.position = Vector3(.1f, .2f, .3f)
                child.components[TransformComponent::class.java]!!.position = Vector3(.04f, 0f, 0f)
                assertFalse(model.getVisualBounds(owner, true, true).isEmpty())
                assertTrue(unrelatedVideo.components[ModelComponent::class.java]!!.isVisible)
                visibility.setVisible(true)
                assertTrue(child.components[ModelComponent::class.java]!!.isVisible)
                assertFalse(authoredHidden.components[ModelComponent::class.java]!!.isVisible)
                child.components[TransformComponent::class.java]!!.position = Vector3.ZERO
            }
            visibility.setVisible(false)
            visibility.bind(replacement)
            assertFalse(replacement.components[ModelComponent::class.java]!!.isVisible)
            visibility.setVisible(true)
            assertTrue(replacement.components[ModelComponent::class.java]!!.isVisible)
        } finally {
            visibility.clear(); owner.destroy(true)
            mesh.close(); material.close(); shape.close(); physics.close()
        }
    }
}
