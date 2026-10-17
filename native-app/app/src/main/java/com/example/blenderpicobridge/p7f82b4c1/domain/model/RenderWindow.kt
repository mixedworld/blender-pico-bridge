package com.example.blenderpicobridge.p7f82b4c1.domain.model

enum class RenderWindowMode { SCENE_ALIGNED, LIVE_DISPLAY }
data class RenderWindowState(val enabled: Boolean=false,val mode: RenderWindowMode=RenderWindowMode.SCENE_ALIGNED,
    val aspect: Float=16f/9f,val reset: Int=0,val retry: Int=0,val locked: Boolean=false)
data class RenderVideoStatus(val message: String,val streaming: Boolean=false,val recovering: Boolean=false,val stereo: Boolean=false)
