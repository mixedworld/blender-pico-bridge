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
    @Test(expected=IllegalArgumentException::class) fun crossedCropCannotWriteACamera() {
        build(listOf(0f,0f,1f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,.9f,1.8f,listOf(.8f,0f,.2f,1f))
    }
    @Test(expected=IllegalArgumentException::class) fun ViewerBehindTheWindowHasNoFabricatedCamera() {
        build(listOf(0f,0f,-1f),listOf(0f,0f,1f),listOf(0f,0f,0f,1f),1f,.9f,1.8f,listOf(0f,0f,1f,1f))
    }
}
