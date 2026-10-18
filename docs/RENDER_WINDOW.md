# Movable Blender render window (experimental)

[Back to README](../README.md)

Bring Blender’s actual rendered viewport—including Cycles lighting and materials—into Room as a movable video window. The existing native model stays in the scene. Switch between an aligned window into that model and a floating display of Blender’s current view.

Included in v0.8.0. Install the add-on and APK from the same release, or build both from the same checkout.

## Start here

Use the [direct Blender streaming setup](DIRECT_STREAMING.md): install the optional render worker once, open Blender's PICO Bridge panel, click **Start viewport stream**, pair the emulator/app and enable **Tools → Render → Show render window**. Choose Cycles, Eevee or Workbench in Blender, or select Solid, Material Preview or Wireframe under **Stream appearance**. The encoder reads GPU pixels directly, with a transparent world background; Chrome capture is no longer the default.

Grab the window’s **border or corners** to move or rotate it; start both hands on the border to resize (0.35–1.8 m). The image centre always passes grabs through to the native model, including when **Show 3D model** is off. In **Tools → Render**, enable **Lock render window** to disable all frame grabbing, including its border. Unlock it to reposition it. Locking only changes interaction; video and Scene-aligned camera sync continue. Recenter places it in front of you. It remains independent of the saved physical viewport frame and curvature.

## Try both modes

| Mode | What happens | How to try it |
| --- | --- | --- |
| **Scene-aligned** (default) | Blender’s temporary camera is calculated from your eye position, the virtual window and the native model’s transforms. The streamed image forms a window into the scene. | Enable Blender’s viewport-control opt-in, assemble the model, then move the window or your head. Resize or move the native model to compare its geometry with the Cycles image. |
| **Live display** | Blender’s previous viewport is restored. The window shows your current Blender view, and head/window movement does not navigate Blender. | Orbit or zoom in Blender. The image updates in PICO like a floating monitor. |

Switching modes preserves the window’s position and size. While either mode is enabled, the existing **Link Blender viewport** control is managed by the render-window mode. Source hiding is temporarily suppressed so there is an image to stream; your hide preference is retained. Turning the window off restores the previous physical viewport-link preference.

The native **Scale / Explode** selection continues to control model manipulation. Two-hand gestures on the video window always resize the window. Native per-part explosion is not sent back to Blender, so use **Assemble** for a scene-aligned comparison.

## Stop and recover

- Turn off **Show render window** to release its camera link, and click **Stop viewport stream** in Blender to stop capture/encoding and restore temporary settings.
- For no video, check Blender's bridge status and render-worker setup. Keep the target 3D viewport open. Brief capture pauses repeat the last image; dropped connections retry automatically. Use **Retry stream** if manual recovery is needed.
- For unexpected background geometry, use a model collection in Cycles. Eevee currently includes visible scene geometry; see the isolation limitations in the direct-stream guide.
- **Model disappears after Stage for take:** staging scales the native model to fit wholly behind the locked physical viewport mask. It remains hidden there until you pinch through the frame and pull it in front. The larger source-size model can extend beyond the mask, making this look like distance culling. The independent video window also obeys scene depth: move it in front of the physical viewport frame if it has been placed behind it.
- For **View unavailable**, stand in front of the window and face it. The image hides when perspective is invalid; its frame remains movable. Use Recenter if needed.
- Resizing the Blender viewport updates the linked camera without resetting it; closing or replacing that viewport ends its link. Mono cannot match both eyes exactly. Optional [Stereo with manual eye separation](STEREO_STREAMING.md) adds binocular parallax; it still does not provide per-pixel scene depth.

## Transport and verification

The current path is Blender RGBA → local aiortc/PyAV worker → VP8 WebRTC colour/alpha atlas → native GPU unpack → transparent PICO VideoMaterial. See [architecture, dependencies, evidence and remaining limits](DIRECT_STREAMING.md).

The earlier opaque Chrome publisher remains in the source as a compatibility tool. Its real-headset video/movement/reconnect tests predate direct RGBA capture and must not be treated as physical verification of the new path. Original bridge MIT terms do not relicense dependencies; see [third-party notices](../THIRD_PARTY_NOTICES.md).

The emulator distance-visibility check was confirmed interactively: after pinching through the locked viewport frame and pulling the staged model in front, the user could move farther away without it disappearing. No camera-culling adjustment was needed.

### Hide the native model while keeping control

In PICO **Tools → Model**, switch **Show 3D model** off to show only the streamed render. The native model stays loaded: grab its existing position to move, rotate, scale or explode it. Its hover highlight is suppressed while hidden. Turn the switch on to reveal it at its current pose. This does not hide the Blender source or the render window.

The setting survives incoming scene updates for the current app session; a fresh app start defaults to showing the model.

### Face the viewer and fit to the model

In **Tools → Render**, enable **Face user** to keep the video surface facing your tracked head while its centre stays at the manual position. This works in Scene-aligned and Live display. You can still move or resize it by its border when unlocked. **Lock render window** disables hand grabs; automatic facing continues.

For a render surface that follows the model, choose **Scene-aligned**, enable **Face user**, then **Fit to model**. The app measures the model’s current bounding box in its fit-owner axes and surrounds it with a sphere centred on that box. The radius is the box’s half diagonal, enclosing every corner. It places the rectangle centre on the viewer-facing sphere surface plus 3 cm, when space permits, and sizes it to cover the projected box with 8% padding. The sphere uses the oriented box, so rotating the model does not artificially inflate the radius. It preserves the stream aspect ratio and includes both manually configured stereo eye positions. Hidden native meshes retain bounds, so this works with **Show 3D model** off. Current model movement, scaling, animation bounds and explosion bounds update the fit. Assemble the model for matching Blender/native geometry; native part explosion is still not transmitted to Blender.

Auto-fit removes border grab targets as well as retaining centre pass-through, so you manipulate the model through the image instead of fighting automatic window placement. Lock is independent: automatic following continues even when locked. Disable **Fit to model** to return to the manual centre and size; disable **Face user** to restore the manual orientation too. Viewport aspect-ratio rebuilds preserve that manual placement. The physical viewport alignment/anchor is unchanged. These options are session-local and initially off.

The fit concentrates the available encoded pixels around the model; it does not raise the configured encoder resolution or frame rate. This uses an enclosing bounding sphere for placement and the projected bounding box for image coverage; it is not an exact mesh-surface intersection or silhouette. When approaching or entering the enclosing sphere, eye clearance takes priority: the plane stays at least 25 cm in front of both eyes and is allowed to intersect the model. Close-up coverage is cropped to at most 120° in either dimension rather than growing without limit or turning the video off. Facing is held stable inside the bounds, including across the model centre, so the plane cannot flip through the viewer. Normal whole-model fitting resumes as you step back. The existing camera-distance limit also caps an automatic window at 9.5 m from the head; larger/distant bounds still fit its projected image.
