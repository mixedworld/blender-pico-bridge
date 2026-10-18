package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import kotlin.math.*

/** Depth in meters at TL, TR, BR, BL marker centers; dimensions only, no invented world pose. */
class MeasureScreenUseCase {
    operator fun invoke(points: List<List<Float>>, board: CalibrationBoard): MeasuredScreen {
        require(points.size == 4 && points.all { p -> p.size == 3 && p.all(Float::isFinite) }) { "Invalid screen depth" }
        fun minus(a: List<Float>, b: List<Float>) = List(3) { a[it]-b[it] }
        fun dot(a: List<Float>, b: List<Float>) = a.indices.sumOf { (a[it]*b[it]).toDouble() }.toFloat()
        fun length(a: List<Float>) = sqrt(dot(a,a))
        fun distance(a: Int,b: Int) = length(minus(points[a],points[b]))
        require(points.all { length(it) in .15f..5f }) { "Depth unavailable or out of range" }
        val top = minus(points[1], points[0]); val side = minus(points[3], points[0])
        val normal = listOf(top[1]*side[2]-top[2]*side[1], top[2]*side[0]-top[0]*side[2], top[0]*side[1]-top[1]*side[0])
        require(length(normal) > .001f && abs(dot(top,side))/(length(top)*length(side)) < .15f) { "Screen depth is not a stable rectangle" }
        require(abs(dot(normal,minus(points[2],points[0])))/length(normal) < .015f) { "Nonplanar screen: use manual alignment" }
        require(abs(distance(0,1)-distance(3,2))/distance(0,1) < .1f && abs(distance(0,3)-distance(1,2))/distance(0,3) < .1f) { "Unstable screen depth; hold still and retry" }
        val markers = board.markers.sortedBy { it.id }
        require(markers.map { it.id } == listOf(0,1,2,3))
        val innerWidth = ((markers[1].x+markers[1].width*.5f) - (markers[0].x+markers[0].width*.5f))/board.width
        val innerHeight = ((markers[3].y+markers[3].height*.5f) - (markers[0].y+markers[0].height*.5f))/board.height
        require(innerWidth in .1f..1f && innerHeight in .1f..1f)
        val width = (distance(0,1)+distance(3,2))*.5f/innerWidth
        val height = (distance(0,3)+distance(1,2))*.5f/innerHeight
        require(width in .1f..2f && height in .1f..2f) { "Measured screen exceeds supported size" }
        return MeasuredScreen(width,height)
    }
}
