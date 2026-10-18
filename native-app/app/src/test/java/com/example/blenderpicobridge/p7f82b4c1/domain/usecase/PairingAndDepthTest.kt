package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.URLEncoder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

class PairingAndDepthTest {
    private fun payload(endpoint: String = "http://192.168.1.3:8766") = "blender-pico://pair?v=1&endpoint=${URLEncoder.encode(endpoint,"UTF-8")}&token=bridge-fixture-token-0123456789ab&session=${"0".repeat(24)}"
    private val board = CalibrationBoard(1920,1080,listOf(MarkerRect(0,24,24,96,96),MarkerRect(1,1800,24,96,96),MarkerRect(2,1800,960,96,96),MarkerRect(3,24,960,96,96)))
    private fun points() = board.markers.map { listOf(.6f*((it.x+it.width*.5f)/1920f-.5f), .34f*((it.y+it.height*.5f)/1080f-.5f), 1f) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun decodesBlenderGeneratedPngWithIndependentJavaReader() {
        val png = File("../../tests/fixtures/pairing.png").readBytes()
        var offset = 8; var compressed = byteArrayOf(); var width = 0; var height = 0
        while (offset < png.size) {
            val size = ByteBuffer.wrap(png,offset,4).int
            val kind = String(png,offset+4,4,Charsets.US_ASCII)
            if (kind == "IHDR") { width = ByteBuffer.wrap(png,offset+8,4).int; height = ByteBuffer.wrap(png,offset+12,4).int }
            if (kind == "IDAT") compressed += png.copyOfRange(offset+8,offset+8+size)
            offset += size+12
        }
        val gray = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val pixels = IntArray(width*height) { i ->
            val level = gray[(i/width)*(width+1)+1+i%width].toInt() and 255
            0xff000000.toInt() or (level shl 16) or (level shl 8) or level
        }
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels))))
        val pairing = ReadPairingQrUseCase()(result.text)
        assertEquals("http://192.168.1.3:8766",pairing.endpoint)
        assertEquals("0".repeat(24),pairing.expectedSession)
    }
    @Test fun pairingCannotOpenArbitraryUrlsOrPublicHosts() {
        assertEquals("192.168.1.3",java.net.URI(ReadPairingQrUseCase()(payload()).endpoint).host)
        for (endpoint in listOf("https://192.168.1.3:8766","http://8.8.8.8:8766","http://evil.example:8766","http://u:p@192.168.1.3:8766","http://192.168.1.3:8766/path")) rejects { ReadPairingQrUseCase()(payload(endpoint)) }
        rejects { ReadPairingQrUseCase()(payload()+"&token=duplicate") }
        rejects { ReadPairingQrUseCase()(payload().replace("v=1","v=999")) }
    }
    @Test fun depthDerivesMetricFullViewportSizeFromMarkerCenterInset() {
        val size = MeasureScreenUseCase()(points(),board)
        assertEquals(.6f,size.width,.00001f); assertEquals(.34f,size.height,.00001f)
    }
    @Test fun depthRejectsMissingNonfiniteAndNonplanarSamples() {
        rejects { MeasureScreenUseCase()(List(4) { listOf(0f,0f,0f) },board) }
        rejects { MeasureScreenUseCase()(points().toMutableList().apply { set(0,listOf(Float.NaN,0f,1f)) },board) }
        rejects { MeasureScreenUseCase()(points().toMutableList().apply { set(2,listOf(.2775f,.147f,1.3f)) },board) }
    }
    @Test fun gestureAccumulationComposesRotationsAndBoundsNetworkCommands() {
        val q = listOf(0f,0f,kotlin.math.sqrt(.5f),kotlin.math.sqrt(.5f))
        val merged = MergeViewportDeltaUseCase()(ViewportDelta(rotation=q),ViewportDelta(rotation=q,pan=listOf(.2f,0f,0f),zoom=2f))
        assertEquals(1f,merged.rotation[2],.00001f);assertEquals(0f,merged.rotation[3],.00001f)
        assertEquals(2f,merged.zoom,0f)
        val bounded = MergeViewportDeltaUseCase()(ViewportDelta(pan=listOf(.9f,0f,0f),zoom=3f),ViewportDelta(pan=listOf(.9f,0f,0f),zoom=3f))
        assertEquals(1f,bounded.pan[0],0f);assertEquals(4f,bounded.zoom,0f)
    }
    @Test fun multiPointDepthRecoversMinorityCorrectClusterAndRejectsAmbiguity() {
        val corners=points()
        val mixed=corners.flatMapIndexed { i,p ->
            if (i in listOf(0,3)) List(7) { p }+List(18) { p.map { v -> v*4 } }
            else List(25) { p }
        }
        val size=MeasureScreenSamplesUseCase()(mixed,25,board)
        assertNotNull(size); assertEquals(.6f,size!!.width,.00001f)
        val ambiguous=corners.flatMap { p -> List(12) { p }+List(13) { p.map { it*2 } } }
        assertNull(MeasureScreenSamplesUseCase()(ambiguous,25,board))
        assertNull(MeasureScreenSamplesUseCase()(List(100) { listOf(0f,0f,0f) },25,board))
    }
    @Test fun kalmanRequiresConsensusBeforeReturningAndRejectsLargeInnovations() {
        val filter=StabilizeScreenSizeUseCase()
        repeat(5) { assertNull(filter.add(MeasuredScreen(.6f,.34f))) }
        assertNull(filter.add(MeasuredScreen(1.5f,.8f)))
        assertEquals(1,filter.rejectedSamples);assertEquals(5,filter.acceptedSamples)
        var result: MeasuredScreen?=null
        repeat(15) { i -> result=filter.add(MeasuredScreen(.6f+(i%3-1)*.003f,.34f+(i%3-1)*.002f)) }
        assertNotNull(result);assertEquals(.6f,result!!.width,.003f);assertEquals(.34f,result!!.height,.002f)
    }

    @Test fun fingertipContactsRecoverMetricWorldPoseAndMarkerInsets() {
        val angle=.3f;val c=kotlin.math.cos(angle);val sn=kotlin.math.sin(angle)
        val contacts=board.markers.map { m ->
            val x=((m.x+m.width*.5f)/board.width-.5f)*.6f
            val y=(.5f-(m.y+m.height*.5f)/board.height)*.34f
            listOf(2f+c*x,1.3f+y,-1f-sn*x)
        }
        val result=FitTouchedScreenUseCase()(contacts,board)
        assertEquals(.6f,result.width,.00001f);assertEquals(.34f,result.height,.00001f)
        assertEquals(2f,result.center[0],.00001f);assertEquals(1.3f,result.center[1],.00001f);assertEquals(-1f,result.center[2],.00001f)
        assertEquals(kotlin.math.sin(angle/2),result.rotation[1],.00001f)
        rejects { FitTouchedScreenUseCase()(contacts.toMutableList().apply { set(2,contacts[0]) },board) }
    }

}
