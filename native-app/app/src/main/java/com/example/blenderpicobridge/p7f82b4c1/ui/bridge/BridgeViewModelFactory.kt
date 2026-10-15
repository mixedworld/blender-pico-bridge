package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.blenderpicobridge.p7f82b4c1.data.repository.HttpBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.usecase.ConnectBridgeUseCase
import com.example.blenderpicobridge.p7f82b4c1.domain.model.InspectionMode

class BridgeViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val repository = HttpBridgeRepository(context.applicationContext)
    private val preview = context.getSharedPreferences("inspection-preview", Context.MODE_PRIVATE)
    private val options = context.getSharedPreferences("inspection-settings", Context.MODE_PRIVATE)
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val mode = if (debuggable && preview.getString("mode", "SCALE") == "EXPLODE") InspectionMode.EXPLODE else InspectionMode.SCALE
        val amount = if (debuggable) preview.getFloat("amount", 0f) else 0f
        val room = debuggable && preview.getBoolean("room", false)
        val monitor = debuggable && preview.getBoolean("monitor", false)
        val quiet = debuggable && preview.getBoolean("quiet", false)
        val reveal = if (debuggable && preview.contains("reveal")) preview.getFloat("reveal", 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) else null
        preview.edit().clear().apply()
        return BridgeViewModel(repository.savedPairing(), ConnectBridgeUseCase(repository), mode, amount, room, monitor, reveal, quiet,
            options.getBoolean("hideSource", true), { options.edit().putBoolean("hideSource", it).apply() }) as T
    }
}
