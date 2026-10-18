package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.domain.model.MonitorCalibration
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.MonitorHandlesUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.MonitorHandles
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.sceneOwned
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes actual SDK lifetime operations on-device, without camera data or user gestures. */
@RunWith(AndroidJUnit4::class)
class SpatialResourceLifecycleTest {
    private fun onMain(block: ()->Unit) {
        var failure: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch(t: Throwable) { failure=t } }
        failure?.let { throw AssertionError("Native resource lifetime check failed",it) }
    }
    @Test fun productionHandlesSurviveTenLockReopenCycles()=onMain {
        val owner=Entity()
        val guides=MonitorHandles(owner)
        val calibration=MonitorCalibration(enabled=true,editing=true,width=.8f,height=.4f)
        val draft=MonitorHandlesUseCase().from(calibration)
        try {
            repeat(10) {
                guides.update(draft,calibration)
                assertEquals(5,owner.getChildren().single().getChildren().size)
                guides.update(null,calibration.copy(editing=false,locked=true))
                assertTrue(owner.getChildren().single().getChildren().isEmpty())
            }
            guides.update(draft,calibration)
            assertEquals(5,owner.getChildren().single().getChildren().size)
        } finally { guides.close();owner.destroy(true) }
    }
    @Test fun retainedCollisionResourcesSurviveRemovalAndModelReplacement()=onMain {
        val physics=PhysicsMaterialResource().sceneOwned()
        val shape=ShapeResource.createBox(Vector3(.3f,.2f,.1f)).sceneOwned()
        val entity=Entity()
        try {
            repeat(10) {
                entity.components.set(CollisionComponent(listOf(shape),physics))
                entity.components.remove(CollisionComponent::class.java)
                assertTrue(physics.valid);assertTrue(shape.valid)
                val replacement=Entity()
                replacement.components.set(CollisionComponent(listOf(shape),physics))
                replacement.destroy(true)
                assertTrue(physics.valid);assertTrue(shape.valid)
            }
            entity.components.set(CollisionComponent(listOf(shape),physics))
        } finally { entity.destroy(true);shape.close();physics.close() }
    }
}
