package com.example.blenderpicobridge.p7f82b4c1.ui.bridge.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.blenderpicobridge.p7f82b4c1.domain.model.ScanPurpose
import com.example.blenderpicobridge.p7f82b4c1.ui.bridge.*

/** Permission result belongs to this scan request; leaving the surface stops acquisition. */
@Composable
internal fun CameraPermissionEffect(state: BridgeUiState, event: (BridgeUiEvent) -> Unit) {
    val context = LocalContext.current
    val latestRequest by rememberUpdatedState(state.scanRequest)
    var permissionRequest by remember { mutableIntStateOf(-1) }
    val registry = LocalActivityResultRegistryOwner.current
    if (registry != null) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (latestRequest == permissionRequest) event(if (grants.values.all { it }) BridgeUiEvent.ScanAuthorized
                else BridgeUiEvent.ScanFailed("Camera or spatial-data permission denied. Retry or use manual setup."))
        }
        LaunchedEffect(state.scanRequest) {
            if (state.scanPurpose != null) {
                val permissions = if (state.scanPurpose == ScanPurpose.DEPTH) arrayOf(Manifest.permission.CAMERA,"com.picovr.permission.SPATIAL_DATA") else arrayOf(Manifest.permission.CAMERA)
                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) event(BridgeUiEvent.ScanAuthorized)
                else { permissionRequest = state.scanRequest; launcher.launch(permissions) }
            }
        }
    } else {
        LaunchedEffect(state.scanRequest) {
            if (state.scanPurpose != null) {
                val granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    (state.scanPurpose != ScanPurpose.DEPTH || context.checkSelfPermission("com.picovr.permission.SPATIAL_DATA") == PackageManager.PERMISSION_GRANTED)
                event(if (granted) BridgeUiEvent.ScanAuthorized else BridgeUiEvent.ScanFailed("Grant Camera / Spatial data in app settings, then Retry scan."))
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, action -> if (action == Lifecycle.Event.ON_STOP) event(BridgeUiEvent.StopScan) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); event(BridgeUiEvent.StopScan) }
    }
}
