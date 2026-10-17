package com.example.blenderpicobridge.p7f82b4c1

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blenderpicobridge.p7f82b4c1.data.repository.HttpBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.data.repository.WebRtcRenderRepository
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in live LAN video check. Reads pairing only; never writes room/calibration preferences. */
@RunWith(AndroidJUnit4::class)
class RenderVideoTransportTest {
    @Test fun threeReceiverSessionsReceiveAndReleaseVideo() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("physical_render")=="true")
        val context=instrumentation.targetContext
        val pairing=HttpBridgeRepository(context).savedPairing()
        require(pairing.endpoint.isNotEmpty() && pairing.token.isNotEmpty()) { "Pair the app first" }
        repeat(3) { attempt ->
            val thread=HandlerThread("Render video test drain").apply { start() }
            val reader=ImageReader.newInstance(1280,610,ImageFormat.PRIVATE,3)
            val imageReady=CountDownLatch(1)
            val videoReady=CountDownLatch(1)
            val drainFailure=AtomicReference<Throwable?>(null)
            reader.setOnImageAvailableListener({ source ->
                try { source.acquireLatestImage()?.use { imageReady.countDown() } }
                catch(error: Exception) { drainFailure.set(error);imageReady.countDown() }
            },Handler(thread.looper))
            var session: AutoCloseable?=null
            var lastStatus="Starting"
            try {
                instrumentation.runOnMainSync {
                    session=WebRtcRenderRepository(context).open(reader.surface,pairing,1280f/610) {
                        lastStatus=it.message
                        if(it.streaming)videoReady.countDown()
                    }
                }
                val received=videoReady.await(25,TimeUnit.SECONDS)
                assertTrue("Session ${attempt+1}: $lastStatus",received)
                assertTrue("Decoded image did not reach output surface",imageReady.await(5,TimeUnit.SECONDS))
                drainFailure.get()?.let { throw AssertionError("Output surface drain failed",it) }
            } finally {
                instrumentation.runOnMainSync { session?.close() }
                reader.setOnImageAvailableListener(null,null)
                thread.quitSafely();thread.join(1000);reader.close()
            }
            // Allow the cancellation finalizer to release its matching signaling lease.
            Thread.sleep(1500)
        }
    }
}
