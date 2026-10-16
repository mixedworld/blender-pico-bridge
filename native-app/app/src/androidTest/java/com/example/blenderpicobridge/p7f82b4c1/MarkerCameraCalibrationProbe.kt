package com.example.blenderpicobridge.p7f82b4c1

import com.example.blenderpicobridge.p7f82b4c1.domain.model.MarkerPolygon
import org.opencv.calib3d.Calib3d
import org.opencv.core.*

/** Diagnostic only. Each small measured marker is a local planar target, independent of monitor curvature. */
internal object MarkerCameraCalibrationProbe {
    data class Fit(val k: FloatArray,val rms: Double,val focalStd: List<Double>,val samples: Int)
    fun fit(views: List<MarkerPolygon>,sideMm: Float,initial: FloatArray): Fit {
        require(views.size>=12 && initial.size==9)
        val chosen=List(minOf(36,views.size)) { views[it*(views.size-1)/(minOf(36,views.size)-1)] }
        val s=sideMm/2000.0
        val objects=chosen.map { MatOfPoint3f(Point3(-s,s,0.0),Point3(s,s,0.0),Point3(s,-s,0.0),Point3(-s,-s,0.0)) }
        val images=chosen.map { view -> MatOfPoint2f(*view.points.map { Point(it.x.toDouble(),it.y.toDouble()) }.toTypedArray()) }
        val camera=Mat(3,3,CvType.CV_64FC1).apply { put(0,0,*initial.map(Float::toDouble).toDoubleArray()) }
        val distortion=Mat.zeros(8,1,CvType.CV_64FC1)
        val rv=mutableListOf<Mat>();val tv=mutableListOf<Mat>()
        val si=Mat();val se=Mat();val errors=Mat()
        try {
            val flags=Calib3d.CALIB_USE_INTRINSIC_GUESS or Calib3d.CALIB_FIX_PRINCIPAL_POINT or Calib3d.CALIB_ZERO_TANGENT_DIST or
                Calib3d.CALIB_FIX_K1 or Calib3d.CALIB_FIX_K2 or Calib3d.CALIB_FIX_K3 or Calib3d.CALIB_FIX_K4 or Calib3d.CALIB_FIX_K5 or Calib3d.CALIB_FIX_K6
            val rms=Calib3d.calibrateCameraExtended(objects,images,Size(1280.0,960.0),camera,distortion,rv,tv,si,se,errors,
                flags,TermCriteria(TermCriteria.COUNT or TermCriteria.EPS,50,1e-8))
            return Fit(FloatArray(9) { camera.get(it/3,it%3)[0].toFloat() },rms,listOf(si.get(0,0)[0],si.get(1,0)[0]),chosen.size)
        } finally { objects.forEach { it.release() };images.forEach { it.release() };camera.release();distortion.release()
            rv.forEach { it.release() };tv.forEach { it.release() };si.release();se.release();errors.release() }
    }
}
