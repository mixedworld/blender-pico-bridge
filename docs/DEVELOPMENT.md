# Build and develop

[Back to README](../README.md)

## Build prerequisites

Install JDK **21**, Python **3.9+**, and Android SDK **Platform 35**, **Build-Tools 35.0.0** and **Platform-Tools**. Android Studio is the easiest SDK manager; its bundled JDK 21 can be selected as Gradle’s JDK. A separate NDK installation is not required by this project’s own code.

Vendor references: [Android Studio](https://developer.android.com/studio), [SDK Manager](https://developer.android.com/studio/intro/update#sdk-manager), [PICO Spatial SDK setup](https://developer.picoxr.com/document/spatial-sdk/set-up-development-environment/).

The app uses PICO’s public Maven repository, declared in `native-app/settings.gradle.kts`. Keep BOM **6.1.9** with a **6.1** emulator. The checked-in Gradle wrapper downloads Gradle; a global Gradle install and the PICO Editor are not necessary for ordinary builds.

## Build the viewer

Download/clone this repository and open a terminal at its root. No developer-specific environment script is needed.

### macOS

Set `JAVA_HOME` to your JDK 21 installation and `ANDROID_HOME` to your actual Android SDK directory. Alternatively, open `native-app` in Android Studio, choose the SDK and JDK there, and let it create its ignored `local.properties`.

```sh
cd native-app
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
cd ..
```

If the wrapper is not executable after extracting a source archive, run `chmod +x native-app/gradlew` once.

### Windows PowerShell

Set `JAVA_HOME` and `ANDROID_HOME` to the installed JDK and SDK directories, or configure them through Android Studio. Open a new terminal after changing environment variables.

```powershell
cd native-app
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
cd ..
```

To use `local.properties` directly, create it inside `native-app`. A Windows example is `sdk.dir=C:/Android/Sdk`; replace it with the real directory and use forward slashes. Never commit this file.

Both builds write `native-app/app/build/outputs/apk/debug/app-debug.apk`. The APK includes ARM64 and x86-64 libraries for the corresponding emulator architectures. Windows runtime validation remains outstanding.

## Package the add-on and APK

From the repository root:

```sh
python3 tools/package_release.py
```

On Windows use `py -3 tools/package_release.py` if Python is not exposed as `python3`. This emits the add-on ZIP, the built APK and `SHA256SUMS` into `dist/release/`. It refuses an APK whose occlusion bundle differs from source, whose bundle is compressed in the APK, or which lacks either required ABI. It also checks package payloads for known local path prefixes and, when present locally, the private pairing token.

For the add-on alone, use `python3 tools/package_release.py --addon-only`. ZIP contents come from the three add-on source files and the MIT license, with fixed metadata for reproducibility. No local scenes, tokens or installed Python packages are included.

The APK is debug-signed using the build machine’s debug keystore. Prefer a release asset built once for all users; independently built debug APKs can have different signatures. See [installation troubleshooting](TROUBLESHOOTING.md).

## Start a standalone emulator package

Download the matching ZIP linked from the README and extract the complete archive to a writable directory. Leave the package’s `picoemulator`, `platform-tools`, system images and configuration folders together.

- **macOS Apple Silicon:** open Terminal in the extracted `swan_rls_spaceos_oversea_K_pico_emulator_mac_20260902` directory and run `bash start-emulator.sh`.
- **Windows:** open the extracted `swan_rls_spaceos_oversea_K_pico_emulator_win64_20260902` directory and run `start-emulator.bat` (PowerShell: `.\start-emulator.bat`).

The launchers were verified as files in the official ZIPs; running these standalone launchers has not been tested here. Review the vendor scripts before running them: the Mac launcher can request administrator access for quarantine handling, and the Windows launcher changes a console preference temporarily. This project does not change system security settings. If the launcher cannot locate its `Pico` AVD or fails host checks, use PICO’s setup guidance or the CLI provisioning route instead of deleting existing emulator data.

Both packages include `platform-tools/adb` (`adb.exe` on Windows). You can use that executable directly for the README’s install/forward/launch commands if it is not on PATH. Wait for the PICO home environment to finish loading before installing the bridge APK.

## Provision the emulator with PICO CLI

The CLI is optional if you already have a working PICO OS 6.1 Emulator. Its setup manages vendor tooling separately from this repository; it is not a bridge runtime dependency.

Follow the [official installation guide](https://developer.picoxr.com/document/pico-cli/quickstart-common/) and [command reference](https://developer.picoxr.com/document/pico-cli/command-reference/). Install a current Node.js LTS satisfying the CLI’s requirements, then:

```sh
npm install -g @picoxr/pico-cli
pico-cli setup
```

Choose **Spatial** and the **6.1** SDK/knowledge line during setup. Complete the setup prompts rather than copying another developer’s local environment file. Then provision and start an emulator:

```sh
pico-cli doctor --platform spatial --format json
pico-cli emulator install 6.1
pico-cli emulator create --bundle-version 6.1 --avd BlenderBridge_6_1
pico-cli emulator start --avd BlenderBridge_6_1
pico-cli device list
```

Use `pico-cli emulator list` if you already have a suitable AVD. Pick its real name instead of creating a duplicate. Refer to your installed command’s `--help` if options differ. These commands can download several gigabytes of vendor files and require virtualization support.

The standalone vendor ZIP download links are in the [README](../README.md#standalone-pico-emulator-downloads). Neither route requires Unity. Only install Spatial Editor if you intend to modify the bundled material.

## Optional pair and launch helper

The primary setup uses the viewer’s Connection panel. For easier entry, the helper accepts a private pairing file. Copy the example first:

```sh
cp pairing.example.json .pairing.json
```

PowerShell equivalent: `Copy-Item pairing.example.json .pairing.json`.

Edit `.pairing.json`: keep endpoint `http://127.0.0.1:8766` and replace `token` with the second line from Blender’s **Copy Pairing Details**. On macOS/Linux run `chmod 600 .pairing.json`. Do not save the token in the example or commit it.

With Blender’s bridge and the emulator running:

```sh
python3 tools/launch_native.py --device emulator-5554 --apk dist/release/blender-pico-bridge-debug.apk
```

The helper installs the APK, adds the reverse forwarding, and launches the app with pairing extras. It does not print the token in its output, but Android intent extras and process arguments are not a secure secret store. Use it only on a trusted development computer. It does not start Blender or the emulator. Re-pair after a token change; opening another `.blend` stops the server.

## Tests and package checks

```sh
python3 -m unittest discover -s tests -v
python3 tools/audit_publication.py
```

Run the Gradle unit/build command above for Kotlin changes. If PICO CLI is installed, also run:

```sh
pico-cli app bundle verify native-app/app/build/outputs/apk/debug/app-debug.apk
```

A passing check must report an uncompressed `.bundle` in APK assets. The release packager performs the same ZIP storage check without requiring the CLI.

GitHub Actions runs Python tests and the Gradle unit/build/package checks. It does not run a PICO emulator on GitHub-hosted runners and does not automatically publish releases.

## Authored occlusion asset

`native-app/app/src/main/assets/monitor_occlusion.bundle` is checked in so users can build without Editor. Its scene metadata and provenance sit next to it. The graph source is under `editor-asset/src/main/res3d/MonitorOcclusion/Sources/`.

This asset was exported with **Editor 6.1.7**, with an explicit author-approved unmanaged export because that Editor lacked the managed workflow’s required orthographic capture API. Its provenance records `managed: false`; no managed final-scene pass is claimed. For publication, source-cache path prefixes in `AssetInfo.json` were redacted at equal byte lengths; shader, mesh, scene and material payloads were retained. The redacted asset must pass build/import checks before release.

Normal builds consume the checked-in asset; they do not repackage it. Retain `androidResources.noCompress += "bundle"`. To author a replacement, follow [PICO Spatial Editor tooling](https://developer.picoxr.com/resources/) and preserve runtime material path `monitor_occlusion/Root/MonitorMask/DefaultMaterial`.

## Validation scope

The Mac development workflow has exercised GLB import, embedded image materials, object animation and play/pause/scrub sync, part separation, unit tests and APK installation on PICO Emulator 6.1.0. Tests and packaging checks are repeatable from source. The current publication checks are recorded in [VALIDATION.md](VALIDATION.md).

Windows commands are supplied for reproducibility, but this project has not been run on a Windows host here. A successful x86-64 build is not a Windows runtime pass. Real hand-tracking comfort, physical lighting, sustained frame rate and full visual-design fidelity are separate validation work.

Room, monitor occlusion, source hiding and physical casting exist as experimental code. They are deliberately outside the emulator-only getting-started path. Corner handles, curved monitor occlusion and automatic monitor calibration are not implemented.
