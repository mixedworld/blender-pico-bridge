package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blenderpicobridge.p7f82b4c1.data.repository.KnownMarkerPoseRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.*
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class KnownMarkerPoseTest {
    private val board=CalibrationBoard(2000,1000,listOf(MarkerRect(0,40,40,100,100),MarkerRect(1,1860,40,100,100),
        MarkerRect(2,1860,860,100,100),MarkerRect(3,40,860,100,100)),MarkerRect(4,950,450,100,100))
    private val settings=MarkerSettings(44f,60f,true)
    private val k=floatArrayOf(814f,0f,639.5f,0f,814f,479.5f,0f,0f,1f)
    @Before fun setup() { assertTrue(OpenCVLoader.initLocal()) }
    private fun observation(): List<MarkerPolygon> {
        val camera=Mat(3,3,CvType.CV_64FC1).apply { put(0,0,*k.map(Float::toDouble).toDoubleArray()) }
        val rv=Mat(3,1,CvType.CV_64FC1).apply { put(0,0,PI,0.0,0.0) }
        val tv=Mat(3,1,CvType.CV_64FC1).apply { put(0,0,0.0,0.0,1.0) }
        val distortion=MatOfDouble(0.0,0.0,0.0,0.0,0.0)
        return try { (board.markers+board.reference!!).map { marker ->
            val pixels=listOf(marker.x to marker.y,(marker.x+marker.width) to marker.y,
                (marker.x+marker.width) to (marker.y+marker.height),marker.x to (marker.y+marker.height))
            val radius=.88/(PI/3)
            val points=MatOfPoint3f(*pixels.map { (u,v) -> val phi=(u/2000.0-.5)*PI/3
                Point3(radius*sin(phi),(.5-v/1000.0)*.44,radius*(1-cos(phi))) }.toTypedArray())
            val out=MatOfPoint2f()
            try { Calib3d.projectPoints(points,rv,tv,camera,distortion,out)
                MarkerPolygon(marker.id,out.toArray().map { ImagePoint(it.x.toFloat(),it.y.toFloat()) })
            } finally { points.release();out.release() }
        } } finally { camera.release();rv.release();tv.release();distortion.release() }
    }
    @Test fun nativeSolverRecoversKnownCurvedScreenAndReprojection() {
        val fit=KnownMarkerPoseRepository().fit(observation(),board,settings,k)
        assertNotNull(fit);assertEquals(.88f,fit!!.estimate.arcWidth,.0001f)
        assertEquals(.44f,fit.estimate.height,.0001f);assertEquals(1f,fit.estimate.distance,.005f)
        assertTrue(fit.estimate.errorPx<.1f);assertEquals(5,fit.projected.size);assertEquals(50,fit.outline.size)
    }
    @Test fun singleCentralMarkerWorksButUnconfirmedPhysicalSizeDoesNot() {
        val seen=observation().filter { it.id==4 }
        assertNull(KnownMarkerPoseRepository().fit(seen,board,settings.copy(confirmed=false),k))
        val fit=KnownMarkerPoseRepository().fit(seen,board,settings,k)
        assertNotNull(fit);assertEquals(1f,fit!!.estimate.distance,.01f);assertEquals(1,fit.estimate.markerCount)
    }
    @Test fun wrongPhysicalSizeCanMatchSameOutlineAtWrongDistance() {
        val seen=observation()
        val fit=KnownMarkerPoseRepository().fit(seen,board,settings.copy(sideMm=88f),k)
        assertNotNull(fit);assertEquals(2f,fit!!.estimate.distance,.01f)
        assertEquals(1.76f,fit.estimate.arcWidth,.0001f);assertTrue(fit.estimate.errorPx<.1f)
    }
    @Test fun mismatchedCurveHasWorseImageAgreementThanTheCorrectCurve() {
        val seen=observation();val solver=KnownMarkerPoseRepository()
        val correct=solver.fit(seen,board,settings,k)!!
        val flat=solver.fit(seen,board,settings.copy(curveDegrees=0f),k)!!
        assertTrue(flat.estimate.errorPx>correct.estimate.errorPx+.1f)
    }
    @Test fun curveHintUsesSpreadMarkersWithoutChangingMeasuredSettings() {
        val flat=settings.copy(curveDegrees=0f)
        val solver=KnownMarkerPoseRepository()
        val hint=solver.suggestCurve(observation(),board,flat,k)
        assertNotNull(hint);assertEquals(60f,hint!!.estimate.curveDegrees,.01f)
        assertTrue(hint.estimate.errorPx<.1f);assertEquals(0f,flat.curveDegrees,0f)
        assertNull(solver.suggestCurve(observation().filter { it.id==4 },board,flat,k))
    }
    @Test fun blenderMarkerPixelsDecodeWithTheSameCanonicalCornerOrder() {
        val patterns=listOf(
            listOf("111111","101001","110101","111001","111011","111111"),
            listOf("111111","111111","100001","101101","101011","111111"),
            listOf("111111","111001","111001","111011","100101","111111"),
            listOf("111111","101101","101101","110111","110011","111111"),
            listOf("111111","110101","110111","101101","100011","111111"))
        val detector=ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50))
        patterns.forEachIndexed { expected,rows ->
            val pixels=ByteArray(256*256) { 255.toByte() }
            for(y in 0 until 192) for(x in 0 until 192) if(rows[y/32][x/32]=='1') pixels[(y+32)*256+x+32]=0
            val image=Mat(256,256,CvType.CV_8UC1);val ids=Mat();val corners=mutableListOf<Mat>()
            try {
                image.put(0,0,pixels);detector.detectMarkers(image,corners,ids)
                assertEquals(1,ids.rows());assertEquals(expected,ids.get(0,0)[0].toInt())
                val topLeft=corners.single().get(0,0)
                assertEquals(32.0,topLeft[0],1.0);assertEquals(32.0,topLeft[1],1.0)
            } finally { image.release();ids.release();corners.forEach { it.release() } }
        }
    }
    @Test fun independentLocalMarkersRecoverAnisotropicCameraIntrinsics() {
        val truth=k.copyOf().apply { this[0]=900f;this[4]=700f }
        val camera=Mat(3,3,CvType.CV_64FC1).apply { put(0,0,*truth.map(Float::toDouble).toDoubleArray()) }
        val obj=MatOfPoint3f(Point3(-.022,.022,0.0),Point3(.022,.022,0.0),Point3(.022,-.022,0.0),Point3(-.022,-.022,0.0))
        val distortion=MatOfDouble(0.0,0.0,0.0,0.0,0.0)
        val views=(0 until 24).map { i ->
            val rv=Mat(3,1,CvType.CV_64FC1).apply { put(0,0,PI+(i%4-1.5)*.2,(i/4-2.5)*.16,(i%3-1)*.1) }
            val tv=Mat(3,1,CvType.CV_64FC1).apply { put(0,0,(i%4-1.5)*.1,(i/4-2.5)*.06,.5+i%5*.1) }
            val output=MatOfPoint2f()
            try { Calib3d.projectPoints(obj,rv,tv,camera,distortion,output)
                MarkerPolygon(4,output.toArray().map { ImagePoint(it.x.toFloat(),it.y.toFloat()) }) }
            finally { rv.release();tv.release();output.release() }
        }
        try {
            val fit=MarkerCameraCalibrationProbe.fit(views,44f,k)
            assertEquals(900f,fit.k[0],1f);assertEquals(700f,fit.k[4],1f);assertTrue(fit.rms<.001)
        } finally { camera.release();obj.release();distortion.release() }
    }

}
