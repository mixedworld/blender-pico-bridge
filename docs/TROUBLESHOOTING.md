# Troubleshooting

[Back to README](../README.md)

| Symptom | Check / fix |
| --- | --- |
| `adb` is not found | Add Android SDK `platform-tools` to PATH, then open a new shell. PowerShell can run `adb.exe` explicitly. |
| No emulator appears in `adb devices` | Wait for PICO OS to boot; confirm you started PICO OS 6.1 rather than a phone AVD. Use the vendor/CLI emulator diagnostics. |
| More than one device appears | Always pass the real serial with `adb -s SERIAL`; do not rely on automatic selection. |
| “Connection lost” / connection refused | Keep Blender open and its bridge running. In add-on 0.6.3+, select **Connect via → USB / Emulator (ADB)** and click **Connect / Reconnect Emulator**. This restores forwarding and sends current pairing. For older versions, recreate `adb -s SERIAL reverse tcp:8766 tcp:8766` and connect to `http://127.0.0.1:8766` with the current token. |
| Blender cannot find ADB although Terminal can | GUI applications may inherit a different PATH. Set **Edit → Preferences → Add-ons → PICO Blender Bridge → ADB executable** to the installed `platform-tools/adb` (`adb.exe` on Windows). |
| Pairing fails / unauthorized | Copy the current token from Blender, Disconnect in the viewer, replace the token and Connect again. The example token is not a valid credential. |
| No geometry is published | Choose a collection containing meshes, or Visible Scene for the default cube. Cameras and lights are intentionally excluded. |
| Parts do not separate | Explode operates on distinct mesh objects. Use several objects; a joined mesh does not automatically become separate mechanical components. Try the separation slider first. |
| Geometry/material edits are missing | Click Refresh Model in Blender, then Refresh model in the viewer if needed. Viewer refresh does not re-export Blender. |
| Animation differs | Set the intended Blender frame range and refresh after action/clip changes. Try simple object keyframes first; deformation and simulation require format-specific checks/baking. |
| Materials look different | Use Principled BSDF and supported image maps. Bake unsupported procedural nodes. Native IBL/PBR does not match a Cycles studio render pixel-for-pixel. |
| Model is huge or tiny | Preview auto-fits the import. Check Blender scene units for source-scale work. A large import can take time; the download cap is 200 MB. |
| Hands are unreliable | In eye-hand mode, enable Settings → Labs → Hand Tracking, allow webcam access and use good lighting. Use built-in controls/sliders while diagnosing. Hands are experimental emulator input. |
| Mac emulator fails to start | Use the ARM package on Apple Silicon, keep the vendor files together and follow PICO’s virtualization/security setup. Do not disable system protections as a generic fix. |
| Windows emulator fails to start | Follow PICO’s host GPU/virtualization requirements. Windows runtime is not validated by this repository’s Mac tests. |
| APK reports an ABI error | Use the current universal debug APK containing both `arm64-v8a` and `x86_64`. Confirm you are using a PICO OS 6.1 image. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Another build may use the same application ID with a different signing key. Back up any settings before removing the old app; removal deletes its stored pairing/preferences. Prefer updates signed by the same maintainer. |
| Gradle cannot locate Android SDK | Set `ANDROID_HOME` or create ignored `native-app/local.properties` with `sdk.dir` pointing to the real SDK. |
| Gradle/JDK errors | Select JDK 21, use the checked-in wrapper, and install SDK Platform 35 / Build-Tools 35.0.0. Do not paste a different machine’s absolute paths. |
| Dependency resolution fails | Check access to Google Maven, Maven Central and the PICO Maven repository in `settings.gradle.kts`. A first build requires internet access. |
| Bundle load fails | Rebuild and run `pico-cli app bundle verify APK`. Keep the bundle in assets and `noCompress` enabled. Do not unzip the bundle into APK assets. |

For diagnosis, collect bounded logs without publishing pairing tokens:

```sh
adb -s emulator-5554 logcat -b crash -d
adb -s emulator-5554 logcat -d -s BlenderBridge
```

When asking for help, include host OS/CPU architecture, Blender version, emulator version/serial, build error text, and a minimal scene you have permission to share. Remove tokens, room captures and unrelated private paths first.
