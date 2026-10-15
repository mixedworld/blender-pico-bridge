package com.example.blenderpicobridge.p7f82b4c1

import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.BridgeScreen
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.RoomScreen
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import androidx.compose.ui.Modifier
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme {
                val length = LocalPhysicalLengthConverter.current
                BridgeScreen(Modifier.windowConstraints(
                    minWidth = length.lengthToDp(.7f, LengthUnit.Meters),
                    minHeight = length.lengthToDp(.7f, LengthUnit.Meters),
                    minDepth = length.lengthToDp(.7f, LengthUnit.Meters),
                    maxWidth = length.lengthToDp(3f, LengthUnit.Meters),
                    maxHeight = length.lengthToDp(2.5f, LengthUnit.Meters),
                    maxDepth = length.lengthToDp(3f, LengthUnit.Meters),
                ))
            }
        }
        Stage(id = "BlenderRoom") { PicoTheme { RoomScreen() } }
    }
