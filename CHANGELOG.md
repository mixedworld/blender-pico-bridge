# Changelog

## 0.8.0 — Connection-first tools

- Start disconnected on the Connection screen, with QR and manual pairing only. A sustained connection loss clears the virtual model and video and returns to pairing; healthy scene updates retain placement and links.
- Reject stale QR camera frames and results from cancelled scans; clear rejected pairing tokens. Camera scanning starts only when requested.
- Organize the native toolbox into Model, Render, Align and Connection tabs, with per-page scrolling and visible scroll cues. Remove duplicate page headings.
- Keep five-handle viewport alignment as the primary method; group marker, depth and touch alternatives under Experimental calibration.
- Add a render-border visibility option. Scene-aligned Face user + Fit to model hides the border automatically unless overridden.
- Make the Tools launcher movable by its grip, with a recall action and an optional open-palm shortcut. The palm shortcut defaults off and still requires physical hand-tracking validation.

Use the matching 0.8.0 add-on and APK. macOS/emulator validation covers connection cleanup, tabs and launcher interaction; fresh-camera QR behavior, palm comfort and Windows runtime remain unverified. Binaries are debug-signed development builds.

## 0.7.0 — Room and stereo render windows

- Room-first emulator setup, including an everyday Blender Cube walkthrough and generic experimental device instructions.
- Blender **Connect / Reconnect Emulator** restores ADB forwarding, pairs with the current server and launches the installed viewer in Room.
- Direct transparent viewport streaming from Blender through a local WebRTC encoder; optional stereo with manually entered eye separation. Cycles, Eevee, Workbench and viewport shading modes have separate capture paths.
- Movable render windows with Scene-aligned and Live display modes, border-only grabs, frame locking, Face user and enclosing-sphere Fit to model with safe close-up behavior.
- Native-model visibility can be disabled while keeping interaction. Workbench exports can use matte material viewport colours; authored PBR remains available.
- Five independent viewport alignment handles, curvature control and saved spatial alignment; improved grab-through, gesture continuity, scene-update placement retention and camera synchronization.
- Experimental QR pairing and calibration alternatives. Automatic calibration, physical-device compatibility, Windows runtime and physical stereo compositing are not certified by this release.

Use the matching 0.7.0 add-on and APK. Release binaries are debug-signed development builds; vendor installers, demo models, credentials and local test recordings are excluded.

## 0.3.0 — Emulator preview

Initial packaged emulator preview with native model import, supported PBR materials, timeline synchronization and separate-part explosion controls.
