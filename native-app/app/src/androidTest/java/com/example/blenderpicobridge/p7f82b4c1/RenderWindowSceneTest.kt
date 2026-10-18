package com.example.blenderpicobridge.p7f82b4c1

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.domain.model.RenderWindowMode
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeUiEvent
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeViewModel
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeViewModelFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production SceneFeed/VideoComponent; leaves pairing and saved anchors untouched. */
@RunWith(AndroidJUnit4::class)
class RenderWindowSceneTest {
    @Test fun productionSceneReceivesTransparentStream() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("render_scene")=="true")
        val aligned=InstrumentationRegistry.getArguments().getString("render_scene_aligned")=="true"
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        context.startActivity(Intent().setClassName(context.packageName,context.packageName+".platform.LaunchActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("inspection_room",true))
        lateinit var model: BridgeViewModel
        inst.runOnMainSync { model=ViewModelProvider(context.applicationContext as ViewModelStoreOwner,
            BridgeViewModelFactory(context))[BridgeViewModel::class.java] }
        fun await(check: ()->Boolean): Boolean {
            val until=System.currentTimeMillis()+25000
            while(System.currentTimeMillis()<until) { if(check())return true;Thread.sleep(100) }
            return false
        }
        assertTrue("Model must load and direct-stream capability must arrive",await { model.state.value.hasModel && model.state.value.renderAvailable && model.state.value.room })
        inst.runOnMainSync {
            model.onEvent(BridgeUiEvent.RenderMode(if(aligned) RenderWindowMode.SCENE_ALIGNED else RenderWindowMode.LIVE_DISPLAY))
            model.onEvent(BridgeUiEvent.RenderEnabled(true))
        }
        assertTrue("Production window: "+model.state.value.renderStatus,await { model.state.value.renderStreaming })
        if(aligned) {
            // A saved physical monitor can be behind the emulator's current eye.
            // Move only the independent test video window in front; preserve the anchor.
            inst.runOnMainSync { model.onEvent(BridgeUiEvent.RecenterRender) }
            assertTrue("Scene-aligned camera must start",await { model.state.value.linkViewport && !model.state.value.renderStatus.startsWith("View unavailable") })
        }
        val billboard=InstrumentationRegistry.getArguments().getString("render_scene_billboard")=="true"
        if(billboard) inst.runOnMainSync {
            model.onEvent(BridgeUiEvent.RenderFaceUser(true))
            model.onEvent(BridgeUiEvent.RenderFitModel(true))
        }
        val hideNative=InstrumentationRegistry.getArguments().getString("render_scene_hide_model")=="true"
        if(hideNative) inst.runOnMainSync { model.onEvent(BridgeUiEvent.ModelVisibilityChanged(false)) }
        val lockWindow=InstrumentationRegistry.getArguments().getString("render_scene_lock_window")=="true"
        if(lockWindow) inst.runOnMainSync { model.onEvent(BridgeUiEvent.RenderLocked(true)) }
        // Leave a bounded evidence window for compositor capture, then release the live receiver.
        val hold=InstrumentationRegistry.getArguments().getString("render_scene_hold_ms")?.toLongOrNull()?.coerceIn(1000,20000) ?: 10000
        Thread.sleep(hold)
        assertTrue("Production window lost video: "+model.state.value.renderStatus,model.state.value.renderStreaming)
        if(aligned)assertTrue("Scene-aligned camera must survive scene edits",model.state.value.linkViewport)
        if(billboard) {
            assertTrue("Auto-fit remains active",model.scene.value.renderWindow.autoFit)
            assertTrue("Auto-fit camera remains valid",!model.state.value.renderStatus.startsWith("View unavailable"))
            inst.runOnMainSync {
                model.onEvent(BridgeUiEvent.RenderFitModel(false))
                model.onEvent(BridgeUiEvent.RenderFaceUser(false))
            }
        }
        if(lockWindow) {
            assertTrue("Frame lock must survive live video",model.scene.value.renderWindow.locked)
            inst.runOnMainSync { model.onEvent(BridgeUiEvent.RenderLocked(false)) }
        }
        if(hideNative) {
            assertTrue("Native rendering must remain disabled",!model.scene.value.modelVisible)
            assertTrue("Native interaction mode must remain available",model.state.value.hasModel)
            inst.runOnMainSync { model.onEvent(BridgeUiEvent.ModelVisibilityChanged(true)) }
        }
        inst.runOnMainSync { model.onEvent(BridgeUiEvent.RenderEnabled(false)) }
    }
}
