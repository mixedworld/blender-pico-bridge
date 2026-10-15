package com.example.blenderpicobridge.p7f82b4c1.platform
import android.os.Bundle
import android.content.pm.ApplicationInfo
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val endpoint = intent.getStringExtra("bridge_endpoint")
        val token = intent.getStringExtra("bridge_token")
        if (endpoint != null && token != null) getSharedPreferences("pairing", MODE_PRIVATE).edit()
            .putString("endpoint", endpoint).putString("token", token).apply()
        // Reproducible visual capture seed, available only in a debuggable build.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (intent.hasExtra("inspection_hide_source")) getSharedPreferences("inspection-settings", MODE_PRIVATE).edit()
                .putBoolean("hideSource", intent.getBooleanExtra("inspection_hide_source", true)).apply()
            val amount = intent.getFloatExtra("inspection_explosion", 0f)
            getSharedPreferences("inspection-preview", MODE_PRIVATE).edit()
                .putString("mode", intent.getStringExtra("inspection_mode") ?: "SCALE")
                .putFloat("amount", if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f)
                .putBoolean("room", intent.getBooleanExtra("inspection_room", false))
                .putBoolean("monitor", intent.getBooleanExtra("inspection_monitor", false))
                .putBoolean("quiet", intent.getBooleanExtra("inspection_quiet", false)).apply()
            if (intent.hasExtra("inspection_reveal")) getSharedPreferences("inspection-preview", MODE_PRIVATE).edit()
                .putFloat("reveal", intent.getFloatExtra("inspection_reveal", 0f)).apply()
        }
        super.onCreate(savedInstanceState)
    }
}
