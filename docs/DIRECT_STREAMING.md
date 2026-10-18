# Direct Blender video with transparency (experimental, 0.8.0)

Blender supplies rendered RGBA pixels to a local encoder process. WebRTC carries them directly to the PICO app. No browser, screen recording permission, PICO Connect or desktop sharing is needed. **Cycles, Eevee and Workbench** share the same encoder and receiver. You can also stream Solid, Material Preview or Wireframe viewport shading.

## Options investigated

| Approach | Alpha and scene context | Decision |
| --- | --- | --- |
| NVIDIA CloudXR | XR streaming stack with scene/application integration. Its current server requires an NVIDIA GPU and Windows/Linux; the Apple framework is a client. | An interesting future server backend, unsuitable for this Apple Silicon Mac. [Server requirements](https://docs.nvidia.com/cloudxr-sdk/latest/requirement/runtime_req.html), [Apple client](https://docs.nvidia.com/cloudxr-sdk/latest/requirement/cloudxrkit_req.html). |
| VP8/VP9 WebM alpha | WebM can carry an extra alpha bitstream in container metadata. Ordinary WebRTC video decoding does not automatically reconstruct that WebM container extension. | Useful for files, insufficient for our existing live Android decoder. [WebM alpha design](https://wiki.webmproject.org/alpha-channel). |
| HEVC with alpha | Apple's layered HEVC format supports transparency; an ordinary decoder may ignore the alpha layer. | PICO/Android WebRTC interoperability is unverified. [Apple's format explanation](https://developer.apple.com/videos/play/wwdc2019/506/). |
| Separate colour and mask tracks | Standard codecs, independent bandwidth tuning. Requires explicit frame pairing; delayed masks create motion fringes. | Possible future optimization; adds synchronization complexity. |
| **Colour and mask in one VP8 frame** | Top half colour, bottom half greyscale coverage. Both halves share one timestamp and decoded frame. GPU reconstructs RGBA. | **Implemented.** Uses standard WebRTC VP8 transport and decoders. Similar packing is described as Method 3 in the WebM alpha design. |

Transparent pixels have their RGB cleared before encoding. This avoids spending colour detail on the removed environment. It is a scene-aware coverage optimization, not a geometry-aware video codec or NVIDIA reprojection implementation. Mask pixels still consume bandwidth. VP8 is lossy: fine hair, glass and soft edges can show compression artefacts.

## Install once

Use the add-on **0.8.0** and native APK from the same checkout. Packaged builds also include `render-worker-setup.zip`: extract it and run the helper inside its `tools` folder if you are not using a repository checkout. Install the optional encoder environment using an external **Python 3.10+**:

```sh
# macOS / Linux, from the repository directory
python3 tools/setup_render_worker.py
```

```powershell
# Windows, from the repository directory (not runtime-tested yet)
py -3 tools/setup_render_worker.py
```

The helper creates a user-local virtual environment and installs pinned aiortc/PyAV plus NumPy. It leaves Blender's embedded Python untouched. For a custom location, pass `--directory PATH`, then paste the printed Python executable into **Blender → Edit → Preferences → Add-ons → PICO Blender Bridge → Render worker Python**. An empty path uses the helper's default location. Set maximum image side and target fps there too (defaults **960 px / 15 fps**, range 128–1280 px and 1–30 fps).

The worker needs no standalone ffmpeg command: PyAV uses its FFmpeg libraries. Encoding currently uses aiortc's CPU VP8 path; no VideoToolbox/NVENC acceleration is claimed. [aiortc API](https://aiortc.readthedocs.io/en/latest/api.html), [PyAV](https://pyav.org/docs/stable/).

## Open Blender → stream to the emulator

1. Open your scene. Choose **Cycles**, **Eevee** or **Workbench** under **Render Properties → Render Engine**.
2. Hover over the **3D Viewport**, press **N**, and open **PICO Bridge**. Choose the source and click **Start Bridge** if needed.
3. For **Cycles model-only rendering**, choose **Source → Collection** and your model's collection. Other visible geometry in the active view layer is hidden from camera rays while retaining reflection, shadow and lighting contributions. Excluded asset-library templates are never modified; duplicated temporary masks are restored when an object joins the source. **Visible Scene** deliberately includes visible floors, walls and scenery. World illumination remains active behind a transparent Film background.
4. Click **Start viewport stream**. The selected viewport switches to Rendered shading. Keep it open; a minimized/closed viewport cannot supply live GPU frames. **Eevee, Workbench and viewport shading modes include all visible geometry**; hide unwanted background geometry yourself or use a model-only scene. Its camera-ray isolation is not equivalent to Cycles.
5. Start the PICO OS 6.1 emulator, install the matching APK and pair as in the [main setup](../README.md). With this repository's configured Android tools, `python3 tools/launch_native.py --room --apk native-app/app/build/outputs/apk/debug/app-debug.apk` opens experimental Room on the default emulator. It reads your private `.pairing.json`; never publish that file. ADB reverse carries HTTP signaling; WebRTC additionally needs a working direct ICE route. Both were verified on the Mac emulator setup.
6. In PICO, open **Tools → Render → Show render window**. Choose **Live display** first to see Blender's current view. Use **Scene-aligned** with **Allow PICO viewport control** enabled for the moving, head-tracked window. Existing movement/resize controls are unchanged; see [render-window modes](RENDER_WINDOW.md).
7. Click **Stop viewport stream** in Blender when finished. Film transparency, Cycles camera-visibility flags, shading and the draw callback are restored. Saving the .blend also stops the stream first so temporary settings are not saved. Restart the stream after saving.

## Choose the streamed appearance

Stop the stream, choose **Stream appearance** in Blender's **PICO Bridge** panel, then start again. Mono/Stereo is a separate setting and works with every supported appearance.

| Stream appearance | What is rendered |
| --- | --- |
| **Scene renderer** (default) | The scene's selected Cycles, Eevee or Workbench engine. Choose Workbench under **Render Properties → Render Engine** to use its render settings. |
| **Solid** | Solid viewport shading, including its studio lighting, material/object/random colours, shadows and cavity settings. |
| **Material Preview** | The viewport's material-preview shading and lighting. Blender requires Cycles or Eevee selected; Workbench cannot enable this mode. |
| **Wireframe** | The viewport's geometry wireframe style, with a transparent background. Line colours follow the viewport's settings. |

The captured image omits viewport chrome, gizmos and editor overlays. Changing **Render Engine** or the viewport shading type during a stream ends that capture cleanly; restart with the desired appearance. Other shading controls can update live. Stopping restores the previous shading and Film transparency. If an engine change makes the previous shading unavailable, Blender's valid current mode is retained.

Only **Cycles + Scene renderer** uses camera-ray source isolation and the Cycles HDR colour path. All other appearances use colour-managed GPU offscreen frames; they do not apply that transform twice. Visible background geometry remains visible even though the world background is transparent.

## Rendering and lifecycle

**Cycles:** capture the public Python render engine `view_draw` callback immediately after its beauty pass and before viewport overlays. Generic `GPUOffScreen.draw_view3d` returned empty Cycles pixels in our Blender 5.2.2 probe, so it is not used for Cycles. The original callback is restored on stop. Since 0.5.1, capture reads **floating-point scene-linear HDR**, then applies Blender's active OpenColorIO view/look, exposure and display gamma before quantizing to SDR sRGB. Reading that framebuffer directly as bytes bypasses Filmic/AgX and clips highlights; it must not be restored. The current CPU converter supports the standard view/look/exposure/gamma and working-space settings. Custom view curves and white balance currently stop direct Cycles capture with an explicit message; switch them off for this experimental path. Eevee applies its settings through Blender's own offscreen draw instead.

**Eevee:** render through public `GPUOffScreen.draw_view3d`, with transparent Film, no background, overlays or gizmos. Its GPU framebuffer is read on Blender's draw thread. This leaves the backend open for future Eevee-specific quality/isolation work. [Official Blender offscreen example](https://github.com/blender/blender/blob/main/doc/python_api/examples/gpu.9.py).

The bounded parent/worker pipe transports RGBA plus dimensions and monotonic capture time. Latest-frame queues drop old captures rather than accumulating latency. Blender API/GPU calls and immutable OCIO-processor selection stay on Blender's main/draw thread. A bounded pixel-writer thread applies the float colour conversion; a separate virtual-environment process resizes and encodes. Colour conversion never calls bpy or GPU APIs on the worker thread. Associated colour is unassociated before transport, with zero RGB outside coverage. The receiver uses a public WebRTC GL drawer to reconstruct RGBA into an alpha-capable EGL Surface, then a transparent PICO VideoMaterial with depth testing and no depth writes.

There is one authenticated local publisher/viewer signaling session, no audio and no external STUN/TURN/cloud service. WebRTC media uses DTLS-SRTP. Pairing/control HTTP retains the existing trusted-LAN security model. Do not expose its port publicly. During Blender imports, exports or temporary GPU capture interruptions, the encoder repeats the last complete image and keeps the connection alive. Fresh pixels resume automatically. Receiver/network failures reconnect automatically with a new offer. Scene-aligned keeps its camera through short recovery, including surface/aspect changes after video has first arrived; a prolonged video outage releases ownership after a bounded recovery period (15 seconds in the receiver; Blender also requires fresh publisher/viewer leases, expiring after 12/20 seconds respectively). Explicit off/Live display still releases immediately. Reconnecting camera streams and viewport resizing update the same temporary camera without restoring the old view in between. **Retry stream** remains available for manual recovery.

Mono sends one colour/coverage image; optional [Stereo with manual eye separation](STEREO_STREAMING.md) sends two eye views together. Neither mode carries per-pixel scene depth, reprojection, or arbitrary remote 3D occlusion. Native geometry remains independent. Alpha removes background pixels but cannot replace a depth stream for correct intersections with arbitrary real-world objects. Scene-aligned projection and native-model matching retain the existing flat-window and assembled-model limitations.

## Verification and remaining limits

On Blender 5.2.2 on the development Mac, both Cycles and Eevee produced real transparent GPU frames. Earlier Blender versions have not been tested for this capture feature. A real save-copy test restored Film, shading, camera visibility and the Cycles callback, stopped the worker, and kept the bridge server running. On PICO OS 6.1 **emulator-5554**, each backend passed three successive WebRTC receive/decode/unpack/release sessions; RGBA output contained both clear and opaque pixels. A separate GPU fixture verified colour orientation and exact clear/opaque coverage. A production SceneFeed/RenderWindowScene integration test reached live-video status. This does not certify visual compositing in the headset: the emulator screenshot did not expose the window, so that visual check remains unobserved. The physical device was disconnected. Hands, Windows runtime, sustained throughput and motion-to-photon latency remain unverified for this new path.

The browser publisher remains in the source as an opaque compatibility path; the default add-on button now starts direct capture. Do not run both publishers simultaneously. The whole-app visual-design review remains non-clean from earlier work; the managed direct-alpha source/build check passed, independently of that review.

### Colour correction regression evidence (0.5.1)

The Cycles framebuffer contained linear RGB up to 5.16 in the test scene. Twelve independent comparisons against Blender's own `Image.save_render` PNG output matched exactly (maximum channel error **0/255**): Standard, Filmic, AgX, an AgX look, exposure ±1 and display gamma 0.8/1.3. Five additional OCIO regressions cover middle grey, HDR highlight separation, unchanged coverage, live settings updates and queued-processor snapshots. They run in Blender's Python with its active config; the ordinary external-Python suite skips them if PyOpenColorIO is absent. The correction adds no encoder dependency: Blender 5.2.2 already bundles PyOpenColorIO. The output remains associated RGBA until the encoder's existing resample/unassociation step. Eevee retains `do_color_management=True` and receives no second transform.

After the correction, three emulator WebRTC receive/decode/unpack/release sessions passed with alpha. A same-view opaque-pixel comparison of decoded output versus the colour-corrected source measured a median absolute RGB error of 4/255 (mean 5.48/255), including lossy VP8 and resampling; this is not a claim of pixel-identical video or end-to-end latency.

The user subsequently confirmed that the streamed model’s brightness and colours now match Blender closely in the emulator. This is visual verification of the 0.5.1 colour correction; physical-headset colour matching remains untested.

### Scene edits and recovery regression evidence (0.5.2)

The previous camera isolation touched excluded asset-library templates. Their dragged-in copies inherited `visible_camera=False`, leaving only shadows/indirect light. Visible Scene now applies no camera masks; collection/selection isolation only touches eligible active-view-layer geometry. A temporary ownership property follows copies so inherited bridge masks can be restored without enabling intentionally camera-hidden objects. The two affected assets in the open scene were repaired without reloading the scene.

Five visibility regressions pass externally. Two additional tests inside Blender verify real object-copy properties and camera reuse across a stream-ID change plus viewport resize, followed by exact restoration. On PICO OS 6.1 emulator-5554, three sequential RGBA receive/decode/release sessions passed while the first session covered an eight-second Blender capture pause: decoded frames continued and capture resumed with the same worker. Native JVM regressions cover short recovery preserving alignment, long recovery releasing it and transient camera-command HTTP failures. These checks do not establish physical-device stability or the visual result of a new drag operation.

Automatic reconnect was also tested by forcibly clearing an active video lease: the same native receiver instance negotiated a fresh stream and decoded video again in about 1.45 seconds, without Retry. Its three-session alpha transport test passed. This is emulator evidence for a deliberate signaling interruption, not a latency guarantee.

Slow native model replacement can exceed a pose timeout while direct video is healthy. Retain the existing Blender camera while publisher/viewer video leases remain fresh, then rebind the replacement camera stream in place. Explicit off/Live display also sends a release using the current model revision, even if the old camera sender was already canceled for loading. Turning the render window off explicitly stops its camera before restoring any prior viewport-link preference. A JVM regression covers off during a pending model revision.

Final 0.5.2 production-window regression passed on the emulator: an explicitly published 23→24-object update took 3.36 seconds to export, changed the model revision, and retained the same active Blender camera throughout the native rebuild (no sampled drop/replacement). Scene-aligned video remained live; turning the window off released the camera. The temporary asset was removed and the exact original scene revision restored. All 115 JVM tests pass; final crash-buffer observation was empty. This checks the real model handoff, separately from the synthetic capture pause and forced video reconnect.

User verification of 0.5.2: in the emulator, with Scene-aligned rendering enabled, dragging a new asset into Blender now renders the asset visibly and keeps both the video and aligned view stable. The user confirmed both reported issues are fixed. Physical-device verification remains pending.

### Additional appearances (0.6.1)

On Blender 5.2.2 on the development Mac, Workbench Scene renderer, Solid and Wireframe produced real transparent GPU frames. Stereo checks passed for Workbench Scene renderer/Solid/Wireframe, Eevee Material Preview and Cycles-selected Solid; Eevee Material Preview also passed Mono. Both eye images contained clear and covered pixels. Non-Cycles beauty modes used no Cycles hook or auxiliary render window. The existing emulator receiver decoded Workbench stereo and returned distinct per-eye camera commands. Switching from Eevee with saved Material Preview to Workbench stopped the worker cleanly even though Workbench cannot restore that shading enum. Physical headset rendering and performance of these additional looks remain unverified.

### Native model materials (0.6.2)

The floating render stream and the native 3D model have independent appearances. In Blender’s **PICO Bridge → Native materials**, choose:

| Setting | Native model appearance |
| --- | --- |
| **Auto** (default) | Workbench uses matte Material Viewport Display colours; Cycles/Eevee keep the authored PBR export. Switching render engines updates the model when Sync model edits is enabled. |
| **PBR materials** | Always export authored shader materials and supported glTF textures, including actual metalness and roughness. |
| **Viewport colours (matte)** | Always use material viewport colours, non-metallic rough surfaces and no shader texture/normal/clearcoat maps. |

These conversions affect only the published GLB. They do not edit Blender materials, geometry, animation or the render stream. With Sync model edits off, click **Refresh Model** after changing the option.

Workbench is a different renderer: [its material colour mode](https://docs.blender.org/manual/en/5.1/render/workbench/color.html) uses Viewport Display colours, while [studio and MatCap lighting](https://docs.staging.blender.org/manual/en/latest/render/workbench/lighting.html) do not use scene lights. The matte native option approximates these material colours under PICO’s environment lighting; it does not reproduce MatCap/studio lighting, cavity, Object/Random/Single/Attribute colour modes or textures. Use the render stream for Blender’s exact appearance.

Procedural shader networks can also lose information during ordinary PBR glTF export. Prefer supported Principled BSDF/image-texture materials or bake procedural textures when you need them on the native model.
