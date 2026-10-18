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
records previous flags in the active view layer; objects excluded from that layer are skipped. Unavailable-object errors do not stop camera updates or lease expiry. Exported model bytes and pose node membership remain intact.
A hidden lease expires after eight seconds unless renewed. The native client renews
every 2.5 seconds while fully extracted and the optional Room setting is enabled.
Reset, disabling, leaving Room and disconnect request restoration. Source visibility
is reversible and does not modify saved files, geometry, transforms or timeline.

## Experimental 0.4 additions

The sidebar QR encodes `blender-pico://pair?v=1&endpoint=<url-encoded-http-endpoint>&token=<url-encoded-bearer>&session=<server-session>`.
It is a credential: never include a real QR in public evidence. The native auto-scan parser rejects other schemes, unknown/duplicate fields, public or DNS hosts and malformed credentials. It restricts automatic endpoints to private/loopback literal IPv4 HTTP addresses. Manual pairing retains the existing endpoint validation. A scanned connection checks the expected session before accepting state or model bytes.

State optionally includes `calibrationBoard:{version:1,dictionary:"DICT_4X4_50",width,height,markers:[{id,rect:[x,y,width,height]}]}`. Coordinates are integer pixels from the selected viewport's **top left**, matching marker IDs 0/TL, 1/TR, 2/BR and 3/BL. An optional `referenceMarker:{id:4,rect:[x,y,width,height]}` adds a same-size central marker without changing the existing four-entry `markers` array. It is omitted if its quiet zone would overlap a corner marker. These are display coordinates, not physical dimensions or world coordinates. An absent board means the overlay is off or the viewport cannot display it.

Known-size monitor settings stay local to the headset. For curved geometry, width is cylindrical surface arc length, height is vertical, and `curveDegrees` is total horizontal sweep (0–120°). No camera image, VST fitted pose or physical measurement is added to the HTTP state. The optional tracked rendering-view stream below carries derived camera coordinates when explicitly enabled.

`viewport:{location:[x,y,z],rotation:[x,y,z,w],distance,perspective,lens,width,height}` describes the selected Blender viewport in Blender Z-up units/axes. `viewQuaternion` remains the derived mapping from exported Y-up geometry into viewport camera axes. `viewportControlEnabled` is true only with Blender-side opt-in, a live target viewport and a non-CAMERA perspective.

`POST /v1/viewport-delta` requires the same authorization and exactly:

```json
{"session":"current-server-session","requestId":"unique-request-id","pan":[0.02,0,0],"rotation":[0,0,0,1],"zoom":1.05}
```

The body limit is 2048 bytes; IDs are 1–64 characters, pan components are finite in [-1,1], rotation is a normalized quaternion, and zoom is finite in [0.25,4]. Pan is expressed in viewport camera axes as fractions of the vertical view span at the pivot; positive object pan moves the view pivot oppositely. Rotation becomes inverse viewport orbit and zoom divides view distance. The server rejects disabled/old sessions (409), a second pending command (429) and invalid bodies (400). It accepts one pending command (202), deduplicates 128 recent request IDs (200), and discards commands older than 0.5 seconds. `lastViewportRequest` acknowledges the most recently applied command. This is bounded live navigation, not an exactly-once durable command queue.

Only Blender's main-thread timer touches `RegionView3D`. Object matrices, scene-camera transforms, saved files and timeline stay unchanged. Native gestures aggregate at roughly 13 Hz, and their inspection representation follows the reported pivot/orientation/distance. The link defaults off, requires both peers to opt in and stops on transport failure/disconnect/model replacement/space change. Source hiding is suppressed while linked in Preview. This does not synchronize stereo projection or shading; see [QR alignment limits](docs/QR_ALIGNMENT.md).

### Tracked monitor camera (Room)

State advertises `cameraSyncVersion:1`, `cameraControlEnabled` (Blender opt-in and live viewport), and `cameraSyncActive`. Room requires these capabilities and a locked monitor. Preview continues to use `/v1/viewport-delta`.

`POST /v1/view-camera` uses bearer authentication, a 2048-byte limit and exactly these fields for an active sample:

```json
{"session":"current-server-session","revision":"<64 lowercase hex GLB SHA-256>","stream":"unique-stream-id","sequence":1,"active":true,"position":[0,0,4],"rotation":[0,0,0,1],"slopes":[-0.3,0.3,-0.2,0.2],"near":0.01,"far":1000}
```

