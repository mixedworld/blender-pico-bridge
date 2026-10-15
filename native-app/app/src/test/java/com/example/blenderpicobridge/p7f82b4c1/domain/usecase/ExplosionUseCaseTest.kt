package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import org.junit.Test
import org.junit.Assert.*

class ExplosionUseCaseTest {
    private val layer = ExplosionUseCase()
    @Test fun spreadingAndClosingAreReversibleAndBounded() {
        val amount = layer.fromSpan(.3f, 1.2f)
        assertTrue(amount > .3f)
        assertEquals(.3f, layer.fromSpan(amount, 1f / 1.2f), .00001f)
        assertEquals(1f, layer.fromSpan(.9f, 100f), 0f)
        assertEquals(0f, layer.fromSpan(.1f, .01f), 0f)
    }
    @Test fun invalidTrackingSamplesDoNotCorruptInspection() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(.4f, layer.fromSpan(.4f, it), 0f)
        }
    }
    @Test fun explosionLayersOverEachAuthoredFrameWithoutAccumulating() {
        val pose = NodePose("rim", listOf(1f,2f,3f), listOf(0f,0f,0f,1f), listOf(1f,1f,1f))
        val packet = FramePacket("s", 1, "r", null, "car", 1, false, 0f,
            listOf(pose), null, 1, 0, listOf(ExplosionPart("rim", listOf(2f,0f,-1f))))
        val first = layer.poses(packet, .5f)
        assertEquals(listOf(2f,2f,2.5f), first.first().position)
        assertEquals(first, layer.poses(packet, .5f))
        val animated = packet.copy(poses = listOf(pose.copy(position = listOf(3f,2f,3f))))
        assertEquals(listOf(4f,2f,2.5f), layer.poses(animated, .5f).first().position)
        assertEquals(listOf(pose), layer.poses(packet, 0f))
        assertEquals(listOf(pose), packet.poses)
    }
}
