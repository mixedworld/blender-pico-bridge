# Blender → PICO bridge protocol v1

The Blender add-on owns an authenticated HTTP server on port 8766. Every request
requires `Authorization: Bearer <pairing-token>`. Pairing details are copied
from Blender's **PICO Bridge** sidebar. Model and timeline data remain on the
local network. PICO Connect independently streams the Mac desktop.

`GET /v1/state` returns an immutable JSON snapshot:

| Field | Meaning |
| --- | --- |
| protocol | 1 |
| session | New identifier for each server start |
| sequence | Monotonic snapshot sequence within that session |
| revision | SHA-256 of the complete GLB; null before the first export |
| modelPath | `/v1/models/<revision>.glb` |
| modelName, modelBytes, objectCount | Published model metadata |
| animationCount, textureCount | Exported GLB feature counts |
| exportFrameStart, exportFrameEnd | Range used when baking the published clip |
| frame, frameStart, frameEnd, fps | Current Blender scene timeline; FPS includes fps_base |
| playing | Whether Blender is playing its timeline |
| timeSeconds | Current frame's clip-relative time in seconds |
| sentAtUnixMs | Diagnostic timestamp; never trust it as a cross-device clock |
| explodeParts | Optional revision-scoped part names and parent-local Y-up offsets at full separation |
| viewQuaternion | Optional [x,y,z,w] orientation mapping exported Y-up geometry to active Blender viewport camera axes; captured only for staging a take |
| sourceHidden | Whether source meshes are currently temporarily hidden by the visibility lease |
| transforms | Evaluated object-local transforms in glTF/PICO Y-up coordinates |
| error | Latest export failure; last complete revision remains usable |

Each transform contains a Blender object `name`, `p:[x,y,z]` in meters,
`q:[x,y,z,w]` quaternion, and `s:[x,y,z]` scale. Names in a Blender file are
unique; the viewer indexes matching GLB nodes once per revision. Meshes split
by the exporter can remain child nodes of that transform owner. Bone poses
are carried by sampled GLB animation resources rather than object packets.

`GET /v1/models/<revision>.glb` returns an immutable complete GLB, including
embedded image textures, PBR material values, and sampled supported animation.
The client verifies the SHA-256 before loading it from app-private cache.
The server retains three recent revisions. There are no remote Python,
filesystem browsing, or arbitrary Blender editing endpoints.

The viewer polls every 50 ms. Entity and animation-controller changes happen
on the main thread. It rejects stale sequences, downloads only new revisions,
and swaps a loaded model atomically. A persistent local inspection parent owns
user movement, rotation, and scale. It never receives Blender transform updates.
Imported nodes underneath it receive Blender pose updates. On disconnect the
viewer freezes the last pose, keeping the complete model visible.

Blender controls Play, Pause, scrubbing, frame range, and loop wrap. Controllers
seek to the received frame time while paused; object transforms apply directly.
The bridge does not promise frame lock between independent displays or recreate
Cycles shading, procedural shaders, simulations, or arbitrary unsupported
glTF animation. Bake these features to supported geometry, textures, and clips.


Bridge 0.2 adds optional `explodeParts:[{name,offset:[x,y,z]}]`. Older v1 clients
ignore it; newer clients treat an absent list as no separable parts. Offsets
are derived from the evaluated geometric center of each exported mesh and the
assembly bounds center. Mesh ancestor displacement is subtracted before
conversion to immediate-parent space, avoiding double displacement for nested
geometry. The native renderer computes `authoredPosition + amount * offset`
from each incoming pose, so repeated packets never accumulate separation.
Animation seek occurs first; object pose plus inspection offset follows.
The amount and interaction mode belong to native inspection state, not Blender.
The same public part vectors are included in `asset.extras.picoBridge.explodeParts`
inside the GLB. Thus an offset edit changes its SHA-256 revision and causes a
fresh envelope fit even when mesh geometry is unchanged. This curated extras
block contains no Blender custom-property dump or pairing credentials.


Bridge 0.3 adds `POST /v1/source-visibility` with exactly `{ "hidden": true|false }`,
using the same bearer authorization. The body limit is 64 bytes; unknown fields,
non-booleans and other command paths are rejected. HTTP threads only store intent.
The Blender main-thread timer applies viewport `hide_set` to source meshes and
records previous flags; exported model bytes and pose node membership remain intact.
A hidden lease expires after eight seconds unless renewed. The native client renews
every 2.5 seconds while fully extracted and the optional Room setting is enabled.
Reset, disabling, leaving Room and disconnect request restoration. Source visibility
is reversible and does not modify saved files, geometry, transforms or timeline.
