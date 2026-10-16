# Blender PICO Bridge

Read README.md and docs/DEVELOPMENT.md for the supported emulator-only public setup. Original code is MIT; preserve third-party notices. This is an existing functional prototype, not a new scaffold.

- `blender-addon/pico_blender_bridge/`: Blender exporter, authenticated HTTP server and source-visibility lease.
- `native-app/`: Kotlin/PICO Spatial SDK 6.1.9; read its AGENTS.md before editing it.
- `PROTOCOL.md`: transport authority and content-addressed model revisions.
- `tools/package_release.py`: allowlisted add-on and verified APK packaging.
- `tools/audit_publication.py`: audit the exact Git index before any publication.

Run `python3 -m unittest discover -s tests -v`, the native Gradle unit/build tasks, and publication/package checks after relevant changes. Device/emulator install and source-only tests are separate evidence. Do not claim Windows, hands or headset performance from a Mac build.

Local credentials, evidence, recordings, vendor installs, demo models and generated releases are intentionally ignored. Never add them with force. The local working directory may contain private untracked files; publish only the reviewed Git tree and allowlisted release outputs. No GitHub remote or publication is implied by local preparation.

Source visibility must be scoped to the active Blender view layer. Excluded collection objects can return False from hide_get but raise from hide_set, stopping the timer midway through extraction. source_visibility.py skips absent objects, uses an explicit layer and isolates unavailable-object errors. Preserve original flags on return/lease expiry; tests/test_source_visibility.py covers the regression. Verification: 16 Python tests pass; three live hide/restore cycles preserved visibility and authored transforms in the multi-view-layer scene. The user confirmed on the real PICO that returning the extracted model makes it reappear in Blender and head-tracked viewport synchronization continues.
