package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.content.Context
import android.os.Build
import com.example.blenderpicobridge.p7f82b4c1.domain.model.MarkerCameraProfile
import org.json.JSONObject

/** Local calibration only, scoped to this device firmware. Never substitutes a universal focal multiplier. */
internal class MarkerCameraProfileRepository(context: Context) {
    private val prefs=context.getSharedPreferences("marker-camera-profile",Context.MODE_PRIVATE)
    fun load(): MarkerCameraProfile? = runCatching {
        if(prefs.getString("firmware",null)!=Build.FINGERPRINT) return null
        val json=JSONObject(prefs.getString("profile",null) ?: return null)
        require(json.getInt("version")==1 && json.getString("eye")=="left")
        fun matrix(key: String)=json.getJSONArray(key).let { a -> List(a.length()) { a.getDouble(it).toFloat() } }
        MarkerCameraProfile(json.getInt("width"),json.getInt("height"),matrix("sdkK"),matrix("measuredK"),
            json.getDouble("rms").toFloat(),json.getDouble("stdFx").toFloat(),json.getDouble("stdFy").toFloat(),json.getInt("samples"))
    }.getOrNull()
}
