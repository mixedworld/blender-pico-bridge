# Changelog

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
