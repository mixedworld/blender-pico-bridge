# Stereo render window (experimental, 0.6.1)

Stereo uses two independently rendered Blender cameras, packed into one WebRTC video frame with their alpha masks. PICO's public `VideoMaterial` side-by-side mode displays the left image to the left eye and the right image to the right eye. Mono remains the default.

## Enable it

1. Install the matching add-on and native app from this checkout. Set up the [optional render worker](DIRECT_STREAMING.md) once.
2. In Blender, open **3D Viewport → Sidebar → PICO Bridge**. Stop the viewport stream if it is running.
3. Set **Stream views → Stereo (manual IPD)**. Enter your headset's eye separation in **Manual eye separation (mm)**, then check **Use this manually entered eye separation**. Changing the value clears its confirmation. The initial 64 mm shown in the field is an example, not an automatic measurement.
4. Click **Start viewport stream**. In PICO Room, enable **Tools → Render window → Show render window** and choose **Scene-aligned** for overlay against the native model. Recenter the render window if needed.
5. To return to Mono, stop the Blender stream, select **Mono**, and start again. The native receiver reconnects and selects the matching playback mode automatically.

For Cycles, Blender opens a temporary window with left/right camera previews. Keep it open during streaming; closing it stops stereo and restores the capture settings. The main viewport remains a centre view. Eevee, Workbench and the Solid/Material Preview/Wireframe appearances render both eyes offscreen in one draw callback and do not need that extra window. Two renders cost more GPU work than Mono; start with a moderate stream resolution.

## Eye separation and alignment

This initial version uses an explicitly confirmed **manual symmetric eye rig**. It offsets the eyes by half the entered distance along the tracked head's own right axis, including head rotation, before converting each eye through the public Entity coordinate APIs. Each eye receives an independent off-axis projection through the same physical render window. Window-plane points coincide; foreground and background points acquire opposite stereo disparity. No toe-in or guessed passthrough-camera baseline is used.

Manual separation is an approximation of the headset's render rig. It cannot account for unexposed per-eye origin offsets or automatic IPD changes. Update the value yourself if the headset's eye configuration changes. The installed Spatial SDK 6.1.9 exposes one HMD pose and one gaze pose; research did not establish a supported public API for both render-eye origins or live IPD. Automatic rig access remains future work.

**Live display** does not follow the headset. It constructs a stereo view from Blender's current view, using scene units and its pivot distance for convergence. An orthographic Blender view is converted to a perspective stereo view through that pivot plane, so it will not look identical to the orthographic source. Use **Scene-aligned** for physical overlay and model-scale compensation.

## Capture and transport

- Native camera commands carry the centre camera plus exactly two validated `eyes`, ordered left/right. Each includes its position, rotation, off-axis slopes and clipping planes. Camera positions are in the exported model's coordinate frame; separation is converted from tracked metres, including model scale.
- Cycles uses independent progressive viewport renderers. A camera pair is latched until both eye views are collected. Different animation frame numbers are rejected. Progressive sample counts may differ; this is not a guarantee of identical render completion times. Static camera settings are not repeatedly reassigned.
- Eevee renders both cameras in one main-thread offscreen callback from the same evaluated scene. The existing display transform and transparent background handling apply to both eyes.
- The new format is `stereo-sbs-color-alpha-top-bottom-v1`: top-left colour L, top-right colour R, bottom-left alpha L, bottom-right alpha R. The existing mono format retains its meaning. Stream capability version is 3.
- Eyes are resized separately, with associated alpha, to prevent colour bleeding across the seam. Encoder size limits apply **per eye**. One decoded frame contains the whole pair and both masks; there are no independent network queues to drift between eyes.
- The native alpha drawer preserves the side-by-side layout on a wide RGBA surface. Public `VideoMaterial.setDimensionMode(SIDE_BY_SIDE)` performs eye selection; no custom eye-index shader is required.
- Stopping, saving or closing the capture view removes temporary stereo cameras/window, stops the encoder, and restores Film/shading/render dimensions. Existing camera-link teardown restores the original centre view.

Stereo provides binocular parallax within a flat movable window. It does not send per-pixel depth, support reprojection after decoding, or solve arbitrary intersections with real-world geometry. Existing native-model occlusion and monitor masking still apply.

## Verification

Host checks cover paired-camera validation, left/right colour and alpha packing, chroma-aligned per-eye resizing, window-plane coincidence, disparity signs, and protocol capability handling. Live Cycles generated distinct transparent eye images on the development Mac. Both Cycles and Eevee each passed three emulator receive/decode/unpack/release sessions with stereo status and clear/opaque output. Cycles teardown restored the exact original render dimensions and removed both temporary eye cameras. Physical binocular depth, exact rig matching and sustained headset performance remain unverified because only the emulator was connected.

The managed scene workflow validated source and built the native APK. Its delivery gate could not automatically verify the custom stereo runtime acceptance criterion; this is not a complete managed visual-certification pass. Emulator runtime evidence is recorded separately.

## Sources

- [Spatial SDK 6.1.9 VideoDimensionMode](https://developer.picoxr.com/spatial-api/6.1.9/spatialpack/core/com.pico.spatial.core.ecs.video/-video-dimension-mode/index.html)
- [Spatial SDK 6.1.9 EyeTrackingProvider](https://developer-cn.picoxr.com/spatial-api/6.1.9/trackingpack/tracking/com.pico.spatial.tracking.eye/-eye-tracking-provider/index.html)
- [Blender 5.2 GPUOffScreen](https://docs.blender.org/api/5.2/gpu.types.html#gpu.types.GPUOffScreen)
