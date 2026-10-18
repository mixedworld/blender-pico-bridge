package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.content.Context
import android.util.Log
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.AlignmentSpaceUseCase
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Device-local UUID + anchor-relative geometry. No camera images, credentials or old Stage pose. */
class PersistentAlignmentRepository(context: Context) {
    private val prefs=context.getSharedPreferences("viewport-world-anchor",Context.MODE_PRIVATE)
    val hasSaved: Boolean get()=prefs.contains("alignment")
    fun read(): SavedAlignment? = prefs.getString("alignment",null)?.let(AlignmentCodec::decode)
    data class Created(val saved: SavedAlignment,val anchor: WorldAnchor)
    sealed interface Update {
        data class Located(val anchor: WorldAnchor): Update
        data class Status(val message: String): Update
        data object Removed: Update
    }
    /** Finish the disk transaction even if leaving Room cancels the caller. Never delete the old anchor first. */
    suspend fun save(global: AlignmentSnapshot): Created = withContext(NonCancellable) {
        val old=runCatching { read() }.getOrNull()
        val p=global.monitor.position
        val result=WorldTrackingManager.createAnchor(Vector3(p[0],p[1],p[2]),EulerAngles(),"Blender viewport")
        val anchor=when(result) {
            is WorldTrackingResult.Success -> requireNotNull(result.data) { "PICO returned no saved anchor" }
            is WorldTrackingResult.Error -> error("PICO anchor save failed (${result.errorCode})")
        }
        val saved=SavedAlignment(anchor.anchorUUID.toString(),AlignmentSpaceUseCase()(global,pose(anchor).inverse()))
        val encoded=AlignmentCodec.encode(saved)
        val committed=withContext(Dispatchers.IO) { prefs.edit().putString("alignment",encoded).commit() }
        if(!committed) {
            runCatching { WorldTrackingManager.removeAnchor(anchor.anchorUUID) }
            error("Alignment could not be written to device storage")
        }
        if(old!=null && old.anchorId!=saved.anchorId) runCatching { WorldTrackingManager.removeAnchor(UUID.fromString(old.anchorId)) }
        Log.i("BlenderBridge","ALIGNMENT_SAVED corners=4 curvature=${global.monitor.curveDegrees} staged=${global.stagedPose!=null}")
        Created(saved,anchor)
    }
    /** Subscribe before loading. Bounded, spaced retries allow the room mapper to become ready. */
    fun track(saved: SavedAlignment, initial: WorldAnchor? = null)=callbackFlow<Update> {
        val uuid=UUID.fromString(saved.anchorId)
        val subscription=WorldTrackingManager.subscribeAnchorUpdate { update ->
            if(update.anchor.anchorUUID==uuid) {
                if(update.event==AnchorUpdate.Event.REMOVED) trySend(Update.Removed)
                else if(update.event==AnchorUpdate.Event.ADDED || update.event==AnchorUpdate.Event.LOADED || update.event==AnchorUpdate.Event.UPDATED)
                    trySend(Update.Located(update.anchor))
            }
        }
        val loader=launch {
            if(initial!=null) { send(Update.Located(initial));return@launch }
            send(Update.Status("Restoring viewport alignment · look towards the saved viewport."))
            for(wait in listOf(0L,1500L,3000L,5000L,8000L)) {
                delay(wait)
                when(val result=WorldTrackingManager.loadAnchor(arrayOf(uuid))) {
                    is WorldTrackingResult.Success -> result.data?.firstOrNull { it.anchorUUID==uuid }?.let {
                        send(Update.Located(it));return@launch
                    }
                    is WorldTrackingResult.Error -> {
                        Log.i("BlenderBridge","ALIGNMENT_LOAD code=${result.errorCode}")
                        if(result.errorCode==-5) {
                            send(Update.Status("Grant Spatial data permission to restore the saved viewport."));return@launch
                        }
                    }
                }
            }
            send(Update.Status("Saved viewport not located. Look around nearby, or choose Align viewport to place it again."))
        }
        awaitClose { loader.cancel();subscription.cancel() }
    }
    companion object {
        fun pose(anchor: WorldAnchor): RigidPose {
            val transform=anchor.transform
            val p=transform.position;val q=transform.quaternion
            return RigidPose.fromQuaternion(listOf(p.x.toDouble(),p.y.toDouble(),p.z.toDouble()),
                listOf(q.x.toDouble(),q.y.toDouble(),q.z.toDouble(),q.w.toDouble()))
        }
    }
}

internal object AlignmentCodec {
    private fun floats(a: JSONArray,n: Int): List<Float> {
        require(a.length()==n)
        return List(n) { a.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
    }
    fun encode(saved: SavedAlignment): String {
        val s=saved.relative;val m=s.monitor
        fun pose(p: InspectionPose)=JSONObject().put("position",JSONArray(p.position)).put("quaternion",JSONArray(p.quaternion))
            .put("scale",p.scale).put("fitScale",p.fitScale).put("fitPosition",JSONArray(p.fitPosition))
        return JSONObject().put("version",1).put("anchor",saved.anchorId)
            .put("width",m.width).put("height",m.height).put("curve",m.curveDegrees)
            .put("position",JSONArray(m.position)).put("quaternion",JSONArray(m.quaternion))
            .put("corners",JSONArray(s.handles.corners.map { JSONArray(it) })).put("sagitta",s.handles.sagitta)
            .put("revision",s.revision ?: JSONObject.NULL).put("staged",s.stagedPose?.let(::pose) ?: JSONObject.NULL).toString()
    }
    fun decode(text: String): SavedAlignment {
        val o=JSONObject(text);require(o.getInt("version")==1)
        val id=UUID.fromString(o.getString("anchor")).toString()
        val m=MonitorCalibration(enabled=true,locked=true,width=o.getDouble("width").toFloat(),height=o.getDouble("height").toFloat(),
            curveDegrees=o.getDouble("curve").toFloat(),position=floats(o.getJSONArray("position"),3),quaternion=floats(o.getJSONArray("quaternion"),4))
        require(m.width in .1f..2f && m.height in .1f..2f && m.curveDegrees in 0f..120f)
        RigidPose.fromQuaternion(m.position.map(Float::toDouble),m.quaternion.map(Float::toDouble))
        val corners=o.getJSONArray("corners");require(corners.length()==4)
        val handles=MonitorHandleDraft(List(4) { floats(corners.getJSONArray(it),3) },o.getDouble("sagitta").toFloat())
        require(handles.sagitta.isFinite() && handles.sagitta in 0f..1f)
        val staged=o.optJSONObject("staged")?.let { p ->
            InspectionPose(floats(p.getJSONArray("position"),3),floats(p.getJSONArray("quaternion"),4),
                p.getDouble("scale").toFloat(),p.getDouble("fitScale").toFloat(),floats(p.getJSONArray("fitPosition"),3)).also {
                require(it.scale.isFinite() && it.scale>0 && it.fitScale.isFinite() && it.fitScale>0)
                RigidPose.fromQuaternion(it.position.map(Float::toDouble),it.quaternion.map(Float::toDouble))
            }
        }
        return SavedAlignment(id,AlignmentSnapshot(m,handles,staged,o.optString("revision").takeUnless { it.isEmpty() || it=="null" }))
    }
}
