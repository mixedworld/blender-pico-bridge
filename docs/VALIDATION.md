# Validation scope

**v0.7.0**, prepared on **18 October 2026**. This is an emulator-first development preview, not a store-ready release.

| Check | Evidence / scope |
| --- | --- |
| Host | macOS Apple Silicon; Blender 5.2.2 LTS |
| Native dependency | PICO Spatial SDK BOM 6.1.9; Emulator 6.1.0 |
| Python regression suite | 59 tests, 13 Blender/GPU-only skips outside Blender, no failures |
| Kotlin unit suite | 131 tests, no failures |
| Current functional emulator checks | Four sphere/scene/frame-picking checks plus one production streaming check passed before packaging; includes the cached imported model, hidden native bounds, transformed parents, manual placement restoration and receiver ownership |
| Fit visual confirmation | User confirmed the render rectangle stays clear of the native model from the farther viewing position; production numeric gap was about 3 cm |
| Render stream | Direct mono/stereo and colour/alpha transport exercised in emulator sessions; numerical colour checks and user brightness/colour comparison passed |
| Reconnect | New Blender action reopened and paired the already-installed emulator viewer, verified by authenticated client polling |
| Packaging | Explicit add-on allowlist; APK checked for both ARM64 and x86-64 native libraries, an uncompressed occlusion bundle matching source, known private token and local authoring paths |
| Publication | Exact staged Git tree audited before publication; local credentials, captures, third-party models and private Editor workspace excluded |
| Downloads | Official standalone Mac/Windows emulator archive URLs returned HTTP 200 on 18 October 2026; launcher filenames were previously inspected in vendor archives |

The release process also rebuilds the reviewed source in an isolated archive, packages that build, and checks the Room Cube path. The GitHub Actions checks build and package on Ubuntu; they do not run a PICO emulator. See the repository’s [Actions](https://github.com/mixedworld/blender-pico-bridge/actions) for the actual release commit’s results.

Standalone vendor-launcher execution and Windows runtime have not been tested here. An x86-64 APK does not establish a Windows runtime pass. Physical-device support is experimental; emulator tests do not establish physical hand-input comfort, camera/depth availability, binocular compositing, environment lighting or sustained performance. No specific device is claimed as a supported release target.

Five-handle alignment is the primary viewport alignment method. Marker, depth, touch and automatic world alignment remain optional experiments. Saved anchors and permissions are runtime-dependent. A full managed visual-fidelity/performance certification has not been completed.

Some historical local tests used private third-party scenes. Those models, their screenshots and room recordings are not part of the source or release assets. The public first sample uses Blender’s default Cube. The README architecture diagram is explanatory artwork, not a rendering-fidelity claim.
