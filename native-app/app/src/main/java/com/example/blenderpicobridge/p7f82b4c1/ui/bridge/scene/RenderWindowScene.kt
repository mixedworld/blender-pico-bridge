package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import android.os.SystemClock
import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildRenderBillboardUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ReceiveRenderVideoUseCase
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.VideoComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.SurfaceRenderTexture
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.VideoMaterial
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.sqrt

/** Independent flat render probe under placementOwner. All ECS changes are main-thread only. */
internal class RenderWindowScene(private val owner: Entity,val aspect: Float,
    private val receive: ReceiveRenderVideoUseCase,private val status: (RenderVideoStatus)->Unit): AutoCloseable {
    val anchor=Entity()
    private val video=Entity()
    private val bars=List(4) { Entity() }
    private val width=.9f
    private val height=width/aspect
    private val mesh=MeshResource.createPlane(1f,1f).sceneOwned()
    private val barMesh=MeshResource.createBox(Vector3(1f,1f,1f)).sceneOwned()
    private val border=UnlitMaterial.create().apply { setBaseColor(Color4(.34f,.38f,.42f,1f)) }.sceneOwned()
    private val texture=SurfaceRenderTexture(2560,(1280/aspect).toInt().coerceIn(128,4096),3).sceneOwned()
    private val material=VideoMaterial(BlendingMode.TRANSPARENT,VideoDimensionMode.MONO,MaterialCullingMode.BACK,Color4.TRANSPARENT).apply {
        bindSurfaceRenderTexture(texture);setDepthTest(true);setDepthWrite(false)
    }.sceneOwned()
    private val surface=requireNotNull(texture.acquireSurface()) { "Video surface unavailable" }
    private val picking=RenderFramePicking(anchor,width,height)
    private var locked=false
    private var faceUser=false
    private var autoFit=false
    private var manualPose: Triple<Vector3,Quat,Float>?=null
    private val billboard=BuildRenderBillboardUseCase()
    private var receiver: AutoCloseable?=null
    private var pairing: Pairing?=null
    private var enabled=false
    private var active=true
    private var streaming=false
    private var projectionValid=true
    private var initialized=false
    private var reset=-1
    private var retry=-1
    private var closed=false
    private var lastFitLogAt=0L
    private val transform get()=anchor.components[TransformComponent::class.java]!!
    val physicalWidth get()=width*transform.scaleVector.x

    init {
        anchor.setName("cyclesRenderWindow");owner.addChild(anchor);anchor.enabled=false
        anchor.addChild(video);video.setName("cyclesRenderVideo")
        video.components.set(VideoComponent(mesh,material));video.enabled=false
        video.components[TransformComponent::class.java]!!.scaleVector=Vector3(width,height,1f)
        bars.forEachIndexed { i,bar ->
            anchor.addChild(bar);bar.components.set(ModelComponent(barMesh,border))
            bar.components[TransformComponent::class.java]!!.apply {
                position=when(i) { 0->Vector3(0f,height/2,.004f);1->Vector3(0f,-height/2,.004f);2->Vector3(-width/2,0f,.004f);else->Vector3(width/2,0f,.004f) }
                scaleVector=if(i<2) Vector3(width+.012f,.006f,.006f) else Vector3(.006f,height,.006f)
            }
        }
    }
    fun update(state: RenderWindowState,nextPairing: Pairing?,calibration: MonitorCalibration,head: Vector3?,rotation: Quat?) {
        if(!initialized) place(calibration,head,rotation,true)
        if(reset!=-1 && reset!=state.reset) place(calibration,head,rotation,false)
        if(!faceUser && state.faceUser && initialized && manualPose==null) manualPose=pose()
        if((faceUser && !state.faceUser) || (autoFit && !state.autoFit)) manualPose?.let(::restore)
        if(faceUser && !state.faceUser) manualPose=null
        faceUser=state.faceUser;autoFit=state.autoFit
        val restart=retry!=state.retry || pairing!=nextPairing
        reset=state.reset;retry=state.retry;pairing=nextPairing
        enabled=state.enabled
        bars.forEach { it.enabled=state.borderVisible }
        locked=state.locked
        if(state.mode==RenderWindowMode.LIVE_DISPLAY) setProjectionValid(true)
        anchor.enabled=enabled && active && initialized
        updatePicking()
        if(!enabled || !active) stopReceiver()
        else if(receiver==null || restart) { stopReceiver();startReceiver() }
    }
    private fun startReceiver() {
        val pair=pairing ?: return
        receiver=receive(surface,pair,aspect) { update ->
            if(!closed && enabled && active) {
                if(update.streaming) material.setDimensionMode(if(update.stereo) VideoDimensionMode.SIDE_BY_SIDE else VideoDimensionMode.MONO)
                streaming=update.streaming;video.enabled=streaming && projectionValid
                status(update)
            }
        }
    }
    private fun stopReceiver() { receiver?.close();receiver=null;streaming=false;video.enabled=false }
    fun setProjectionValid(value: Boolean) { projectionValid=value;video.enabled=streaming && projectionValid }
    fun setActive(value: Boolean) {
        if(active==value)return
        active=value;anchor.enabled=enabled && active && initialized
        updatePicking()
        if(!active)stopReceiver() else if(enabled)startReceiver()
    }
    fun place(calibration: MonitorCalibration,head: Vector3?,rotation: Quat?,useMonitor: Boolean) {
        if(useMonitor && calibration.enabled && calibration.locked) {
            transform.position=calibration.position.vector()+calibration.quaternion.quat().rotateVector(Vector3(0f,0f,.12f))
            transform.quaternion=calibration.quaternion.quat()
            val chord=CurvedScreenUseCase(calibration.width,calibration.height,calibration.curveDegrees).chord.coerceIn(.35f,1.8f)
            transform.scaleVector=Vector3(chord/width,chord/width,chord/width)
        } else {
            if(head==null || rotation==null)return
            transform.position=owner.convertPositionFrom(head+rotation.rotateVector(Vector3(0f,-.12f,-1.25f)),null)
            transform.quaternion=owner.convertRotationFrom(rotation,null)
        }
        initialized=true;anchor.enabled=enabled && active
        updatePicking()
    }
    fun ensurePlaced(calibration: MonitorCalibration,head: Vector3,rotation: Quat) {
        if(!initialized)place(calibration,head,rotation,true)
    }
    fun manualPlacement()=manualPose
    fun restoreManualPlacement(pose: Triple<Vector3,Quat,Float>?) { manualPose=pose }
    fun pose()=Triple(transform.position,transform.quaternion,transform.scaleVector.x)
    fun restore(pose: Triple<Vector3,Quat,Float>) {
        transform.position=pose.first;transform.quaternion=pose.second;transform.scaleVector=Vector3(pose.third,pose.third,pose.third);initialized=true
    }
    /** Called on main before generating this frame's camera frustum. No receiver/resource changes. */
    fun follow(head: Vector3,modelCorners: List<Vector3>,stereoEyes: List<Vector3> = emptyList()): Boolean {
        if(!enabled || !active || !faceUser || closed || !initialized)return true
        if(manualPose==null)manualPose=pose()
        if(autoFit) {
            val previousNormal=anchor.convertRotationTo(Quat(),null).rotateVector(Vector3(0f,0f,1f))
            val fit=billboard.fit(head.values(),modelCorners.map { it.values() },aspect,
                stereoEyes.map { it.values() },previousNormal.values()) ?: return false
            transform.position=owner.convertPositionFrom(fit.position.vector(),null)
            transform.quaternion=owner.convertRotationFrom(fit.rotation.quat(),null)
            val s=fit.width/width
            transform.scaleVector=owner.convertScaleFrom(Vector3(s,s,s),null)
            if(SystemClock.elapsedRealtime()-lastFitLogAt>2000) {
                lastFitLogAt=SystemClock.elapsedRealtime()
                val centre=modelCorners.reduce { a,b -> a+b }*(1f/modelCorners.size)
                val normal=anchor.convertRotationTo(Quat(),null).rotateVector(Vector3(0f,0f,1f))
                val plane=anchor.convertPositionTo(Vector3.ZERO,null)
                fun dot(a: Vector3,b: Vector3)=a.x*b.x+a.y*b.y+a.z*b.z
                val radius=modelCorners.maxOf { val d=it-centre;sqrt(dot(d,d)) }
                val eyeDistance=dot(head-plane,normal)
                val gap=-modelCorners.maxOf { dot(it-plane,normal) }
                Log.i("BlenderBridge","RENDER_FIT sphereRadius=$radius centreDistance=${dot(head-centre,normal)} planeEyeDistance=$eyeDistance nearestBoundGap=$gap plane=$plane normal=$normal visual=UNOBSERVED")
            }
        } else {
            val centre=anchor.convertPositionTo(Vector3.ZERO,null)
            val facing=billboard.facing(head.values(),centre.values()) ?: return false
            transform.quaternion=owner.convertRotationFrom(facing.quat(),null)
        }
        return true
    }
    private fun updatePicking() = picking.setInteractive(anchor.enabled && !locked && !autoFit && !closed)
    fun owns(entity: Entity) = anchor.enabled && !locked && !autoFit && picking.owns(entity)
    fun manipulate(delta: Vector3,rotation: Quat,scale: Float) {
        if(locked || autoFit || !anchor.enabled || closed)return
        transform.position=transform.position+delta
        val q=rotation*transform.quaternion
        val n=sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w)
        transform.quaternion=Quat(q.x/n,q.y/n,q.z/n,q.w/n)
        val s=(transform.scaleVector.x*scale).coerceIn(.35f/width,1.8f/width)
        transform.scaleVector=Vector3(s,s,s)
        if(faceUser) manualPose=Triple(transform.position,rotation*(manualPose?.second ?: transform.quaternion),s)
    }
    override fun close() {
        if(closed)return
        closed=true;stopReceiver() // EglRenderer/decoder must release their borrowed Surface first.
        picking.close();anchor.destroy(true);surface.release();material.close();texture.close()
        mesh.close();barMesh.close();border.close()
    }
}
