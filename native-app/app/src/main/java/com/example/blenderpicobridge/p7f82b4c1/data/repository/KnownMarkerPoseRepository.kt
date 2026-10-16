package com.example.blenderpicobridge.p7f82b4c1.data.repository

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.CurvedScreenUseCase
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.*

/** Lens-rectified camera coordinates only; deliberately does not manufacture a world transform. */
class KnownMarkerPoseRepository {
    data class Fit(val estimate: MarkerEstimate,val projected: List<MarkerPolygon>,val outline: List<ImagePoint>,val cameraPose: CameraSpacePose)
    var rejection: String? = null
        private set
    private fun reject(reason: String): Fit? { rejection=reason;return null }

    /** Advisory image fit only. Never changes settings, scale, readiness or world placement. */
    fun suggestCurve(observed: List<MarkerPolygon>,board: CalibrationBoard,settings: MarkerSettings,k: FloatArray): Fit? {
        // A single small patch cannot establish curvature across the screen.
        if (observed.map { it.id }.distinct().size<3) return null
        val rejectionBefore=rejection
        return try {
            val coarse=(0..120 step 10).mapNotNull { fit(observed,board,settings.copy(curveDegrees=it.toFloat()),k) }
                .minByOrNull { it.estimate.errorPx } ?: return null
            val center=coarse.estimate.curveDegrees.toInt()
            (maxOf(0,center-8)..minOf(120,center+8) step 2).mapNotNull {
                fit(observed,board,settings.copy(curveDegrees=it.toFloat()),k)
            }.plus(coarse).minByOrNull { it.estimate.errorPx }
        } finally { rejection=rejectionBefore }
    }

    /** Bounded numeric diagnostics; no camera pixels or image/world coordinates leave the process. */
    fun diagnose(observed: List<MarkerPolygon>,board: CalibrationBoard,settings: MarkerSettings,k: FloatArray): String {
        val previousRejection=rejection
        return try {
            val all=fit(observed,board,settings,k)
            val variants=listOf("active" to k,"fy075" to k.copyOf().apply { this[4]*=.75f },
                "fx1333" to k.copyOf().apply { this[0]*=4f/3f })
            val cameraChecks=variants.joinToString(";") { (label,matrix) ->
                val fits=(0..40 step 5).mapNotNull { fit(observed,board,settings.copy(curveDegrees=it.toFloat()),matrix) }
                val best=fits.minByOrNull { it.estimate.errorPx }?.estimate
                "$label:px=${best?.errorPx},curve=${best?.curveDegrees},z=${best?.distance}"
            }
            val individual=observed.joinToString(";") { marker ->
                val one=fit(listOf(marker),board,settings,k)
                val projected=all?.projected?.firstOrNull { it.id==marker.id }?.points
                val residual=projected?.zip(marker.points)?.let { p -> sqrt(p.sumOf { (a,b) ->
                    val dx=a.x-b.x;val dy=a.y-b.y;(dx*dx+dy*dy).toDouble() }/p.size) }
                "${marker.id}:singlePx=${one?.estimate?.errorPx},allPx=$residual,z=${one?.estimate?.distance}"
            }
            val rects=(board.markers+listOfNotNull(board.reference)).associateBy { it.id }
            val known=observed.filter { it.id in rects }
            val source=MatOfPoint2f(*known.flatMap { m -> val r=rects.getValue(m.id)
                listOf(Point(r.x.toDouble(),r.y.toDouble()),Point((r.x+r.width).toDouble(),r.y.toDouble()),
                    Point((r.x+r.width).toDouble(),(r.y+r.height).toDouble()),Point(r.x.toDouble(),(r.y+r.height).toDouble())) }.toTypedArray())
            val target=MatOfPoint2f(*known.flatMap { it.points.map { p -> Point(p.x.toDouble(),p.y.toDouble()) } }.toTypedArray())
            val output=MatOfPoint2f();var homography: Mat?=null
            var inferred="unresolved"
            val planar=try {
                homography=Calib3d.findHomography(source,target)
                val h=homography!!
                val cx=k[2].toDouble();val cy=k[5].toDouble()
                val a=h.get(0,0)[0]-cx*h.get(2,0)[0];val b=h.get(0,1)[0]-cx*h.get(2,1)[0]
                val c=h.get(1,0)[0]-cy*h.get(2,0)[0];val d=h.get(1,1)[0]-cy*h.get(2,1)[0]
                val e=h.get(2,0)[0];val f=h.get(2,1)[0]
                val aa=a*b;val ab=c*d;val ba=a*a-b*b;val bb=c*c-d*d
                val det=aa*bb-ab*ba
                if(abs(det)>1e-20) {
                    val ix=(-e*f*bb+ab*(e*e-f*f))/det
                    val iy=(-aa*(e*e-f*f)+ba*e*f)/det
                    if(ix>0 && iy>0) inferred="fx=${1/sqrt(ix)},fy=${1/sqrt(iy)}"
                }
                Core.perspectiveTransform(source,output,h)
                val truth=target.toArray();sqrt(output.toArray().indices.sumOf { i -> val p=output.toArray()[i]
                    val dx=p.x-truth[i].x;val dy=p.y-truth[i].y;dx*dx+dy*dy }/truth.size)
            } catch (_: org.opencv.core.CvException) { Double.NaN }
            finally { source.release();target.release();output.release();homography?.release() }
            "board=${board.width}x${board.height} K=${k.joinToString(",")} cameraChecks=[$cameraChecks] planarPx=$planar homographyK=[$inferred] markers=[$individual]"
        } finally { rejection=previousRejection }
    }

