package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class BuildMonitorCameraUseCaseTest {
    private val screen=MonitorCalibration(width=.64f,height=.36f)
    private val rotation=listOf(0f,0f,0f,1f)
    @Test fun offCenterEyeProjectsPhysicalScreenCornersToImageCorners() {
        val eye=listOf(.12f,-.03f,.8f)
        val frame=BuildMonitorCameraUseCase()(eye,listOf(0f,0f,8f),rotation,.1f,screen,true)
        val (l,r,b,t)=frame.slopes
        fun project(x: Float,y: Float)=listOf(((x-eye[0])/eye[2]-l)/(r-l),((y-eye[1])/eye[2]-b)/(t-b))
        val low=project(-.32f,-.18f);val high=project(.32f,.18f)
        assertEquals(0f,low[0],1e-6f);assertEquals(0f,low[1],1e-6f)
        assertEquals(1f,high[0],1e-6f);assertEquals(1f,high[1],1e-6f)
        assertEquals(8f,frame.near,1e-5f)
    }
    @Test fun nearerHeadWidensProjectionAndSourceScaleChangesClipUnits() {
        val solve=BuildMonitorCameraUseCase()
        val far=solve(listOf(0f,0f,1f),listOf(0f,0f,1f),rotation,1f,screen,true)
        val close=solve(listOf(0f,0f,.5f),listOf(0f,0f,5f),rotation,.1f,screen,true)
        assertEquals(far.slopes[1]*2,close.slopes[1],1e-6f)
        assertEquals(5f,close.near,1e-6f)
        val visible=solve(listOf(0f,0f,.5f),listOf(0f,0f,5f),rotation,.1f,screen,false)
        assertEquals(.05f,visible.near,1e-6f)
    }
    @Test fun inverseModelMotionKeepsTheNativeAndBlenderProjectionConsistent() {
        val model=RigidPose.fromQuaternion(listOf(.2,.1,-.3),listOf(0.0,.382683432,0.0,.923879533))
        val eye=RigidPose.fromQuaternion(listOf(.1,.05,.8),listOf(0.0,0.0,0.0,1.0))
        val sourceCamera=model.inverse()*eye
        val sourcePoint=(model.inverse()*RigidPose.fromQuaternion(listOf(.22,-.1,-.15),listOf(0.0,0.0,0.0,1.0))).t
        val inBlender=(sourceCamera.inverse()*RigidPose.fromQuaternion(sourcePoint,listOf(0.0,0.0,0.0,1.0))).t
        val inNative=(eye.inverse()*RigidPose.fromQuaternion(listOf(.22,-.1,-.15),listOf(0.0,0.0,0.0,1.0))).t
        assertEquals(inNative[0]/-inNative[2],inBlender[0]/-inBlender[2],1e-8)
        assertEquals(inNative[1]/-inNative[2],inBlender[1]/-inBlender[2],1e-8)
    }
    @Test(expected=IllegalArgumentException::class) fun eyeBehindMonitorCannotDriveCamera() {
        BuildMonitorCameraUseCase()(listOf(0f,0f,-.1f),listOf(0f,0f,0f),rotation,1f,screen,true)
    }
}
