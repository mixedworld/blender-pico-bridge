package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MonitorHandlesUseCaseTest {
    private val solve=MonitorHandlesUseCase()
    private val screen=MonitorCalibration(enabled=true,editing=true,width=.8f,height=.4f,position=listOf(.2f,1.3f,-1.2f),
        quaternion=listOf(0f,sin(.3f),0f,cos(.3f)))
    @Test fun flatAndCurvedRotatedFramesRoundTripInOwnerSpace() {
        for(angle in listOf(0f,10f,60f,120f)) {
            val source=screen.copy(curveDegrees=angle)
            val result=solve.fit(solve.from(source),source)
            assertEquals(source.width,result.width,1e-5f);assertEquals(source.height,result.height,1e-5f)
            assertEquals(angle,result.curveDegrees,1e-3f)
            CurvedScreenUseCase(result.width,result.height,result.curveDegrees)
            for(i in 0..2) assertEquals(source.position[i],result.position[i],1e-5f)
            val a=RigidPose.fromQuaternion(source.position.map(Float::toDouble),source.quaternion.map(Float::toDouble))
            val b=RigidPose.fromQuaternion(result.position.map(Float::toDouble),result.quaternion.map(Float::toDouble))
            assertEquals(0.0,a.angle(b),1e-5)
        }
    }
    @Test fun cornerMovementDoesNotMoveTheOtherCorners() {
        val before=solve.from(screen);val after=solve.move(before,0,listOf(.04f,.02f,-.01f))
        for(i in 1..3) assertEquals(before.corners[i],after.corners[i])
        assertEquals(before.corners[0][0]+.04f,after.corners[0][0],1e-6f)
        assertEquals(before.sagitta,after.sagitta,0f)
    }
    @Test fun curvatureUsesDepthOnlyAndLeavesCornersFixed() {
        val flat=screen.copy(position=listOf(0f,0f,0f),quaternion=listOf(0f,0f,0f,1f))
        val draft=solve.from(flat)
        val moved=solve.move(draft,4,listOf(.2f,.1f,-.05f))
        assertEquals(draft.corners,moved.corners)
        val fit=solve.fit(moved,flat)
        assertEquals(4*atan(.125f)*180/PI.toFloat(),fit.curveDegrees,1e-4f)
        val top=solve.positions(moved,fit)[4]
        assertEquals(0f,top[0],1e-6f);assertEquals(.2f,top[1],1e-6f);assertEquals(-.05f,top[2],1e-6f)
        val restored=solve.move(moved,4,listOf(0f,0f,.1f))
        assertEquals(0f,restored.sagitta,0f)
    }
    @Test(expected=IllegalArgumentException::class) fun twistedCornersCannotBeLocked() {
        val draft=solve.move(solve.from(screen),0,listOf(0f,.12f,.15f));solve.fit(draft,screen)
    }
    @Test(expected=IllegalArgumentException::class) fun crossedCornersCannotBeLocked() {
        val d=solve.from(screen);solve.fit(d.copy(corners=listOf(d.corners[0],d.corners[2],d.corners[1],d.corners[3])),screen)
    }
    @Test(expected=IllegalArgumentException::class) fun unsupportedCurvatureIsRejected() {
        solve.move(solve.from(screen),4,listOf(0f,0f,-2f))
    }
    @Test fun badIntermediateGeometryStillProvidesFiveMovableGuides() {
        val draft=solve.from(screen)
        val collapsed=draft.copy(corners=List(4) { draft.corners.first() })
        assertEquals(5,solve.positions(collapsed,screen).size)
        assertTrue(solve.positions(collapsed,screen).flatten().all(Float::isFinite))
    }
    @Test fun rejectedFitStillDrawsEveryOutlineEndpointAtItsRawHandle() {
        val draft=solve.move(solve.from(screen),0,listOf(.15f,.2f,.12f))
        assertTrue(runCatching { solve.fit(draft,screen) }.isFailure)
        val lines=solve.outline(draft,screen)
        assertEquals(66,lines.size)
        assertEquals(draft.corners[0],lines[0].first)
        assertEquals(draft.corners[1],lines[62].second)
        assertEquals(draft.corners[2],lines[63].second)
        assertEquals(draft.corners[3],lines[1].first)
        assertEquals(draft.corners[0] to draft.corners[3],lines[64])
        assertEquals(draft.corners[1] to draft.corners[2],lines[65])
        val crown=solve.positions(draft,screen)[4]
        for(i in 0..2) assertEquals(crown[i],lines[30].second[i],1e-6f)
    }
    @Test fun aMovingInvalidCornerMovesTheOutlineOnEveryStep() {
        var draft=solve.from(screen)
        repeat(20) {
            draft=solve.move(draft,2,listOf(.01f,-.02f,.003f))
            val edge=solve.outline(draft,screen)[63].second
            for(i in 0..2) assertEquals(draft.corners[2][i],edge[i],1e-6f)
        }
        val collapsed=draft.copy(corners=List(4) { draft.corners[0] })
        assertTrue(solve.outline(collapsed,screen).flatMap { it.first+it.second }.all(Float::isFinite))
    }
}
