package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AlignmentSpaceUseCaseTest {
    private val space=AlignmentSpaceUseCase()
    private val screen=MonitorCalibration(enabled=true,locked=true,width=.8f,height=.4f,curveDegrees=35f,
        position=listOf(.3f,1.2f,-1.4f),quaternion=listOf(0f,sin(.2f),0f,cos(.2f)))
    private val snapshot=AlignmentSnapshot(screen,MonitorHandlesUseCase().from(screen),
        InspectionPose(listOf(.25f,1.1f,-1.6f),listOf(sin(.15f),0f,0f,cos(.15f)),.7f,.053f,listOf(-.4f,.2f,.5f)),"revision")
    private val anchor=RigidPose.fromQuaternion(listOf(.31,1.19,-1.41),listOf(.1,.2,.3,.9))
    private fun same(a: List<Float>,b: List<Float>) { assertEquals(a.size,b.size);a.indices.forEach { assertEquals(a[it],b[it],1e-5f) } }
    @Test fun wholeCurvedFrameAndFitSurviveSaveAndRestore() {
        val relative=space(snapshot,anchor.inverse())
        val restored=space(relative,anchor)
        same(snapshot.monitor.position,restored.monitor.position)
        assertEquals(0.0,RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),snapshot.monitor.quaternion.map(Float::toDouble))
            .angle(RigidPose.fromQuaternion(listOf(0.0,0.0,0.0),restored.monitor.quaternion.map(Float::toDouble))),1e-6)
        snapshot.handles.corners.zip(restored.handles.corners).forEach { (a,b) -> same(a,b) }
        assertEquals(snapshot.handles.sagitta,restored.handles.sagitta,0f)
        assertEquals(screen.width,restored.monitor.width,0f);assertEquals(screen.height,restored.monitor.height,0f)
        assertEquals(screen.curveDegrees,restored.monitor.curveDegrees,0f)
        same(snapshot.stagedPose!!.position,restored.stagedPose!!.position)
        assertEquals(snapshot.stagedPose.fitScale,restored.stagedPose.fitScale,0f)
        assertEquals(snapshot.stagedPose.fitPosition,restored.stagedPose.fitPosition)
        assertEquals(snapshot.stagedPose.scale,restored.stagedPose.scale,0f)
    }
    @Test fun newStageOriginRelocatesEveryCornerAndCrownTogether() {
        val next=RigidPose.fromQuaternion(listOf(2.0,.4,-.7),listOf(0.0,sin(.4),0.0,cos(.4)))
        val relative=space(snapshot,anchor.inverse())
        val restored=space(relative,next)
        val delta=next*anchor.inverse()
        val positions=MonitorHandlesUseCase().positions(snapshot.handles,snapshot.monitor)
        val result=MonitorHandlesUseCase().positions(restored.handles,restored.monitor)
        positions.zip(result).forEach { (a,b) -> same(space.point(a,delta),b) }
        same(space.inspection(snapshot.stagedPose!!,delta).position,restored.stagedPose!!.position)
    }
    @Test fun exactNonrectangularCornerResidualsAreNotLostToARegeneratedRectangle() {
        val original=snapshot.copy(handles=snapshot.handles.copy(corners=snapshot.handles.corners.mapIndexed { i,p ->
            if(i==0) p.mapIndexed { k,v -> if(k==1) v+.002f else v } else p }))
        val restored=space(space(original,anchor.inverse()),anchor)
        original.handles.corners.zip(restored.handles.corners).forEach { (a,b) -> same(a,b) }
        assertNotEquals(MonitorHandlesUseCase().from(original.monitor).corners,restored.handles.corners)
    }
    @Test fun flatFramesAndUnstagedScenesAlsoRoundTrip() {
        val flat=screen.copy(curveDegrees=0f)
        val s=AlignmentSnapshot(flat,MonitorHandlesUseCase().from(flat))
        val restored=space(space(s,anchor.inverse()),anchor)
        assertNull(restored.stagedPose);assertNull(restored.revision)
        assertEquals(0f,restored.handles.sagitta,0f)
        s.handles.corners.zip(restored.handles.corners).forEach { (a,b) -> same(a,b) }
    }
}
