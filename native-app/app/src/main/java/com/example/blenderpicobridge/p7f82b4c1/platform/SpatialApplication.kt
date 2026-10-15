package com.example.blenderpicobridge.p7f82b4c1.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.blenderpicobridge.p7f82b4c1.mainApp

class SpatialApplication : Application(), androidx.lifecycle.ViewModelStoreOwner {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
