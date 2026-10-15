# Validation scope

Publication preparation checked on **15 October 2026**. This is an emulator-first development preview, not a store-ready release.

| Check | Result |
| --- | --- |
| Host | macOS, Apple Silicon |
| Blender baseline | 5.2.2 LTS |
| PICO SDK / knowledge | BOM 6.1.9 / matching 6.1 knowledge line |
| Development tools | CLI 0.6.0, Emulator 6.1.0, Editor 6.1.7 |
| Environment diagnostics | Usable, no errors; optional profiling/agent-host configuration warnings remain |
| Python transport and GLB tests | 5 passed |
| Kotlin tests | 18 passed, no failures or errors |
| Native debug build | Passed locally and from an isolated archive of the reviewed Git index |
| Isolated source build | Used installed JDK/Android SDK and shared Gradle dependency cache; no demo files, evidence, `.tools`, vendor box model or original checkout source files were needed |
| APK architecture packaging | Both ARM64 and x86-64 native libraries present |
| Occlusion asset packaging | One `.bundle`, stored uncompressed in APK; matches checked-in bundle bytes |
| Mac emulator install/launch | Passed; live model loaded with 65 nodes and 3 textures; Preview fit applied |
| Redacted asset runtime | Material loaded; 64 mesh parts / 21 PBR materials logged; a deterministic halfway mask view was visually checked in the emulator |
| Crash buffer during check | No entries |
| Add-on packaging | Allowlisted source ZIP generated; two successive packages produce identical add-on bytes |
| Publication scan | Git-index scan passed for private file categories, known local pairing token, local authoring paths and obvious private-key patterns; asset ZIP contents inspected too |
| Documentation | Local file links checked; official standalone downloads and their launcher filenames verified from vendor ZIP inventories |
| GitHub Actions | Workflow defined; remote execution has not run yet |

Windows commands and standalone ZIPs are documented, and an x86-64 APK is packaged, but **Windows runtime and standalone launcher execution have not been tested here**. Physical hand-input comfort, camera tracking, sustained GPU performance and complete visual-design fidelity remain separate checks. The emulator does not establish headset performance.

The native runtime screenshots used an existing local test scene. Third-party models, their screenshots and real-room recordings are deliberately not part of the public source tree. The README diagram is original explanatory artwork, not a screenshot or a claim of rendering fidelity.

Room, source hiding, physical casting and monitor extraction remain experimental code outside the public emulator getting-started flow. This preparation does not implement corner handles or curved/automatic monitor alignment.
