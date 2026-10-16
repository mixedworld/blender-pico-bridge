package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class CurvedScreenUseCaseTest {
    @Test fun zeroCurveIsExactlyFlatAndUsesOneMaskStrip() {
        val screen=CurvedScreenUseCase(1.2f,.7f,0f)
        assertEquals(1.2f,screen.chord,0f);assertEquals(0f,screen.edgeDepth,0f);assertNull(screen.radius)
        assertEquals(listOf(-.6f,.35f,0f),screen.point(0f,0f));assertEquals(1,screen.strips().size)
    }
    @Test fun cylinderMaskStripsMeetWithoutGapsAndKeepSubMillimetreSagError() {
        val screen=CurvedScreenUseCase(2f,1f,120f);val strips=screen.strips()
        assertTrue(screen.chord<screen.width);assertTrue(screen.edgeDepth>0f)
        for (i in 0 until strips.lastIndex) {
            val a=strips[i];val b=strips[i+1]
            assertEquals(a.x+cos(a.yaw)*a.length/2,b.x-cos(b.yaw)*b.length/2,.00001f)
            assertEquals(a.z-sin(a.yaw)*a.length/2,b.z+sin(b.yaw)*b.length/2,.00001f)
        }
        val error=screen.radius!!*(1-cos(screen.angle/64))
        assertTrue(error < .001f)
        assertEquals(screen.edgeDepth,screen.point(0f,.5f)[2],.00001f)
        assertEquals(0f,screen.point(.5f,.5f)[2],0f)
    }
    @Test fun impossibleOrNonfiniteCurvesAreRejected() {
        for (degrees in listOf(-1f,121f,Float.NaN)) {
            try { CurvedScreenUseCase(1f,.6f,degrees);fail() } catch (_:IllegalArgumentException) {}
        }
    }
}
