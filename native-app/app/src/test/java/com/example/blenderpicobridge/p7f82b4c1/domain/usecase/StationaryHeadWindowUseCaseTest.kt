package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.RigidPose
import org.junit.Assert.*
import org.junit.Test

class StationaryHeadWindowUseCaseTest {
    private val pose=RigidPose.fromQuaternion(listOf(.2,1.6,-.3),listOf(0.0,0.0,0.0,1.0))
    @Test fun stableFreshBracketWorksButStaleDataDoesNot() {
        val window=StationaryHeadWindowUseCase()
        for(t in 0L..1100L step 10) window.add(t,pose)
        assertNotNull(window.steady(1080,1110))
        assertNull(window.steady(1300,1320))
    }
    @Test fun movementAndGapsCannotMasqueradeAsASteadyCapture() {
        val window=StationaryHeadWindowUseCase()
        for(t in 0L..1100L step 10) window.add(t,pose.copy(t=listOf(t*.0001,1.6,-.3)))
        assertNull(window.steady(1080,1110))
        window.add(1500,pose)
        assertNull(window.steady(1500,1510))
    }
    @Test fun rigidTransformCompositionPreservesFramesAndQuaternionSign() {
        val rotated=RigidPose.fromQuaternion(listOf(.1,.2,.3),listOf(.5,.5,.5,.5))
        val identity=rotated.inverse()*rotated
        assertTrue(identity.distance(RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),listOf(0.0,0.0,0.0,1.0)))<1e-9)
        val result=rotated.inverse()*(rotated*pose)
        assertTrue(pose.distance(result)<1e-9);assertTrue(pose.angle(result)<1e-7)
    }
}