    fun fit(observed: List<MarkerPolygon>,board: CalibrationBoard,settings: MarkerSettings,k: FloatArray): Fit? {
        rejection=null
        if (!settings.confirmed || !settings.sideMm.isFinite() || settings.sideMm !in 10f..200f || k.size!=9 || !k.all(Float::isFinite) || k[0]<=0 || k[4]<=0) return reject("Invalid measured side or camera intrinsics")
        val rects=(board.markers+listOfNotNull(board.reference)).associateBy { it.id }
        val reference=board.reference ?: board.markers.firstOrNull() ?: return reject("Blender marker layout missing")
        val pitch=settings.sideMm/1000f/reference.width
        val geometry=runCatching { CurvedScreenUseCase(board.width*pitch,board.height*pitch,settings.curveDegrees) }.getOrNull() ?: return reject("Measured side gives unsupported screen dimensions or curve")
        val detections=observed.filter { it.id in rects && it.points.size==4 && it.points.all { p -> p.x.isFinite() && p.y.isFinite() } }
        if (detections.isEmpty()) return reject("No matching Blender markers")
        val objectList=mutableListOf<Point3>();val imageList=mutableListOf<Point>()
        fun point(x: Float,y: Float): Point3 = geometry.point(x/board.width,y/board.height).let { Point3(it[0].toDouble(),it[1].toDouble(),it[2].toDouble()) }
        for (d in detections) {
            val r=rects.getValue(d.id)
            val pixels=listOf(r.x.toFloat() to r.y.toFloat(),(r.x+r.width).toFloat() to r.y.toFloat(),
                (r.x+r.width).toFloat() to (r.y+r.height).toFloat(),r.x.toFloat() to (r.y+r.height).toFloat())
            val area=abs(d.points.indices.sumOf { i -> val a=d.points[i];val b=d.points[(i+1)%4];(a.x*b.y-b.x*a.y).toDouble() })/2
            if (area<64) continue
            objectList+=pixels.map { point(it.first,it.second) };imageList+=d.points.map { Point(it.x.toDouble(),it.y.toDouble()) }
        }
        if (objectList.size<4) return reject("Marker image too small; move closer")
        val obj=MatOfPoint3f(*objectList.toTypedArray());val img=MatOfPoint2f(*imageList.toTypedArray())
        val camera=Mat(3,3,CvType.CV_64FC1);val distortion=MatOfDouble(0.0,0.0,0.0,0.0,0.0)
        val rv=Mat();val tv=Mat();val rot=Mat();val reprojection=MatOfPoint2f()
        try {
            camera.put(0,0,*k.map(Float::toDouble).toDoubleArray())
            if (!Calib3d.solvePnP(obj,img,camera,distortion,rv,tv,false,Calib3d.SOLVEPNP_SQPNP)) return reject("Camera pose solver failed")
            Calib3d.solvePnPRefineLM(obj,img,camera,distortion,rv,tv)
            Calib3d.Rodrigues(rv,rot)
            val t=tv.get(0,0)[0].let { x -> listOf(x,tv.get(1,0)[0],tv.get(2,0)[0]) }
            if (!t.all(Double::isFinite) || t[2] !in .15..5.0) return reject("Camera distance outside 0.15–5 m")
            // All fitted points must be in front of the camera; display front normal faces the observer.
            if (rot.get(2,2)[0]>=0 || objectList.any { p -> rot.get(2,0)[0]*p.x+rot.get(2,1)[0]*p.y+rot.get(2,2)[0]*p.z+t[2]<=.05 }) return reject("Fitted screen faces away from camera")
            Calib3d.projectPoints(obj,rv,tv,camera,distortion,reprojection)
            val fitted=reprojection.toArray()
            val rms=sqrt(fitted.indices.sumOf { i -> val dx=fitted[i].x-imageList[i].x;val dy=fitted[i].y-imageList[i].y;dx*dx+dy*dy }/fitted.size).toFloat()
            if (!rms.isFinite()) return reject("Non-finite camera projection")
            val used=detections
            // Project each marker independently so skipped tiny detections cannot shift ID/corner correspondence.
            fun project(points: List<Point3>): List<ImagePoint> {
                val input=MatOfPoint3f(*points.toTypedArray());val output=MatOfPoint2f()
                return try { Calib3d.projectPoints(input,rv,tv,camera,distortion,output);output.toArray().map { ImagePoint(it.x.toFloat(),it.y.toFloat()) } }
                finally { input.release();output.release() }
            }
            val projected=used.map { d -> val r=rects.getValue(d.id)
                MarkerPolygon(d.id,project(listOf(point(r.x.toFloat(),r.y.toFloat()),point((r.x+r.width).toFloat(),r.y.toFloat()),
                    point((r.x+r.width).toFloat(),(r.y+r.height).toFloat()),point(r.x.toFloat(),(r.y+r.height).toFloat())))) }
            val border=(0..24).map { geometry.point(it/24f,0f) }+(24 downTo 0).map { geometry.point(it/24f,1f) }
            val outline=project(border.map { Point3(it[0].toDouble(),it[1].toDouble(),it[2].toDouble()) })
            return Fit(MarkerEstimate(geometry.width,geometry.height,settings.curveDegrees,t[2].toFloat(),rms,objectList.size/4),projected,outline,
                CameraSpacePose((0..2).flatMap { row -> (0..2).map { column -> rot.get(row,column)[0].toFloat() } },t.map(Double::toFloat)))
        } catch (_: org.opencv.core.CvException) { return reject("Camera pose solver rejected marker geometry") }
        finally { obj.release();img.release();camera.release();distortion.release();rv.release();tv.release();rot.release();reprojection.release() }
    }
}
