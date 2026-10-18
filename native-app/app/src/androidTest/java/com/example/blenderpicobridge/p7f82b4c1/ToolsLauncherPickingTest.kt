package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.ToolsLauncherPicking
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.sceneOwned
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measured panel surrogate exercises SDK bounds/collision ownership, not physical hand input. */
@RunWith(AndroidJUnit4::class)
class ToolsLauncherPickingTest {
    @Test fun gripTracksMeasuredPanelAndRepeatedMeasurementDoesNotDuplicateTargets() {
        var failure: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val owner=Entity();val panel=Entity();owner.addChild(panel)
            val mesh=MeshResource.createBox(Vector3(.184f,.144f,.001f)).sceneOwned()
            val material=UnlitMaterial.create().sceneOwned()
            panel.components.set(ModelComponent(mesh,material))
            val picking=ToolsLauncherPicking(panel)
            try {
                picking.measure()
                val target=panel.getChildren().single()
                assertTrue(picking.owns(target));assertFalse(picking.owns(panel))
                assertNotNull(target.components[CollisionComponent::class.java])
                val before=target.components[TransformComponent::class.java]!!.position
                assertTrue(before.y<-.03f)
                repeat(20){picking.measure();assertEquals(listOf(target),panel.getChildren().toList())}
                panel.components[TransformComponent::class.java]!!.position=Vector3(.5f,1f,-1f)
                assertEquals(before,target.components[TransformComponent::class.java]!!.position)
                assertEquals(.5f,target.convertPositionTo(Vector3.ZERO,owner).x,.0001f)
            } catch(error: Throwable){failure=error}
            finally {picking.close();owner.destroy(true);mesh.close();material.close()}
        }
        failure?.let{throw AssertionError("Tools grip SDK check failed",it)}
    }
}
