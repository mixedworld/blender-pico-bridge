package com.example.blenderpicobridge.p7f82b4c1.ui.bridge

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.resize.CaptionBarType
import com.pico.spatial.ui.platform.resize.ContainerResizeType

fun SpatialAppScope.toolsWindow() {
    WindowContainer(id="BlenderTools",form=Form.Planar,
        defaultSize=WindowContainerSize(440.dp,608.dp),
        defaultCaptionBarType=CaptionBarType.Default,
        resizeType=ContainerResizeType.ContentSize,
        enableMaterialBackground=true,
        placement={ containers.firstOrNull { it.state.isFocused }?.let { Placement.right(it) } ?: Placement.none() },
    ) { PicoTheme { ToolsScreen() } }
}
