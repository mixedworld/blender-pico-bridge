# Movable Blender render window (experimental)

[Back to README](../README.md)

Bring Blender’s actual rendered viewport—including Cycles lighting and materials—into Room as a movable video window. The existing native model stays in the scene. Switch between an aligned window into that model and a floating display of Blender’s current view.

This feature is on `feature/cycles-render-window`. Build the add-on and APK from the same checkout. It is separate from the 0.3.0 emulator release.

## Start here

1. **Open Blender and your scene.** Move the pointer over its 3D Viewport, press **N**, and choose **PICO Bridge**. Set **Source → Visible Scene** or choose a collection. Click **Start Bridge** if it is not running.
2. **Connect the PICO app to Blender** using the usual Connection panel or pairing QR. For a physical headset, put it and the computer on the same reachable LAN. USB reverse forwarding can carry pairing/signaling, but does not forward WebRTC’s video transport.
3. **Choose rendered shading in Blender.** Cycles is optional; the stream shows whatever the chosen viewport actually displays. Leave the Blender window open and keep its layout stable while testing.
4. **In Blender’s PICO Bridge panel, click Share render window.** This opens a local sharing page in your default browser. Chrome on macOS is the tested capture route. If another default browser opens, open the same private link in Chrome; do not share the link or its pairing fragment publicly.
5. **On that page, click Share Blender window.** Select the **Blender window** in Chrome’s chooser and click **Share**. Audio is off. On macOS, the first use may require enabling Chrome under **System Settings → Privacy & Security → Screen & System Audio Recording**, then relaunching Chrome and repeating this step. Keep the sharing page open.
6. **In PICO, enter Room and open Tools.** Scroll to **Render window**, then enable **Show render window**. Wait for **Live render**. The sharing page should say **Streaming to PICO**.
7. **Grab the window to move or rotate it.** Use two hands to resize it. Its aspect ratio stays fixed, and its width is limited to 0.35–1.8 metres. **Recenter window** places it in front of you while preserving its size and selected mode.

When an existing physical viewport frame is locked, the new virtual window starts near that frame, offset toward you. Otherwise it starts in front of your head. Moving this window does not move or overwrite your saved physical frame, corners or curvature.

## Try both modes

| Mode | What happens | How to try it |
| --- | --- | --- |
| **Scene-aligned** (default) | Blender’s temporary camera is calculated from your eye position, the virtual window and the native model’s transforms. The streamed image forms a window into the scene. | Enable Blender’s viewport-control opt-in, assemble the model, then move the window or your head. Resize or move the native model to compare its geometry with the Cycles image. |
| **Live display** | Blender’s previous viewport is restored. The window shows your current Blender view, and head/window movement does not navigate Blender. | Orbit or zoom in Blender. The image updates in PICO like a floating monitor. |

Switching modes preserves the window’s position and size. While either mode is enabled, the existing **Link Blender viewport** control is managed by the render-window mode. Source hiding is temporarily suppressed so there is an image to stream; your hide preference is retained. Turning the window off restores the previous physical viewport-link preference.

The native **Scale / Explode** selection continues to control model manipulation. Two-hand gestures on the video window always resize the window. Native per-part explosion is not sent back to Blender, so use **Assemble** for a scene-aligned comparison.

## Stop and recover

- **Turn off Show render window** to remove it and release its camera link. Use **Stop sharing** in the browser to end screen capture as well. Closing that page also ends capture.
- **No video:** check the browser preview, keep the page open, and verify both devices can reach each other on the LAN. After restarting sharing, use **Retry stream** in PICO.
- **Wrong content:** stop sharing, then select the Blender window again. The add-on crops its selected 3D viewport; it does not stream the whole desktop.
- **View unavailable:** stand in front of the window and face it. A scene-aligned image is hidden when its perspective is invalid; the frame remains movable. Use Recenter if needed.
- **Video paused/disconnected:** camera control is released while fresh video is unavailable. A temporary frame stall can recover automatically; after a stopped share, use Retry stream.
- **Layout changed:** resizing panels changes the capture crop and may recreate the video surface while retaining the virtual window pose. Keep the viewport layout steady for an aligned take.

## Transport, dependencies and limits

The add-on hosts the local capture page and authenticated, bounded offer/answer signaling. Chrome captures the selected window, crops out Blender’s editor chrome, and sends video directly over WebRTC. The Android app uses [WebRTC Android 150.7871.01](https://github.com/webrtc-sdk/android) and a public PICO `SurfaceRenderTexture`/`VideoComponent`. No external signaling, STUN or TURN server is configured. The local pairing token stays private; audio and headset camera capture are not used by this feature.

The capture targets **30 fps**, with the longest output side capped at **1280 pixels**. This is a monoscopic rectangular image, not a stereo render or a transparent depth-composited portal. A single image cannot match both eyes exactly. Network/capture latency and Cycles convergence can expose seams during motion. Curved physical-frame camera matching remains a chord approximation; the movable render window itself is flat. Its pose is retained during the current Room session, not saved as a new persistent anchor.

On the tested Mac and real PICO, the user confirmed live video, one-hand movement, two-hand resizing and both mode toggles. A short receiver sample reported approximately **28–29 fps**. An intermittent reconnect failure was traced to a native network candidate arriving after the gather-complete callback. Incremental ICE forwarding fixes that race; an opt-in device test passed three successive receiver open/decode-to-surface/close cycles (about six seconds total). The user then confirmed live video returned in the reopened production Room window with the corrected build. Stopping Mac sharing released camera control and restored Blender's overlay/gizmo settings with the source visible. This does not measure end-to-end latency or establish sustained performance. Windows and emulator video runtime have not been verified.

Source checks: managed Kotlin scene build, 111 JVM tests and 27 Python tests pass. The whole-app design validation remains non-clean: its existing implementation mapping is stale, and a scanner rejected wildcard SDK imports before they were replaced. No global visual-design certification is claimed. Third-party notices accompany packaged APKs in `third-party-notices.zip`; see [notices](../THIRD_PARTY_NOTICES.md).
