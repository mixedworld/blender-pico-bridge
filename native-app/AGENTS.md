# Native Blender PICO Bridge

Existing PICO Spatial SDK project, BOM **6.1.9**. The application ID is `com.example.blenderpicobridge.p7f82b4c1`. Preserve the generated launch chain: `platform/LaunchActivity.kt` → `platform/SpatialApplication.kt` → `Main.kt`.

The supported public workflow is Preview in PICO OS 6.1 Emulator. Room/monitor/casting code is experimental. The experimental QR/depth/viewport-link branch is documented in docs/QR_ALIGNMENT.md at the repository root. Real-device QR pairing over USB and LAN is verified; depth size measurement, automatic world placement and curved monitor calibration are not yet verified. The project has not passed a complete visual-design fidelity review; do not claim one.

The experimental four-marker touch fallback uses HandTrackingProvider global index-tip positions, stable samples and opposite-hand thumb/index distance (independent of OS pinch readiness). Duplicate-millisecond callbacks must retain the sample window. Four contact captures are verified on the real headset; fitted-plane accuracy remains pending. It preserves previous calibration until explicit acceptance and fits a flat proxy only; physical accuracy remains unverified. See TouchTrackingEffect.kt and StableTouchCaptureUseCase.kt.

The toolbox is a tagged independent Planar window (`ToolsWindow.kt` / `ToolsScreen.kt`). Keep the compact `ToolsRecoveryButton` outside that window in both Preview and Room; native close must not strand controls. Room native movement and close/reopen have been verified by the user. Preview recovery still needs an interactive check.

Key files are `ui/bridge/scene/BridgeSceneRuntime.kt` for placement/animation/manipulation, `MonitorThreshold.kt` for occlusion, `data/repository/HttpBridgeRepository.kt` for transport, and `ui/bridge/components/BridgeControls.kt` for controls. Preserve separate placement, inspection, fit and authored-model transforms.

Use public PICO APIs and the declared SDK version. Entity/component and animation updates must happen on the main thread. Keep SpatialUI wrapped in PicoTheme; do not introduce Compose Material/Material3 components. Follow the applicable PICO development skill/workflow when editing SDK scene or UI behavior.

Build using JDK21 and a configured Android SDK: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` (Windows: `gradlew.bat`). The Gradle wrapper and Maven repository configuration are checked in. ARM64 and x86-64 native libraries are packaged; Windows runtime testing remains pending.

Keep the occlusion `.bundle`, `.scenes.json` and `.provenance.json` together under assets, with `noCompress` enabled. The project-authored graph retains unmanaged-export provenance; publication metadata redaction is documented in docs/DEVELOPMENT.md. Do not report it as a managed Editor final-scene pass.

See the root publication/package scripts and docs before publishing. Keep tokens, local.properties, host-specific PICO/IDE setup, model downloads, room captures and build artifacts out of Git. Input gestures, actual headset rendering and sustained performance require their own runtime evidence.
