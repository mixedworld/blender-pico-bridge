package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.RigidPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BuildRenderBillboardUseCaseTest {
    private val build=BuildRenderBillboardUseCase()
    private fun box(c: List<Float>,half: Float)=List(8) { i -> List(3) { k -> c[k]+if(i and (1 shl k)==0)-half else half } }
    private fun local(fit: RenderBillboardFit,p: List<Float>): List<Float> {
        val frame=RigidPose.fromQuaternion(fit.position.map(Float::toDouble),fit.rotation.map(Float::toDouble)).inverse()
        return List(3) { row -> (frame.t[row]+(0..2).sumOf { k -> frame.r[row*3+k]*p[k] }).toFloat() }
    }
    @Test fun facesViewerWithFiniteRotationIncludingOverheadAndOppositeViews() {
        for(eye in listOf(listOf(1f,2f,3f),listOf(0f,2f,0f),listOf(0f,0f,-2f))) {
            val q=build.facing(eye,listOf(0f,0f,0f))!!
            val matrix=RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),q.map(Float::toDouble)).r
            val distance=sqrt(eye.sumOf { (it*it).toDouble() })
            for(k in 0..2)assertEquals(eye[k]/distance,matrix[k*3+2],1e-5)
        }
        assertNull(build.facing(listOf(0f,0f,0f),listOf(0f,0f,0f)))
    }
    @Test fun nearestBoundHasClearanceAndSmallModelsAreNotClampedToManualWindowMinimum() {
        val points=box(listOf(0f,0f,-2f),.1f)
        val fit=build.fit(listOf(0f,0f,0f),points,1.6f)!!
        assertEquals(-2f+sqrt(3f)*.1f+.03f,fit.position[2],.00001f)
        assertTrue(fit.width<.35f)
        assertTrue(points.all { local(fit,it)[2]<-.029f })
    }
    @Test fun everyCornerFitsForBothStereoEyesAtObliqueViewAndPortraitAspect() {
        val points=box(listOf(.6f,.2f,-2f),.4f)
        val head=listOf(-.3f,.7f,.8f)
        val eyes=listOf(listOf(-.335f,.7f,.8f),listOf(-.265f,.7f,.8f))
        for(aspect in listOf(.6f,1.8f)) {
            val fit=build.fit(head,points,aspect,eyes)!!
            for(eye in eyes) {
                val e=local(fit,eye)
                val camera=BuildRenderCameraUseCase()(e,e,fit.rotation,1f,fit.width,aspect,listOf(0f,0f,1f,1f))
                for(point in points) {
                    val p=local(fit,point);val z=e[2]-p[2]
                    val u=((p[0]-e[0])/z-camera.slopes[0])/(camera.slopes[1]-camera.slopes[0])
                    val v=((p[1]-e[1])/z-camera.slopes[2])/(camera.slopes[3]-camera.slopes[2])
                    assertTrue("Horizontal fit $u",u in .01f.. .99f)
                    assertTrue("Vertical fit $v",v in .01f.. .99f)
                }
            }
        }
    }
    @Test fun rectangleCentreLiesOnEnclosingSphereTowardsViewerAcrossDirections() {
        val centre=listOf(.8f,1.3f,-2f)
        val half=listOf(.6f,.2f,.1f)
        val points=List(8) { i -> List(3) { k -> centre[k]+if(i and (1 shl k)==0)-half[k] else half[k] } }
        val radius=sqrt(half.sumOf { (it*it).toDouble() }).toFloat()
        for(direction in listOf(listOf(0f,0f,1f),listOf(1f,0f,0f),listOf(.4f,.3f,.8660254f))) {
            val eye=List(3) { centre[it]+direction[it]*3f }
            val fit=build.fit(eye,points,1.6f)!!
            for(k in 0..2)assertEquals(centre[k]+direction[k]*(radius+.03f),fit.position[k],.00001f)
            assertTrue(points.all { local(fit,it)[2]<=-.02999f })
        }
    }
    @Test fun rotatingOrientedBoundsDoesNotInflateTheEnclosingSphere() {
        val centre=listOf(.2f,1f,-3f)
        val radius=sqrt(.8f*.8f+.2f*.2f+.1f*.1f)
        for(angle in listOf(0f,.4f,1.2f,2.4f)) {
            val points=List(8) { i ->
                val x=if(i and 1==0)-.8f else .8f
                val y=if(i and 2==0)-.2f else .2f
                val z=if(i and 4==0)-.1f else .1f
                listOf(centre[0]+x*cos(angle)+z*sin(angle),centre[1]+y,centre[2]-x*sin(angle)+z*cos(angle))
            }
            val eye=listOf(centre[0],centre[1],0f)
            val fit=build.fit(eye,points,1.6f)!!
            assertEquals(centre[2]+radius+.03f,fit.position[2],.00001f)
            assertTrue(points.all { local(fit,it)[2]<-.02999f })
        }
    }
    @Test fun enlargedAndMovedBoundsUpdatePoseAndResolutionCoverage() {
        val eye=listOf(0f,1f,1f)
        val small=build.fit(eye,box(listOf(0f,1f,-2f),.1f),1.6f)!!
        val large=build.fit(eye,box(listOf(.4f,1f,-3f),2f),1.6f)!!
        assertTrue(large.width>small.width)
        val largeEye=local(large,eye)
        BuildRenderCameraUseCase()(largeEye,largeEye,large.rotation,1f,large.width,1.6f,listOf(0f,0f,1f,1f))
        assertNotEquals(small.position,large.position)
        val repeat=build.fit(eye,box(listOf(.4f,1f,-3f),2f),1.6f)!!
        assertEquals(large,repeat)
    }
    @Test fun invalidInputsNeverProduceUnsafePlanes() {
        val points=box(listOf(0f,0f,0f),.2f)
        assertNull(build.fit(listOf(0f,0f,1f),emptyList(),1.6f))
        assertNull(build.fit(listOf(Float.NaN,0f,1f),points,1.6f))
        assertNull(build.fit(listOf(0f,0f,1f),points,0f))
    }
    @Test fun nearViewMayIntersectButPlaneNeverCrossesEitherStereoEye() {
        val points=box(listOf(0f,0f,-.3f),.2f)
        val eyes=listOf(listOf(-.032f,0f,-.02f),listOf(.032f,0f,.02f))
        val fit=build.fit(listOf(0f,0f,0f),points,1.6f,eyes)!!
        val depths=points.map { local(fit,it)[2] }
        assertTrue(depths.any { it>0 });assertTrue(depths.any { it<0 })
        for(eye in eyes+listOf(listOf(0f,0f,0f))) {
            val e=local(fit,eye)
            assertTrue("Both eyes retain 25 cm clearance",e[2]>=.24999f)
            val camera=BuildRenderCameraUseCase()(e,e,fit.rotation,1f,fit.width,1.6f,listOf(0f,0f,1f,1f))
            assertTrue(atan(camera.slopes[1])-atan(camera.slopes[0])<=2*PI/3+.00001)
            assertTrue(atan(camera.slopes[3])-atan(camera.slopes[2])<=2*PI/3+.00001)
        }
    }
    @Test fun crossingModelCentreKeepsFacingFiniteAndBoundsTheCloseUp() {
        val points=box(listOf(0f,0f,0f),1f)
        val previous=listOf(0f,0f,1f)
        for(z in listOf(.05f,.001f,0f,-.001f,-.05f)) {
            val fit=build.fit(listOf(0f,0f,z),points,.7f,previousNormal=previous)!!
            assertEquals(listOf(0f,0f,0f,1f),fit.rotation)
            val e=local(fit,listOf(0f,0f,z))
            assertEquals(.25f,e[2],.00001f)
            assertTrue(fit.width<=2f*e[2]*sqrt(3f)*.7f+.00001f)
            BuildRenderCameraUseCase()(e,e,fit.rotation,1f,fit.width,.7f,listOf(0f,0f,1f,1f))
        }
    }
    @Test fun approachingNearestExtentIsContinuousAtTheEyeDistanceClamp() {
        val points=box(listOf(0f,0f,-1f),.2f)
        val positions=(0..20).map { i ->
            val eye=listOf(0f,0f,-.5f-i*.005f)
            val fit=build.fit(eye,points,1.6f)!!
            assertTrue(local(fit,eye)[2]>=.24999f)
            fit.position[2]
        }
        positions.zipWithNext().forEach { (a,b) -> assertTrue(abs(a-b)<=.00501f) }
    }
    @Test fun farSmallBoundsRespectExistingCameraMinimumFrustumAndTenMetreDistance() {
        val fit=build.fit(listOf(0f,0f,0f),box(listOf(0f,0f,-30f),.001f),1.6f)!!
        val eye=local(fit,listOf(0f,0f,0f))
        assertTrue(eye[2]<=9.5f)
        BuildRenderCameraUseCase()(eye,eye,fit.rotation,1f,fit.width,1.6f,listOf(0f,0f,1f,1f))
    }
}
