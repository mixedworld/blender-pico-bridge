package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent

/** Render-only switch. Never disables entities, removes components or changes materials. Main thread only. */
internal class NativeModelVisibility {
    private var meshes = emptyList<Pair<ModelComponent, Boolean>>()
    var visible = true
        private set

    fun bind(root: Entity) {
        val next = mutableListOf<Pair<ModelComponent, Boolean>>()
        fun visit(entity: Entity) {
            entity.components[ModelComponent::class.java]?.let { next.add(it to it.isVisible) }
            entity.getChildren().forEach(::visit)
        }
        visit(root)
        meshes = next
        applyVisibility()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        applyVisibility()
    }

    private fun applyVisibility() {
        meshes.forEach { (mesh, originallyVisible) -> mesh.isVisible = visible && originallyVisible }
    }

    fun clear() { meshes = emptyList() }
}