Position and quaternion are the camera-to-model pose in exported glTF Y-up coordinates; camera-local +Y is up and -Z is forward. Slopes are `[left,right,bottom,top]` at unit forward depth. Near/far are positive exported-model units. Room derives them from the HMD position, monitor frame and inverse fitted-model transform. With source hiding enabled, near is the eye-to-monitor distance in those units. No camera images, VST calibration or raw room data are sent.

Session/stream IDs are 1–64 characters. Sequences are nonnegative integers ≤2^53. Numbers must be finite with magnitude ≤10^6, rotation squared length in [0.99,1.01], slope spans in (0.0001,20), individual slope magnitude <20, and 0.00001 ≤ near < far ≤10^6. Unknown fields are rejected. A release contains only `session,revision,stream,sequence,active:false`.

A changed model revision clears the old camera-stream lease, allowing immediate rebinding; an identical export retains it. Successful HTTP responses support persistent connections with bounded acknowledgments; error responses close the connection.

The latest command replaces pending intent (202), monotonic sequences ignore older samples without extending their lease (200), and another live stream is rejected (409). A matching current session, model revision and explicit opt-in are required (409 otherwise). Pending commands expire after 0.5 seconds; the active stream lease expires after one second. HTTP threads never touch bpy. The main-thread timer creates/removes only its own viewport-local camera and restores previous view/render settings. Source objects and the production scene camera remain unchanged. This is a monoscopic physical-screen frustum, not a per-eye optical FOV clone or frame-lock contract. Curved displays currently use a chord approximation; see the linked usage/limits document.

### Optional render-window video

State advertises `renderStreamVersion:1` and optional `captureViewport:{x,y,width,height,windowWidth,windowHeight,crop:[u0,v0,u1,v1]}`. Capture coordinates use Blender window pixels with a bottom-left origin. `crop` is relative to the full 3D `WINDOW` region, also bottom-left; it lets the native camera expand the projected frustum for the full region before the browser removes overlapping editor chrome. The payload contains no screen pixels or credentials.

`GET /render` serves a self-contained browser publisher page to loopback clients only. Its operator-generated URL supplies the pairing token in a fragment, removed from history by the page. Screen capture requires the browser's user-selected Blender window. No token is embedded in the served HTML.

Authenticated `GET /v1/render-stream` returns `{stream,offer,answer,publisherReady,size:[width,height],capture,viewerCandidates:[],publisherCandidates:[]}`. SDP/stream fields may be null. Candidate entries contain `candidate,mid,index`. Authenticated `POST` to the same path accepts exactly one of:

```json
{"type":"offer","stream":"viewer-id","sdp":"v=0..."}
{"type":"answer","stream":"viewer-id","sdp":"v=0..."}
{"type":"viewer","stream":"viewer-id"}
{"type":"publisher","width":1280,"height":720}
{"type":"ice","stream":"viewer-id","side":"viewer","candidate":"candidate:...","mid":"0","index":0}
{"type":"stop","stream":"viewer-id"}
{"type":"unpublish"}
```

The body limit is 300000 bytes. IDs are 1–64 ASCII alphanumeric/underscore/hyphen characters. SDP is 1–262144 characters beginning with `v=0`; dimensions are integers from 1 through 8192. ICE `side` is `viewer` or `publisher`; each candidate is a string beginning `candidate:`, at most 8192 characters, with a 1–64 character `mid` and integer `index` from 0 through 32. Each side's list is deduplicated and capped at 64 entries. Stale candidates cannot enter another viewer's session, and lists clear on release, expiry or changed offer. Unknown/extra fields and invalid types are rejected (400). A competing viewer, stale answer/heartbeat/candidate or full candidate list is rejected (409); accepted commands return 202. A stop only clears its matching viewer. Viewer leases expire after 20 seconds; publisher readiness expires after 12 seconds. Native viewer heartbeats target two seconds, browser publisher heartbeats three seconds. ICE candidates are forwarded incrementally after the offer/answer; do not assume a gather-complete callback means every candidate is already in local SDP.

Video travels directly between browser and native WebRTC peers, with no configured STUN/TURN/cloud relay. Both devices therefore need reachable peer network addresses; HTTP USB forwarding alone is insufficient. The receiver is video-only and uses a PICO video surface. Scene-aligned mode reuses `/v1/view-camera` with the virtual window frustum and no source-hiding near plane; Live display releases that camera. Neither mode changes authored objects. See [render-window setup and limitations](docs/RENDER_WINDOW.md).
