# Blender → PICO Bridge

**Bring Blender models into a native PICO spatial viewport—without a headset.**

View a Blender collection in the PICO Emulator with PBR materials, inspect its separate parts, and follow Blender’s animation timeline. The Blender add-on serves the scene locally; a Kotlin app renders and manipulates it with PICO Spatial SDK.

![Blender to PICO bridge architecture](docs/assets/bridge-overview.svg)

**Prototype · Emulator-first · MIT**

This feature branch adds an experimental [QR pairing, five-handle viewport alignment and viewport link](docs/QR_ALIGNMENT.md). Build its add-on 0.6.1 and APK together. The 0.3.0 release below remains the emulator baseline; real-device QR pairing is verified; physical monitor fitting and automatic world alignment remain experimental.

This public setup targets **PICO OS 6.1 Emulator**. The macOS Apple Silicon workflow has been exercised; Windows instructions and x86-64 APK packaging are provided, but Windows runtime testing is still needed. Physical headset deployment and monitor extraction are experimental and outside this guide.

[Quick start](#quick-start-your-first-model-from-blender) · [Install & run](#install-and-run) · [Dependencies](#dependencies) · [Build from source](docs/DEVELOPMENT.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Protocol](PROTOCOL.md)

## Quick start: your first model from Blender

**First time?** Complete [Install and run](#install-and-run) once to install the Blender add-on and PICO viewer, start the emulator and set up its ADB connection. After that, this is the everyday path:

1. **Open Blender and a scene containing a mesh.** The default cube is enough for your first test; you do not need a downloaded model.
2. **Move your mouse over the 3D Viewport**—the large area showing your model—and press **N** to open its right-hand sidebar. You can also use **View → Sidebar** in that viewport.
3. **Click the vertical PICO Bridge tab** on the right edge of that sidebar. The panel inside is titled **PICO Blender Bridge**. If the tab is missing, [install and enable the add-on](#2-install-the-blender-add-on), then return here.
4. **Set Source → Visible Scene.** This publishes the visible meshes, including the default cube. You do not need to select the cube or choose a collection.
5. **Click Start Bridge.** If you already see **Stop Bridge**, the server is running; skip this click. On this feature branch, **Start when panel opens** starts it automatically. Click **Refresh Model** after changing the source, and wait for **Published … objects · … MB** at the bottom of the panel.
6. **Click Copy Pairing Details.** It copies two lines: the server URL, then the pairing token. Keep Blender open.
7. **Open Blender Bridge in the PICO Emulator → Connection.** Click **Use manual connection** if shown. Enter **Bridge URL** `http://127.0.0.1:8766` and the **pairing token** from the second copied line, then click **Connect**. For this emulator path, use the loopback URL even if the copied first line shows a LAN address. The [one-time emulator setup](#3-start-the-emulator-and-install-the-viewer) provides the required ADB forwarding.

**Success looks like:** the viewer says **Live from Blender** and shows your cube/model in Preview. If the scene has animation, press Play in Blender's Timeline; the viewer follows it. The default cube has no animation, so it will stay still.

For later sessions, repeat these steps with your own `.blend` file. To publish another model, change **Source**, then click **Refresh Model** in Blender. **Collection** lets you publish just one collection; choose it in the collection picker that appears. For an explosion, use several separate mesh objects. Opening another `.blend` stops the current bridge, so check that it has started again and reconnect with its current pairing details.

**Using a real headset on this feature branch?** With matching 0.6.1 add-on and app builds, choose **Connect via → Wi-Fi / LAN** in Blender and put both devices on the same reachable LAN. If the pairing code is not visible, click **Show large pairing QR**. In the headset, open **Connection → Scan Blender QR**, allow Camera, and look at the code. This replaces manual URL/token entry. Headset pairing and monitor alignment are experimental; continue with the [headset setup and alignment guide](docs/QR_ALIGNMENT.md).

## Room alignment: start with the five handles

**Five-handle placement is the primary alignment method.** After connecting a real headset, choose **Room → Align viewport** (called **Align monitor** in older builds).

1. Pinch the four cyan handles onto the four corners of **Blender’s 3D viewport image**, the area displaying the model.
2. Move the amber top-centre handle in/out to match the curvature at the middle of that viewport’s top edge.
3. Choose **Stage for take → Lock alignment**, then grab and pull the model through the frame. Reopen **Align viewport** to adjust it again.

**Align to the viewport, not the physical monitor edges or bezel.** Blender’s headers, Timeline, Outliner and other panels are outside the target rectangle. Using the whole monitor gives the bridge the wrong camera window and can offset or mis-scale the model. If you want a larger target, maximize the 3D Viewport first; keep its layout unchanged after alignment.

See the [five-handle walkthrough](docs/QR_ALIGNMENT.md#manual-alignment-with-five-handles) for details. Marker, depth and touch methods remain experimental alternatives.

## Optional direct Cycles / Eevee render window

Stream Blender's actual rendered pixels directly to a movable transparent window in PICO. No Chrome or Mac screen sharing is needed. Install the optional encoder once with `python3 tools/setup_render_worker.py`, then in Blender choose **PICO Bridge → Start viewport stream**. In the app enter **Room → Tools → Render window → Show render window**. Toggle **Scene-aligned** or **Live display**.

Optional [Stereo with manual eye separation](docs/STEREO_STREAMING.md) sends distinct left/right views together; Mono remains the default.

See the [direct-stream setup and codec comparison](docs/DIRECT_STREAMING.md) and [movement/mode guide](docs/RENDER_WINDOW.md). Cycles supports collection-based camera isolation. Eevee, Workbench, Solid, Material Preview and Wireframe stream visible geometry against a transparent world background; choose **Stream appearance** in Blender. Both backends have passed emulator video/alpha output tests on this Mac. Physical-device testing of this new path is pending.

## What it does

- **Native PBR preview:** GLB geometry and supported image textures, with native environment lighting.
- **Blender timeline sync:** play, pause and scrub in Blender; the viewer follows animation time and evaluated object poses.
- **Inspect the model:** move, rotate and scale using supported emulator input.
- **Explode separate parts:** switch the two-hand gesture between Scale and Explode, or use the separation slider. Assemble restores the parts.
- **Live updates:** publish supported geometry/material edits; Refresh Model explicitly exports a new revision.
- **Model-focused controls:** optional hover highlighting and a collapsible toolbox.

The base bridge transfers **3D scene data**. The optional render-window feature additionally streams Blender’s viewport video. PICO Connect, Unity, Blender MCP and an AI coding agent are **not required** to run the bridge. PICO Connect desktop mirroring has no verified emulator setup for this project.

## Dependencies

| Component | Required for | Tested / selected version | Download |
| --- | --- | --- | --- |
| Blender | Authoring, exporting and local bridge server | **5.2.2 LTS**; other versions unverified | [Blender downloads](https://www.blender.org/download/) · [5.2 archive](https://download.blender.org/release/Blender5.2/) |
| PICO Emulator | Running the viewer | **PICO OS 6.1** | [Official resources](https://developer.picoxr.com/resources/) · standalone packages below |
| Android SDK Platform-Tools (`adb`) | Installing the APK and forwarding the local connection | Recent Platform-Tools | [macOS / Windows downloads](https://developer.android.com/tools/releases/platform-tools) |
| Python | Packaging the add-on and optional launch helper | **3.9+**, standard library only | [Python downloads](https://www.python.org/downloads/) |
| JDK | Building the viewer from source | **21** used here | [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) |
| Android SDK | Building the viewer from source | **Platform 35**, **Build-Tools 35.0.0** | [Android Studio / command-line tools](https://developer.android.com/studio) |
| PICO Spatial SDK | Viewer dependency resolved by Gradle | **BOM 6.1.9** | [Official setup guide](https://developer.picoxr.com/document/spatial-sdk/set-up-development-environment/) |

The Gradle wrapper pins Gradle **8.13**, AGP **8.13.2** and Kotlin **2.1.20**. Do not install a separate Gradle version. An RGB webcam is optional for the emulator’s experimental hand tracking. Internet access is needed to obtain dependencies; model sync stays local.

### Standalone PICO Emulator downloads

These links are published on [PICO’s resources page](https://developer.picoxr.com/resources/) and were checked on **15 October 2026**:

- [macOS Apple Silicon / ARM package](https://lf-developer.picovr.com/obj/spatial-toolbox-mycis/oversea/emulator_original_packages/pico_oversea_emulator_20260902_mac.zip)
- [Windows package](https://lf-developer.picovr.com/obj/spatial-toolbox-mycis/oversea/emulator_original_packages/pico_oversea_emulator_20260902_win.zip)

These are vendor ZIP packages, not bridge installers: approximately 3.0 GB for Mac and 3.6 GB for Windows, before extraction. Keep each extracted package together. The Mac package contains `start-emulator.sh`; the Windows package contains `start-emulator.bat`. See the [standalone launch steps](docs/DEVELOPMENT.md#start-a-standalone-emulator-package), or use the [PICO CLI route](docs/DEVELOPMENT.md#provision-the-emulator-with-pico-cli) for managed provisioning. If links change, use the resources page. No Intel Mac standalone package is listed there.

## Install and run

Use the same computer for Blender and the emulator. This guide uses ADB reverse forwarding, so the viewer connects to `http://127.0.0.1:8766` without needing the host’s LAN address.

### 1. Obtain the bridge packages

Download the packages from [0.3.0 — Emulator preview](https://github.com/mixedworld/blender-pico-bridge/releases/tag/v0.3.0), or [build from source](docs/DEVELOPMENT.md):

- [Blender add-on ZIP](https://github.com/mixedworld/blender-pico-bridge/releases/download/v0.3.0/pico_blender_bridge-0.3.0.zip)
- [PICO Emulator viewer APK](https://github.com/mixedworld/blender-pico-bridge/releases/download/v0.3.0/blender-pico-bridge-debug.apk)
- [SHA-256 checksums](https://github.com/mixedworld/blender-pico-bridge/releases/download/v0.3.0/SHA256SUMS)

The release contains:

```text
pico_blender_bridge-0.3.0.zip    Blender add-on
blender-pico-bridge-debug.apk   PICO Emulator viewer (ARM64 + x86-64)
SHA256SUMS                    File integrity checksums
```

The development APK is debug-signed, not a store release. Download Blender, the emulator and other vendor dependencies from their own sites; they are not bundled here.

### 2. Install the Blender add-on

1. Open Blender **5.2.2 LTS**.
2. Open **Edit → Preferences → Add-ons → menu → Install from Disk…**.
3. Select `pico_blender_bridge-0.3.0.zip` without extracting it, then enable **PICO Blender Bridge**.
4. Close Preferences. Move your mouse over the **3D Viewport**, press **N** to open its right sidebar, and click the vertical **PICO Bridge** tab. Look for the panel titled **PICO Blender Bridge**.

Blender’s bundled glTF exporter must be available. The add-on needs no pip packages and no Blender MCP installation.

### 3. Start the emulator and install the viewer

Start a **PICO OS 6.1** emulator—not a standard Android phone emulator—and wait for its home environment to load. Put Platform-Tools on your terminal’s PATH, then check:

```sh
adb devices
```

Use the actual emulator serial from that output. The examples below use `emulator-5554`; change it if yours differs. From the directory containing the downloaded APK:

```sh
adb -s emulator-5554 install -r blender-pico-bridge-debug.apk
adb -s emulator-5554 reverse tcp:8766 tcp:8766
adb -s emulator-5554 shell am start -n com.example.blenderpicobridge.p7f82b4c1/.platform.LaunchActivity
```

The commands work in macOS Terminal and Windows PowerShell once `adb` is on PATH. In PowerShell, you can use `adb.exe` explicitly. Forwarding must be recreated after some emulator restarts.

### 4. Publish a model from Blender

For a first test, Blender’s default cube is enough:

1. Open a scene containing a mesh. Keep the cube for a simple smoke test, or use your own model.
2. In **PICO Bridge**, choose **Visible Scene**, or choose **Collection** and pick the model’s collection. Cameras and lights are excluded.
3. Click **Start Bridge** and keep Blender open. If **Stop Bridge** is already visible, it is running. This feature branch can start automatically when you open its panel; release 0.3.0 requires the Start click.
4. If you changed the source after startup, click **Refresh Model**. Wait for **Published … objects · … MB** at the bottom of the panel. If it asks you to choose geometry, set **Source → Visible Scene** with a mesh visible, or choose a nonempty collection, then refresh again.
5. Click **Copy Pairing Details**. This copies a URL on the first line and a private token on the second.

For an explosion test, use several separate mesh objects. A single mesh object will not turn into independently movable mechanical parts automatically. For animation, keyframe an object and set a short Blender frame range before publishing.

### 5. Connect in the emulator

In the viewer's **Connection** panel, click **Use manual connection** if shown, then enter:

| Field | Value |
| --- | --- |
| Bridge URL | `http://127.0.0.1:8766` |
| Pairing token | The second line copied from Blender |

Click **Connect**. A model may take a while to import on the emulator. **Preview** fits it to the spatial volume. Press Play in Blender to check timeline sync.

The copied LAN URL is intentionally replaced by loopback when using ADB reverse. A native Python launch helper is available if entering the token in the emulator is inconvenient: [private pairing and launch](docs/DEVELOPMENT.md#optional-pair-and-launch-helper).

### 6. Try the controls

| Control | Result |
| --- | --- |
| Scale | Two-hand movement scales/rotates the model |
| Explode | Two-hand spreading changes part separation |
| Separation slider / Assemble | Inspect parts without relying on two-hand input |
| Fit preview | Fit the model to the current Preview volume |
| Highlight on hover | Toggle model hover feedback; off by default |
| Hide tools / Show tools | Collapse and recall the toolbox |
| Connection → Refresh model | Reload the current published revision |

For camera-based hands, select the emulator’s **eye-hand mode**, then **Settings → Labs → Hand Tracking**. Keep your hands visible to the RGB webcam in a well-lit area. PICO documents this as experimental emulator input; it is not a guarantee of headset-like precision. Mouse input can be disabled while the tracked gaze point is active. See [PICO documentation](https://developer.picoxr.com/document/) (PICO Emulator → Hand Tracking).

To export edits or a changed animation action, click **Refresh Model in Blender**. The viewer’s refresh button reloads the current export; it does not ask Blender to re-export. Opening another `.blend` stops the bridge; start it again and update pairing if its token changes.

## Content support and current limits

- Use glTF-compatible Principled BSDF materials and image textures. Bake procedural materials, simulations and unsupported deformation as needed. Native PBR is not a Cycles render.
- Animation transport and ordinary object transforms have been exercised. Complex armatures, shape keys and deforming topology need their own compatibility checks.
- The native download limit is **200 MB per GLB**. Large exports and emulator rendering can be slow.
- Separate displays are not frame-locked. Inspection transforms stay local to the viewer and do not edit Blender.
- The app includes experimental Room/monitor controls, but this guide supports **Preview in the emulator**. The feature branch implements experimental touch alignment and known-size cylindrical geometry with camera feedback; physical accuracy, curved occlusion and automatic world placement are not verified.
- This is a functional prototype, not a finished visual-design or performance certification. Read the [validation scope](docs/DEVELOPMENT.md#validation-scope).

## Project layout

```text
blender-addon/   Blender add-on and HTTP server
native-app/      Kotlin app, Gradle wrapper and runtime occlusion asset
editor-asset/    Source for the authored occlusion material
tools/         Packaging, launch and optional recording helpers
tests/         Python transport/GLB tests
docs/          Setup, troubleshooting, publication and dependency notes
PROTOCOL.md     Scene/state transport contract
```

## Security, licensing and contributing

The bridge uses a bearer token over **unencrypted local HTTP**. Use ADB reverse or a trusted private network; do not expose port 8766 to the internet. Keep tokens out of commits, screenshots and public `.blend` files. The viewer has one bounded source-visibility endpoint; there is no general remote-Python execution API. See [SECURITY.md](SECURITY.md).

Original project code is [MIT licensed](LICENSE). Vendor SDKs, Gradle components and Blender keep their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md). The repository does not include vendor installers, BMW/demo model downloads, private pairing files or room recordings. Blender and PICO names identify compatibility; no vendor endorsement is implied.

Contributions are welcome. Include the host OS, emulator version and a small shareable reproduction scene when reporting a problem. Start with [CONTRIBUTING.md](CONTRIBUTING.md).

For experimental head-tracked desktop perspective, see [Room viewport synchronization](docs/QR_ALIGNMENT.md#room-head-tracked-monitor-camera): align and lock the monitor, enable Blender's **Allow PICO viewport control**, then enable **Link Blender viewport** in PICO. The bridge creates a temporary viewport camera that follows the tracked viewpoint and compensates native model dragging, then restores the previous view when stopped. Curved-screen projection currently uses a flat chord approximation.
