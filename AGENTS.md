# Blender PICO Bridge

Read README.md and docs/DEVELOPMENT.md for the supported emulator-only public setup. Original code is MIT; preserve third-party notices. This is an existing functional prototype, not a new scaffold.

- `blender-addon/pico_blender_bridge/`: Blender exporter, authenticated HTTP server and source-visibility lease.
- `native-app/`: Kotlin/PICO Spatial SDK 6.1.9; read its AGENTS.md before editing it.
- `PROTOCOL.md`: transport authority and content-addressed model revisions.
- `tools/package_release.py`: allowlisted add-on and verified APK packaging.
- `tools/audit_publication.py`: audit the exact Git index before any publication.

Run `python3 -m unittest discover -s tests -v`, the native Gradle unit/build tasks, and publication/package checks after relevant changes. Device/emulator install and source-only tests are separate evidence. Do not claim Windows, hands or headset performance from a Mac build.

Local credentials, evidence, recordings, vendor installs, demo models and generated releases are intentionally ignored. Never add them with force. The local working directory may contain private untracked files; publish only the reviewed Git tree and allowlisted release outputs. No GitHub remote or publication is implied by local preparation.
