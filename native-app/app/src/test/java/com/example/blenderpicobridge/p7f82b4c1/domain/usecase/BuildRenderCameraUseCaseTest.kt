package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import org.junit.Assert.*
import org.junit.Test

class BuildRenderCameraUseCaseTest {
    private val build=BuildRenderCameraUseCase()
    @Test fun sourceCropEdgesProjectToTheVirtualWindowEdges() {
        val crop=listOf(.04f,.06f,.78f,.94f)
        val frame=build(listOf(.1f,-.05f,1.2f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),.2f,.9f,1.6f,crop)
        val (l,r,b,t)=frame.slopes
        assertEquals((-.45f-.1f)/1.2f,l+(r-l)*crop[0],1e-5f)
        assertEquals((.45f-.1f)/1.2f,l+(r-l)*crop[2],1e-5f)
        assertEquals((-.9f/1.6f/2+.05f)/1.2f,b+(t-b)*crop[1],1e-5f)
        assertEquals((.9f/1.6f/2+.05f)/1.2f,b+(t-b)*crop[3],1e-5f)
        assertEquals(.025f,frame.near,1e-6f)
    }
    @Test fun rigidWindowScalingPreservesItsPerspectiveRatios() {
        val a=build(listOf(.1f,0f,1f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,.9f,1.8f,listOf(0f,0f,1f,1f))
        val b=build(listOf(.2f,0f,2f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,1.8f,1.8f,listOf(0f,0f,1f,1f))
        a.slopes.zip(b.slopes).forEach { (x,y) -> assertEquals(x,y,1e-6f) }
    }
    @Test fun stereoWindowPlaneCoincidesAndDepthHasCorrectDisparity() {
        val pair=listOf(-.032f,.032f).map { x -> build(listOf(x,0f,1f),listOf(x,0f,1f),
            listOf(0f,0f,0f,1f),1f,.9f,1.5f,listOf(0f,0f,1f,1f)) }
        fun u(eye: Int,x: Float,z: Float): Float {
            val camera=pair[eye];val slope=(x-camera.position[0])/(1f-z)
            return (slope-camera.slopes[0])/(camera.slopes[1]-camera.slopes[0])
        }
        for(x in listOf(-.45f,0f,.45f)) assertEquals(u(0,x,0f),u(1,x,0f),1e-6f)
        assertTrue("Front of window needs crossed disparity",u(0,0f,.2f)>u(1,0f,.2f))
        assertTrue("Behind window needs uncrossed disparity",u(0,0f,-.2f)<u(1,0f,-.2f))
        assertEquals(.064f,pair[1].position[0]-pair[0].position[0],1e-6f)
    }
    @Test(expected=IllegalArgumentException::class) fun crossedCropCannotWriteACamera() {
        build(listOf(0f,0f,1f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,.9f,1.8f,listOf(.8f,0f,.2f,1f))
    }
    @Test(expected=IllegalArgumentException::class) fun ViewerBehindTheWindowHasNoFabricatedCamera() {
        build(listOf(0f,0f,-1f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,.9f,1.8f,listOf(0f,0f,1f,1f))
    }
}
