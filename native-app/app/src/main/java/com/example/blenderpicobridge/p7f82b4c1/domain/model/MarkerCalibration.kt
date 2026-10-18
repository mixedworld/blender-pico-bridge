package com.example.blenderpicobridge.p7f82b4c1.domain.model

data class MarkerSettings(val sideMm: Float = 50f, val curveDegrees: Float = 0f, val confirmed: Boolean = false)
enum class MarkerPhase { NONE, SETUP, SCAN, REVIEW }
data class ImagePoint(val x: Float, val y: Float)
data class MarkerPolygon(val id: Int, val points: List<ImagePoint>)
data class MarkerEstimate(val arcWidth: Float, val height: Float, val curveDegrees: Float,
    val distance: Float, val errorPx: Float, val markerCount: Int)
/** Camera-space feedback only. Image bytes are memory-only, never saved or sent to Blender. */
data class MarkerScanFrame(val settings: MarkerSettings, val previewRgb: ByteArray,
    val observed: List<MarkerPolygon>, val projected: List<MarkerPolygon>, val outline: List<ImagePoint>,
    val estimate: MarkerEstimate?, val stableSamples: Int, val status: String,
    val previewWidth: Int = 320, val previewHeight: Int = 240,
    val worldRequired: Boolean = false,val worldAlignment: WorldMarkerAlignment? = null)

/** OpenCV camera axes: +X right, +Y down, +Z forward. Row-major rotation; translation in metres. */
data class CameraSpacePose(val rotation: List<Float>,val translation: List<Float>)

/** A local, measured rectified LEFT-camera profile. No image data or camera-to-world pose. */
data class MarkerCameraProfile(val width: Int,val height: Int,val sdkK: List<Float>,val measuredK: List<Float>,
    val rms: Float,val stdFx: Float,val stdFy: Float,val samples: Int)

/** Candidate crosses to the main-thread scene owner; no Entity work happens in the camera collector. */
data class WorldMonitorRequest(val scan: Int,val board: CalibrationBoard,val settings: MarkerSettings,
    val estimate: MarkerEstimate,val alignment: WorldMarkerAlignment)
