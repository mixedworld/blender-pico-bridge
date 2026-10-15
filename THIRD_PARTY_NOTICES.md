# Third-party notices and asset scope

The [MIT license](LICENSE) covers original bridge code and project documentation. It does not replace third-party licenses or vendor terms.

| Component | How it is used | Notice / source |
| --- | --- | --- |
| Gradle wrapper | Wrapper scripts/JAR checked into `native-app/gradle` | Copyright notices retained in wrapper scripts; [Apache License 2.0](LICENSES/Apache-2.0.txt) and [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE) |
| Nayuki QR Code generator | Vendored `blender-addon/pico_blender_bridge/qrcodegen.py`; no pip install needed in Blender | Copyright Project Nayuki, MIT license retained in file. [Source revision 3c6d0b3](https://github.com/nayuki/QR-Code-generator/blob/3c6d0b3cefb4e049dc337e82237c9644399716a8/python/qrcodegen.py). |
| ZXing core 3.5.3 | Native app QR decoding; fetched by Gradle | [ZXing](https://github.com/zxing/zxing), Apache 2.0; no external Barcode Scanner app is required. |
| OpenCV 4.12.0 | ArUco detection in permissioned camera frames; fetched by Gradle | [OpenCV license](https://github.com/opencv/opencv/blob/4.12.0/LICENSE), Apache 2.0; dependencies retain their bundled notices. |
| Android / AndroidX, Kotlin and coroutines | Build/runtime dependencies fetched by Gradle | Each dependency retains its published license; Android libraries commonly use Apache 2.0. See their Maven artifacts and notices. |
| PICO Spatial SDK, SpatialUI and generated scaffold resources | Native runtime dependencies and original scaffold conventions | SDKs are fetched from PICO’s Maven repository. PICO terms/notices apply; [official resources](https://developer.picoxr.com/resources/). Retained scaffold/source asset copyright metadata is not relicensed by this project. |
| Blender and bundled glTF exporter | External authoring/export application | [Blender licensing](https://www.blender.org/about/license/); Blender is installed separately and is not redistributed here. |
| PICO Emulator | External test environment | Vendor distribution, AOSP/QEMU components and their included notices; download separately. |
| FFmpeg | Optional physical casting/recording helper | External executable, not bundled. Its license depends on the build; [FFmpeg legal information](https://ffmpeg.org/legal.html). It is unnecessary for emulator setup. |

The small occlusion material/scene is original project-authored content exported by PICO Spatial Editor. It retains PICO-generated metadata and honest unmanaged-export provenance. No vendor emulator, runtime SDK installation or Editor binaries are vendored in this repository.

The private workspace used additional scenes for experiments. **BMW models, model adaptations, their renders, downloaded robot assets, exported GLBs and headset room recordings are excluded from this repository and from the release packaging allowlist.** They must not be added under this MIT license. If a future demo uses an external asset, document its author, source, exact license and redistribution requirements separately.
