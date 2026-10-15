# QR pairing, depth measurements and viewport linking

Experimental feature branch: `feature/qr-monitor-viewport-sync`. Build the add-on **0.4.0** and APK together from this branch. The published 0.3.0 downloads do not contain these features. This is a draft implementation, not a verified automatic monitor alignment release.

## Pair from Blender

Open the **PICO Bridge** sidebar. **Start when panel opens** starts the local server once; disable it for manual startup. New installations default to Visible Scene. Existing collection/selection settings are retained. The server can pair before valid geometry is selected; choose a source and Refresh Model to publish geometry.

The panel shows the server IPv4 address, manual token entry/copy and an on-screen QR. **Show QR and markers** adds a larger QR and four OpenCV `DICT_4X4_50` markers to the selected 3D Viewport. The overlay is display-only and does not add geometry to the scene. Markers are sized for camera visibility and inset around overlapping Blender headers, toolbars, sidebars and shelves so their complete white borders stay visible. The transmitted marker rectangles retain full-viewport pixel coordinates. Stop Bridge removes its QR preview/temp file. Do not share screenshots containing a real pairing QR: it contains the bearer credential.

In the PICO app, open **Connection**. It requests camera permission and starts looking for the Blender QR. Valid bridge QR data automatically fills the pairing details and connects; other QR codes are ignored. Only literal private/loopback IPv4 HTTP endpoints and our versioned QR scheme are accepted. The initial connection also checks the QR's server session; restarting the bridge requires a fresh scan. Manual connection remains available, including for hosts that need a different route.

Choose **Connect via → Wi-Fi / LAN** when the headset can reach the computer's advertised address. A QR cannot configure a firewall or connect isolated networks. If the Mac and headset are on different networks, use **USB / Emulator (ADB)** and run `adb -s DEVICE reverse tcp:8766 tcp:8766` for that device (replace DEVICE with its `adb devices` serial). This mode deliberately advertises `http://127.0.0.1:8766` through the USB tunnel. The displayed URL, copied details and QR all use the selected route; changing the route updates the QR without restarting the server. Changing the server port requires a matching reverse mapping.

**Show large pairing QR** toggles the QR independently from the markers, even when a headset is already connected. Its size follows the viewport so it remains readable on a high-resolution monitor. If scanning stalls, the status distinguishes service startup, first-frame acquisition and live QR searching.

## Measure the viewport with depth

Use **Room → Align monitor → Measure with markers + depth**, grant Camera and Spatial Data, and look at the four markers. The implementation:

1. Acquires 1280×960 rectified stereo, timestamp and intrinsics in one SpatialML capture. Device intrinsics verified nearly identical horizontal/vertical focal lengths at this 4:3 size; a 16:9 capture stretched the image and prevented QR detection.
2. Reads RGB into app memory and detects the four marker centers with OpenCV.
3. Keeps that capture frozen while `uvTo3DInCameraSpace` projects those image points using its depth/stereo registration.
4. Rejects nonfinite, zero/out-of-range, nonrectangular or noncoplanar results; requires five size estimates stable within 1 cm.
5. Extrapolates from the marker-center insets to the full viewport rectangle. **Use measured size** applies only width/height to the existing monitor plane, leaving it unlocked for manual placement/confirmation.

No physical width is requested or guessed. Camera frames are not written to disk or uploaded to Blender. Cancelling, leaving the scan surface or backgrounding the app stops acquisition. A timeout or denied permission preserves manual setup.

**Automatic world placement is incomplete.** The public 6.1.9 projection API documents camera-space XYZ. Current HMD poses are available, but the public API does not establish the VST exposure-camera extrinsic or expose historical timestamped HMD poses. SpatialML's `CameraAnchor.Locked` world-locks its own scene, with no verified handle-to-existing-ECS-plane bridge here. Applying a current HMD pose as if it were calibrated exposure data is deliberately not implemented.

Real-device validation is still required for UV axis convention, depth freshness, metric accuracy and world registration. Flat-screen size estimates do not reconstruct a curved display: four corner samples cannot measure its center curvature or radius. Reflective/curved displays can produce inaccurate or missing depth. Do not treat this path as curved-screen calibration.

## Link Blender's view

Enable **Allow PICO viewport control** in Blender, then **Link Blender viewport** in the PICO toolbox. Both switches default off. The native gesture path sends bounded orbit/pan/zoom commands; the Blender main-thread timer updates the selected `RegionView3D`. It does not modify mesh/object transforms, the scene camera, animation, or save the file. CAMERA perspective is excluded to avoid editing a production camera view.

The native viewer follows the reported pivot, orientation and distance. Incremental gestures are aggregated while a command is in flight; session IDs, request IDs, body/vector limits, deduplication and a short pending-command lifetime bound the server endpoint. A transport failure, disconnect, new model or space switch stops linking. Disabling the link restores the native inspection pose. Source hiding is suspended while linked so the desktop view remains available.

This is pose/zoom linking, **not pixel-exact visual synchronization**. The PICO stereo eye positions, display projection, PBR lighting and Blender viewport shading remain different. Preview volume clipping still applies; the fit is not a calibrated off-axis projection of the physical screen. Physical pinch/gaze comfort and handedness need device testing.

## Validation so far

- Ten Python tests pass: transport authorization/limits/session/deduplication, GLB and QR/board/PNG behavior.
- Twenty-five Kotlin tests pass, including an independent ZXing decode of a synthetic QR PNG generated by the Blender encoder, depth geometry/rejection and gesture aggregation.
- Android debug build and APK bundle/ABI checks pass on macOS Apple Silicon. Source/architecture/design-style checks pass; this does not establish full design-to-runtime fidelity or headset performance.
- In live Blender 5.2.2 LTS, OpenCV decoded the generated screen QR and detected marker IDs 0–3. A live HTTP viewport command was acknowledged, the view changed, every authored object matrix stayed unchanged, and the view was restored.
- PICO Emulator 6.1.0 installs and launches the APK and opens the camera permission request from Connection. The emulator supplies no live headset VST/depth. Real-device QR scanning was subsequently verified on PICO with normal Camera permission and the SpatialML debug/bypass property disabled: the camera delivered live frames, decoded the pairing QR and connected to the BMW scene over USB/ADB reverse. The user confirmed **Live from Blender**. LAN pairing could not complete on this setup because the Mac and headset were on isolated subnets; direct LAN reachability was tested and failed. Depth measurement and full automatic world alignment remain unverified.

## Primary API references

- [PICO rectified camera acquisition](https://developer.picoxr.com/spatial-api/6.1.9/spatialml/securemr/com.pico.spatial.ml.securemr/-pipeline/rectified-v-s-t-access.html)
- [Registered UV to camera-space 3D](https://developer.picoxr.com/spatial-api/6.1.9/spatialml/securemr/com.pico.spatial.ml.securemr/-pipeline/uv-to3-d-in-camera-space.html)
- [Permissioned readback](https://developer.picoxr.com/spatial-api/6.1.9/spatialml/readback/com.pico.spatial.ml.readback/readback-content.html)
- [SpatialML-owned camera anchors](https://developer.picoxr.com/spatial-api/6.1.9/spatialml/securemr/com.pico.spatial.ml.securemr/-scene-graph-property/-camera-anchor/index.html)
- [Public HMD tracking data](https://developer.picoxr.com/spatial-api/6.1.9/trackingpack/tracking/com.pico.spatial.tracking.hmd/-h-m-d-tracking-data/index.html)
- [Blender RegionView3D](https://docs.blender.org/api/5.2/bpy.types.RegionView3D.html)
