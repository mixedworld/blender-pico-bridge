package com.example.blenderpicobridge.p7f82b4c1

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.data.repository.RenderVideoRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ReceiveRenderVideoUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.BuildRenderBillboardUseCase
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.RenderWindowScene
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene.sceneOwned
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class RenderBillboardSceneTest {
    @Test fun cachedImportedModelStaysBehindSpherePlaneAfterRotationAndHiding()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("sphere_model_probe")=="true")
        val revision=requireNotNull(args.getString("model_revision"))
        require(revision.matches(Regex("[a-f0-9]{64}")))
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"bridge-models/$revision.glb")
        assertTrue("Already cached scene required",file.isFile)
        withContext(Dispatchers.Main) {
            val owner=Entity()
            val model=Entity.loadSuspend(file);owner.addChild(model)
            val receive=ReceiveRenderVideoUseCase(object: RenderVideoRepository {
                override fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit)=AutoCloseable {}
            })
            val window=RenderWindowScene(owner,1.6f,receive) {}
            try {
                window.update(RenderWindowState(enabled=true,faceUser=true,fitModel=true,locked=true),
                    Pairing("http://127.0.0.1:8787","fixture-token"),MonitorCalibration(enabled=true,locked=true),null,null)
                fun hide(entity: Entity) {
                    entity.components[ModelComponent::class.java]?.isVisible=false
                    entity.getChildren().forEach(::hide)
                }
                for(hidden in listOf(false,true)) {
                    if(hidden)hide(model)
                    for(angle in listOf(0f,.4f,1.2f)) {
                        owner.components[TransformComponent::class.java]!!.apply {
                            position=Vector3(.2f,1.1f,-2.5f)
                            quaternion=Quat(0f,sin(angle/2),0f,cos(angle/2))
                            scaleVector=Vector3(.084f,.084f,.084f)
                        }
                        val b=model.getVisualBounds(owner,true,true)
                        assertFalse(b.isEmpty())
                        val corners=List(8) { i -> owner.convertPositionTo(Vector3(
                            if(i and 1==0)b.min.x else b.max.x,if(i and 2==0)b.min.y else b.max.y,
                            if(i and 4==0)b.min.z else b.max.z),null) }
                        val centre=owner.convertPositionTo(b.center,null)
                        val radius=b.boundingSphereRadius*.084f
                        val head=centre+Vector3(0f,0f,radius+2f)
                        val eyes=listOf(head+Vector3(-.032f,0f,0f),head+Vector3(.032f,0f,0f))
                        assertTrue(window.follow(head,corners,eyes))
                        val plane=window.anchor.convertPositionTo(Vector3.ZERO,null)
                        val n=window.anchor.convertRotationTo(Quat(),null).rotateVector(Vector3(0f,0f,1f))
                        val expected=centre+n*(radius+.03f)
                        assertEquals(expected.x,plane.x,.0001f);assertEquals(expected.y,plane.y,.0001f)
                        assertEquals(expected.z,plane.z,.0001f)
                        assertTrue("Entire imported model bound stays behind actual plane",corners.all {
                            val d=it-plane;d.x*n.x+d.y*n.y+d.z*n.z<-.0299f
                        })
                    }
                }
            } finally { window.close();owner.destroy(true) }
        }
    }
    @Test fun followsAcrossParentTransformWithoutChangingReceiverOrLosingManualPose() {
        var failure: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { verify() } catch(t: Throwable) { failure=t }
        }
        failure?.let { throw AssertionError("Billboard scene check failed",it) }
    }
    @Test fun actualModelBoundsStayBehindPlaneUntilBothEyeClearancesRequireIntersection() {
        var failure: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val owner=Entity()
            val model=Entity();owner.addChild(model)
            val mesh=MeshResource.createBox(Vector3(.7f,.6f,1.2f)).sceneOwned()
            val material=UnlitMaterial.create().sceneOwned()
            val receive=ReceiveRenderVideoUseCase(object: RenderVideoRepository {
                override fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit)=AutoCloseable {}
            })
            val window=RenderWindowScene(owner,1.6f,receive) {}
            try {
                owner.components[TransformComponent::class.java]!!.apply {
                    position=Vector3(.2f,1.1f,-1.3f)
                    quaternion=Quat(0f,sin(.3f),0f,cos(.3f))
                    scaleVector=Vector3(.8f,.8f,.8f)
                }
                model.components.set(ModelComponent(mesh,material))
                val bounds=model.getVisualBounds(null,true,true)
                assertFalse(bounds.isEmpty())
                val corners=List(8) { i -> Vector3(if(i and 1==0)bounds.min.x else bounds.max.x,
                    if(i and 2==0)bounds.min.y else bounds.max.y,if(i and 4==0)bounds.min.z else bounds.max.z) }
                window.update(RenderWindowState(enabled=true,faceUser=true,fitModel=true,locked=true),
                    Pairing("http://127.0.0.1:8787","fixture-token"),MonitorCalibration(enabled=true,locked=true),null,null)
                fun distance(p: Vector3): Float {
                    val c=window.anchor.convertPositionTo(Vector3.ZERO,null)
                    val n=window.anchor.convertRotationTo(Quat(),null).rotateVector(Vector3(0f,0f,1f))
                    val d=p-c;return d.x*n.x+d.y*n.y+d.z*n.z
                }
                val far=Vector3(bounds.center.x,bounds.center.y,bounds.max.z+1f)
                assertTrue(window.follow(far,corners))
                assertTrue("Every actual mesh bound behind plane",corners.all { distance(it)<-.029f })
                val near=Vector3(bounds.center.x,bounds.center.y,bounds.max.z+.1f)
                val eyes=listOf(near+Vector3(-.032f,0f,-.02f),near+Vector3(.032f,0f,.02f))
                assertTrue(window.follow(near,corners,eyes))
                assertTrue("Close-up is allowed to intersect",corners.any { distance(it)>0f })
                assertTrue(corners.any { distance(it)<0f })
                assertTrue(eyes.all { distance(it)>=.24999f })
                assertTrue(window.follow(bounds.center,corners))
                assertTrue(distance(bounds.center)>=.24999f)
                assertTrue(model.enabled)
            } catch(t: Throwable) { failure=t }
            finally { window.close();owner.destroy(true);mesh.close();material.close() }
        }
        failure?.let { throw AssertionError("Nearest extent SDK check failed",it) }
    }
    private fun verify() {
        val owner=Entity()
        owner.components[TransformComponent::class.java]!!.apply {
            position=Vector3(.3f,.2f,-.1f);quaternion=Quat(0f,sin(.2f),0f,cos(.2f))
            scaleVector=Vector3(.8f,.8f,.8f)
        }
        var opens=0;var closes=0
        val receive=ReceiveRenderVideoUseCase(object: RenderVideoRepository {
            override fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit): AutoCloseable {
                opens++;return AutoCloseable { closes++ }
            }
        })
        val window=RenderWindowScene(owner,1.6f,receive) {}
        var state=RenderWindowState(enabled=true)
        val monitor=MonitorCalibration(enabled=true,locked=true)
        val pair=Pairing("http://127.0.0.1:8787","fixture-token")
        fun update()=window.update(state,pair,monitor,null,null)
        fun closeEnough(a: Vector3,b: Vector3) {
            assertEquals(a.x,b.x,.0001f);assertEquals(a.y,b.y,.0001f);assertEquals(a.z,b.z,.0001f)
        }
        fun points(x: Float=0f)=List(8) { i -> Vector3(x+if(i and 1==0)-.1f else .1f,
            if(i and 2==0)1.1f else 1.3f,if(i and 4==0)-2.1f else -1.9f) }
        val eye=Vector3(.3f,1.6f,.4f)
        try {
            update();val original=window.pose()
            state=state.copy(faceUser=true,locked=true);update()
            assertTrue(window.follow(eye,emptyList()))
            closeEnough(original.first,window.pose().first)
            assertEquals(original.third,window.pose().third,0f)
            val centre=window.anchor.convertPositionTo(Vector3.ZERO,null)
            val direction=eye-centre
            val z=window.anchor.convertRotationTo(Quat(),null).rotateVector(Vector3(0f,0f,1f))
            val length=sqrt(direction.x*direction.x+direction.y*direction.y+direction.z*direction.z)
            assertTrue((z.x*direction.x+z.y*direction.y+z.z*direction.z)/length>.9999f)
            state=state.copy(fitModel=true,locked=false);update()
            repeat(5) {
                assertTrue(window.follow(eye,points()))
                assertEquals(1,opens);assertEquals(0,closes)
                assertTrue(window.anchor.getChildren().none { it.components[CollisionComponent::class.java]!=null })
            }
            val fitted=window.pose()
            val expected=BuildRenderBillboardUseCase().fit(listOf(eye.x,eye.y,eye.z),points().map { listOf(it.x,it.y,it.z) },1.6f)!!
            assertEquals("World width after parent scale conversion",expected.width,
                .9f*window.anchor.convertScaleTo(Vector3(1f,1f,1f),null).x,.0001f)
            closeEnough(Vector3(expected.position[0],expected.position[1],expected.position[2]),
                window.anchor.convertPositionTo(Vector3.ZERO,null))
            window.manipulate(Vector3(.2f,0f,0f),Quat(),1.4f)
            assertEquals(fitted,window.pose())
            assertTrue(window.follow(eye,points(.3f)));assertNotEquals(fitted,window.pose())
            state=state.copy(fitModel=false);update()
            closeEnough(original.first,window.pose().first);assertEquals(original.third,window.pose().third,0f)
            assertEquals(4,window.anchor.getChildren().count { it.components[CollisionComponent::class.java]!=null })
            state=state.copy(faceUser=false);update()
            assertEquals(original,window.pose())
        } finally { window.close();owner.destroy(true) }
        assertEquals(opens,closes)
    }
}
