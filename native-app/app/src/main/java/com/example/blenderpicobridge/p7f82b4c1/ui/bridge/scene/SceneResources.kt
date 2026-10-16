package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import com.pico.spatial.core.ecs.resource.Resource

/** Retained across component removal; the owning scene must explicitly close it. */
internal fun <T: Resource> T.sceneOwned(): T = apply { toGlobal() }
