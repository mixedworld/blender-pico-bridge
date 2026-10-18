package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.data.repository.RenderVideoRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ReceiveRenderVideoUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.RenderWindowScene
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.sceneOwned
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual production frame targets and resource lifetimes, without a video/network session. */
@RunWith(AndroidJUnit4::class)
class RenderFramePickingTest {
    @Test fun centrePassesThroughAndTenLockCyclesRetainVideoPoseAndModelPicking() {
        var failure: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { verifyFrame() } catch(t: Throwable) { failure=t }
        }
        failure?.let { throw AssertionError("Frame picking check failed",it) }
    }
    private fun verifyFrame() {
        val owner=Entity()
        var opens=0;var closes=0
        val receive=ReceiveRenderVideoUseCase(object: RenderVideoRepository {
            override fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit): AutoCloseable {
                opens++;return AutoCloseable { closes++ }
            }
        })
        val window=RenderWindowScene(owner,16f/9f,receive) {}
        val modelProxy=Entity();owner.addChild(modelProxy)
        val physics=PhysicsMaterialResource().sceneOwned()
        val shape=ShapeResource.createBox(Vector3(.2f,.2f,.2f)).sceneOwned()
        modelProxy.components.set(CollisionComponent(listOf(shape),physics))
        modelProxy.components.set(InteractableComponent())
        val calibration=MonitorCalibration(enabled=true,locked=true)
        var state=RenderWindowState(enabled=true)
        val pair=Pairing("http://127.0.0.1:8787","fixture-token")
        fun targets()=window.anchor.getChildren().filter { it.components[CollisionComponent::class.java]!=null }
        fun update()=window.update(state,pair,calibration,null,null)
        try {
            update()
            val savedPose=window.pose()
            repeat(10) {
                val borders=targets()
                assertEquals(4,borders.size)
                assertEquals(1,opens);assertEquals(0,closes)
                assertFalse(window.owns(window.anchor))
                borders.forEach { border ->
                    assertTrue(window.owns(border))
                    assertNotNull(border.components[InteractableComponent::class.java])
                    val p=border.components[TransformComponent::class.java]!!.position
                    // Targets lie on the perimeter; no centre entity can intercept the model.
                    assertTrue(kotlin.math.abs(p.x)>.4f || kotlin.math.abs(p.y)>.24f)
                }
                window.anchor.getChildren().filter { it !in borders }.forEach {
                    assertNull(it.components[CollisionComponent::class.java])
                    assertNull(it.components[InteractableComponent::class.java])
                }
                state=state.copy(locked=true);update()
                assertTrue(targets().isEmpty())
                assertTrue(borders.none(window::owns))
                window.manipulate(Vector3(.2f,.1f,.1f),Quat(),1.2f)
                assertEquals(savedPose,window.pose())
                assertTrue(modelProxy.enabled)
                assertNotNull(modelProxy.components[CollisionComponent::class.java])
                assertNotNull(modelProxy.components[InteractableComponent::class.java])
                state=state.copy(locked=false);update()
                assertEquals(4,targets().size)
                assertTrue(targets().none { it in borders })
            }
            window.manipulate(Vector3(.1f,0f,0f),Quat(),1.1f)
            assertNotEquals(savedPose,window.pose())
            window.setActive(false);assertTrue(targets().isEmpty())
            window.setActive(true);assertEquals(4,targets().size)
            state=state.copy(enabled=false);update();assertTrue(targets().isEmpty())
        } finally {
            window.close();owner.destroy(true);shape.close();physics.close()
        }
        assertEquals(opens,closes)
    }
}
