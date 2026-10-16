package com.example.blenderpicobridge.p7f82b4c1

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in public OpenXR discovery. Creates no XR instance/session and acquires no camera frames. */
@RunWith(AndroidJUnit4::class)
class OpenXrCameraCapabilityTest {
    private external fun probeExtensions(context: Context): String
    @Test fun enumeratePublicRuntimeExtensions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physical_camera")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("openxr_loader")
        System.load(File(context.codeCacheDir,"libopenxr_probe.so").absolutePath)
        println("OPENXR_CAMERA "+probeExtensions(context.applicationContext))
    }
}
