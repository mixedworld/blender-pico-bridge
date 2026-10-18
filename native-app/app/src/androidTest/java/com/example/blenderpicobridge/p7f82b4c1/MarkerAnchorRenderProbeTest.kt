package com.example.blenderpicobridge.p7f82b4c1

import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.data.repository.*
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CvToSpatialCameraPoseUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.SelectMarkerIntrinsicsUseCase
import com.pico.spatial.ml.securemr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder

/** Opt-in visible proof using the already paired model. No ECS world transform is inferred. */
@RunWith(AndroidJUnit4::class)
class MarkerAnchorRenderProbeTest {
    @Test fun followThenLockMeasuredBoardPose() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physical_camera")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bridge=HttpBridgeRepository(context)
        val model=withTimeout(15000) { bridge.observe(bridge.savedPairing()).filterIsInstance<BridgeUpdate.Model>().first() }
        val board=requireNotNull(model.packet.board)
        val profile=requireNotNull(MarkerCameraProfileRepository(context).load()) { "Install a validated local camera profile first" }
        val k=SelectMarkerIntrinsicsUseCase()(profile.sdkK.toFloatArray(),1280,960,profile)
        require(k.contentEquals(profile.measuredK.toFloatArray())) { "Local camera profile did not pass validation" }
        val settings=MutableStateFlow(MarkerSettings(requireNotNull(args.getString("side_mm")).toFloat(),
            requireNotNull(args.getString("curve_deg")).toFloat(),true))
        val scale=requireNotNull(args.getString("model_scale")).toFloat()
        require(scale.isFinite() && scale in .001f..1f)
        val instance=SpatialMLInstance.create(context.applicationContext)
        withTimeout(8000) { while(!instance.ready) delay(100) }
        val session=requireNotNull(instance.createSession(SpatialMLSession.InitInfo(1280,960,1000,1000,1000,
            SpatialMLSession.ContainerType.VOLUMETRIC).addPortal()))
        val tensors=mutableListOf<GlobalTensor>()
        val pipelines=mutableListOf<Pipeline>()
        try {
            val bytes=model.file.readBytes()
            val scene=SharedMemory.create("existing-bridge-model",bytes.size).use { memory ->
                val mapped=memory.mapReadWrite()
                try { mapped.put(bytes) } finally { SharedMemory.unmap(mapped) }
                session.newSceneFromGLTF(memory)
            }.also(tensors::add)
            val matrix=session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32,intArrayOf(4,4))).also(tensors::add)
            fun anchor(property: SceneGraphProperty.CameraAnchor)=session.newPipeline().also { pipeline ->
                pipelines+=pipeline
                pipeline.updateSceneGraphProperty(scene,"/",property,matrix)
                pipeline.switchSceneVisibility(scene,pipeline.newLocalTensor(0xF.toByte()))
            }
            val follow=anchor(SceneGraphProperty.CameraAnchor.Follow)
            val locked=anchor(SceneGraphProperty.CameraAnchor.Locked)
            val solver=KnownMarkerPoseRepository()
            var lastTask: Pipeline.RunTask?=null
            withTimeout(90000) {
                SpatialCameraRepository(context,automaticWorld=false).scan(ScanPurpose.MARKER_POSE,board,settings)
                    .filterIsInstance<ScanUpdate.Markers>()
                    .filter { it.frame.observed.size>=4 && it.frame.stableSamples>=8 }
                    .take(30).collect { update ->
                        val fit=requireNotNull(solver.fit(update.frame.observed,board,settings.value,k))
                        val pose=CvToSpatialCameraPoseUseCase()(fit.cameraPose)
                        for(row in 0..2) for(column in 0..2) pose[row*4+column]*=scale
                        SharedMemory.create("marker-camera-pose",64).use { memory ->
                            val mapped=memory.mapReadWrite().order(ByteOrder.nativeOrder())
                            try { mapped.asFloatBuffer().put(pose) } finally { SharedMemory.unmap(mapped) }
                            matrix.tensorResource=memory
                        }
                        lastTask=follow.submit(emptyMap(),null,lastTask)
                    }
            }
            locked.submit(emptyMap(),null,lastTask)
            println("MARKER_ANCHOR locked_for_visual_check seconds=25 modelScale=$scale")
            delay(25000)
        } finally {
            pipelines.asReversed().forEach { it.destructor.clear();it.destructor.destroy() }
            tensors.asReversed().forEach { it.destructor.clear();it.destructor.destroy() }
            session.destructor.clear();session.destructor.destroy()
        }
    }
}
