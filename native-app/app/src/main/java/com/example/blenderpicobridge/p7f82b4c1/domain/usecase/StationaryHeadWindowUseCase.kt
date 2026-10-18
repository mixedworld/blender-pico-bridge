package com.example.blenderpicobridge.p7f82b4c1.domain.usecase

import com.example.blenderpicobridge.p7f82b4c1.domain.model.RigidPose

/** Receipt-time stability gate; deliberately does not claim exposure-clock synchronization. */
class StationaryHeadWindowUseCase {
    private val samples=ArrayDeque<Pair<Long,RigidPose>>()
    @Synchronized fun add(timeMs: Long,pose: RigidPose) {
        if(samples.lastOrNull()?.first?.let { timeMs<it || timeMs-it>150 }==true) samples.clear()
        samples.addLast(timeMs to pose)
        while(samples.size>1 && timeMs-samples.first().first>1300) samples.removeFirst()
    }
    @Synchronized fun steady(captureStartedMs: Long,finishedMs: Long): RigidPose? {
        if(finishedMs-captureStartedMs !in 0..250 || samples.size<20) return null
        val points=samples.filter { it.first>=captureStartedMs-1000 && it.first<=finishedMs }
        if(points.size<20 || points.last().first<captureStartedMs || finishedMs-points.last().first>120 ||
            captureStartedMs-points.first().first<900 || points.zipWithNext().any { (a,b) -> b.first-a.first>100 }) return null
        val mean=RigidPose.mean(points.map { it.second })
        if(points.any { mean.distance(it.second)>.004 || mean.angle(it.second)>Math.toRadians(.5) }) return null
        return mean
    }
}
