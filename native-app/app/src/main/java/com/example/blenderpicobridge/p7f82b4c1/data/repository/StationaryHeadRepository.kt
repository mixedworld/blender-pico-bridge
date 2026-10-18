package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.os.SystemClock
import com.example.blenderpicobridge.p7f82b4c1.domain.model.RigidPose
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.StationaryHeadWindowUseCase
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.tracking.DataProvider
import kotlinx.coroutines.*

internal class StationaryHeadRepository {
    private val provider=HMDTrackingProvider()
    private val window=StationaryHeadWindowUseCase()
    private var job: Job?=null
    @Volatile var frames=0
        private set
    val diagnostic: String get()="state=${provider.state} support=${provider.supportState} frames=$frames"
    suspend fun start() {
        withContext(Dispatchers.Main.immediate) { provider.start() }
        job=CoroutineScope(currentCoroutineContext()).launch {
            provider.dataFlow.collect { data ->
                if(provider.state==DataProvider.State.STARTED && provider.supportState==DataProvider.SupportState.SUPPORTED) {
                    val p=data.hmdPose.position;val q=data.hmdPose.rotation
                    runCatching { RigidPose.fromQuaternion(listOf(p.x.toDouble(),p.y.toDouble(),p.z.toDouble()),
                        listOf(q.x.toDouble(),q.y.toDouble(),q.z.toDouble(),q.w.toDouble())) }.getOrNull()?.let {
                        frames++
                        window.add(SystemClock.elapsedRealtime(),it)
                    }
                }
            }
        }
    }
    fun steady(start: Long,end: Long)=window.steady(start,end)
    suspend fun stop() {
        job?.cancelAndJoin()
        withContext(NonCancellable+Dispatchers.Main.immediate) { provider.stop() }
    }
}
