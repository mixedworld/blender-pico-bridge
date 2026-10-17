package com.example.blenderpicobridge.p7f82b4c1.data.repository

import android.view.Surface
import com.example.blenderpicobridge.p7f82b4c1.domain.model.*

interface RenderVideoRepository {
    fun open(surface: Surface,pairing: Pairing,aspect: Float,status: (RenderVideoStatus)->Unit): AutoCloseable
}
