bl_info = {
    "name": "PICO Blender Bridge", "author": "PICO Blender Bridge",
    "version": (0, 6, 3), "blender": (4, 2, 0),
    "location": "3D View > Sidebar > PICO Bridge",
    "description": "Live PBR model and Blender timeline bridge to PICO Spatial SDK",
    "category": "3D View",
}

import bpy
import os
import socket
import json
import struct
import tempfile
import time
import threading
import queue
from bpy.app.handlers import persistent
from .server import BridgeServer
from .glb import with_explosion_parts, with_viewport_materials, material_profile
from .pairing import pairing_payload
from .display import PairingDisplay
from .tracked_camera import TrackedCamera
from .source_visibility import hide_source_objects
from .render_stream import viewport_capture as viewport_crop
from .viewport_capture import DirectViewportCapture, default_worker_python
from .emulator_connection import connect_emulator

_server = None
_exporting = False
_dirty_at = 0.0
_last_frame = None
_source_visibility = {}
_capture_view = None
_direct_capture = None
_scope = []
_node_names = set()
_clip_start = 1
_clip_fps = 24.0
_export_signature = None
_message = "Stopped"
_display = None
_auto_start_attempted = False
_tracked_camera = TrackedCamera()
_emulator_connecting = False
_emulator_message = ''


def settings():
    return bpy.context.scene.pico_bridge


def scoped_objects():
    cfg = settings()
    if cfg.scope == "COLLECTION":
        objects = list(cfg.collection.all_objects) if cfg.collection else []
    elif cfg.scope == "SELECTION":
        objects = [bpy.data.objects[n] for n in _scope if n in bpy.data.objects]
    else:
        objects = [o for o in bpy.context.view_layer.objects if (not o.hide_get() or o.name in _source_visibility) and not o.hide_render]
    allowed = {o for o in objects if o.type not in {"CAMERA", "LIGHT"}}
    # Include skinning dependencies and transform ancestors, without adding unrelated geometry.
    for obj in list(allowed):
        for modifier in obj.modifiers:
            if modifier.type == "ARMATURE" and modifier.object:
                allowed.add(modifier.object)
        parent = obj.parent
        while parent:
            allowed.add(parent)
            parent = parent.parent
    return allowed


def export_signature(scene):
    cfg = scene.pico_bridge
    return (scene.frame_start, scene.frame_end, scene.render.fps / scene.render.fps_base,
            material_profile(scene.render.engine, cfg.native_materials))


def native_materials_changed(_self, _context):
    global _dirty_at
    if _server and not _exporting:
        _dirty_at = time.monotonic()


def export_model():
    global _exporting, _dirty_at, _message, _node_names, _clip_start, _clip_fps, _export_signature
    if not _server:
        return
    restore_source_visibility()
    objects = scoped_objects()
    if not any(o.type in {"MESH", "CURVE", "FONT", "SURFACE", "META"} for o in objects):
        raise RuntimeError("Choose a collection or selection containing geometry")
    selected = [obj for obj in bpy.context.view_layer.objects if obj.select_get()]
    active = bpy.context.view_layer.objects.active
    scene = bpy.context.scene
    frame = scene.frame_current
    _exporting = True
    tmp = tempfile.NamedTemporaryFile(suffix=".glb", delete=False)
    tmp.close()
    try:
        bpy.ops.object.select_all(action="DESELECT")
        for obj in objects:
            if obj.name in bpy.context.view_layer.objects:
                obj.select_set(True)
        bpy.ops.export_scene.gltf(
            filepath=tmp.name, export_format="GLB", use_selection=True,
            export_materials="EXPORT", export_animations=True,
            export_animation_mode="SCENE", export_force_sampling=True,
            export_frame_range=True, export_frame_step=1,
            export_anim_slide_to_zero=True, export_anim_scene_split_object=False,
            export_cameras=False, export_lights=False, export_yup=True,
            # Scene custom properties include private bridge pairing settings.
            # Node names carry transform identity; glTF extras are unnecessary.
            export_extras=False)
        with open(tmp.name, "rb") as f:
            data = f.read()
        json_length = struct.unpack_from("<I", data, 12)[0]
        document = json.loads(data[20:20 + json_length])
        _node_names = {n.get("name") for n in document.get("nodes", [])}
        parts = explosion_parts(objects, _node_names)
        # Offset edits must create a new content-addressed revision and refit envelope.
        data = with_explosion_parts(data, parts)
        cfg = settings()
        profile = material_profile(scene.render.engine, cfg.native_materials)
        if profile == "VIEWPORT":
            colors = {slot.material.name: list(slot.material.diffuse_color)
                      for obj in objects for slot in obj.material_slots if slot.material}
            data = with_viewport_materials(data, colors)
        _message = f"Published {len(objects)} objects · {len(data) / 1048576:.1f} MB"
        revision = _server.publish_model(data, {"error": None, "modelName": cfg.collection.name if cfg.collection else scene.name,
                               "exportFrameStart": scene.frame_start, "exportFrameEnd": scene.frame_end,
                               "exportFps": scene.render.fps / scene.render.fps_base,
                               "objectCount": len(objects), "animationCount": len(document.get("animations", [])),
                               "textureCount": len(document.get("images", [])) if profile == "PBR" else 0,
                               "explodeParts": parts})
        cfg.last_revision = revision[:12]
        _clip_start = scene.frame_start
        _clip_fps = scene.render.fps / scene.render.fps_base
        _export_signature = export_signature(scene)
    finally:
        os.unlink(tmp.name)
        scene.frame_set(frame)
        bpy.ops.object.select_all(action="DESELECT")
        for obj in selected:
            if obj.name in bpy.context.view_layer.objects:
                obj.select_set(True)
        bpy.context.view_layer.objects.active = active
        _exporting = False
        _dirty_at = 0.0


def explosion_parts(objects, node_names):
    """Stable revision-scoped offsets in each exported node's Y-up parent space.

    Subtract a mesh ancestor's displacement so nested parts do not move twice.
    The optional custom vector lets an artist specify a mechanical disassembly.
    """
    from mathutils import Vector, Matrix
    graph = bpy.context.evaluated_depsgraph_get()
    meshes = [o for o in objects if o.type == "MESH" and o.name in node_names]
    if not meshes:
        return []
    corners = {o: [o.evaluated_get(graph).matrix_world @ Vector(c)
                   for c in o.evaluated_get(graph).bound_box] for o in meshes}
    points = [p for values in corners.values() for p in values]
    center = Vector(tuple((min(p[i] for p in points) + max(p[i] for p in points)) * .5 for i in range(3)))
    delta = {o: (sum(corners[o], Vector()) / 8 - center) * .85 for o in meshes}
    basis = Matrix(((1, 0, 0), (0, 0, 1), (0, -1, 0)))
    result = []
    for obj in sorted(meshes, key=lambda o: o.name):
        ancestor = obj.parent
        while ancestor and ancestor not in delta:
            ancestor = ancestor.parent
        displacement = delta[obj] - (delta[ancestor] if ancestor else Vector())
        if obj.parent and obj.parent.name in node_names:
            displacement = obj.parent.evaluated_get(graph).matrix_world.to_3x3().inverted_safe() @ displacement
        custom = obj.get("pico_explode_offset")
        if custom is not None and len(custom) == 3:
            displacement = Vector(custom)
        result.append({"name": obj.name, "offset": list(basis @ displacement)})
    return result


def restore_source_visibility():
    for name, (hidden, layer) in list(_source_visibility.items()):
        obj = bpy.data.objects.get(name)
        if obj:
            try:
                obj.hide_set(hidden, view_layer=layer)
            except (RuntimeError, ReferenceError):
                pass
    _source_visibility.clear()


def update_source_visibility(hidden):
    if hidden:
        if not _source_visibility:
            hide_source_objects(scoped_objects(), bpy.context.view_layer, _source_visibility)
    elif _source_visibility:
        restore_source_visibility()


def update_capture_view(area=None, enabled=False):
    """Keep the shared viewport clean, restoring user overlay/gizmo settings on stop."""
    global _capture_view
    space = area.spaces.active if area and enabled else None
    if _capture_view and _capture_view[0] != space:
        previous, overlays, gizmos = _capture_view
        try:
            previous.overlay.show_overlays = overlays
            previous.show_gizmo = gizmos
        except (ReferenceError, RuntimeError):
            pass
        _capture_view = None
    if space and _capture_view is None:
        _capture_view = (space, space.overlay.show_overlays, space.show_gizmo)
        space.overlay.show_overlays = False
        space.show_gizmo = False


def camera_tick():
    """Apply only the latest head pose; do not wait for scene-state/export work."""
    global _message
    if not _server:
        return None
    scene = bpy.context.scene
    cfg = scene.pico_bridge
    area, region = _display.target() if _display else (None, None)
    camera_command = _server.take_camera_command()
    if _tracked_camera.active:
        # Native model loading can outlast a pose lease while video remains healthy.
        # Hold the aligned view while both video leases are fresh. Explicit release
        # below restores immediately; disconnected viewers expire after 20 seconds.
        with _server.lock:
            video = _server.render_signaling.state()
        recovering = bool(_direct_capture and _direct_capture.running
            and video["stream"] is not None and video["publisherReady"])
        if not cfg.allow_viewport_control or (not _server.camera_lease_active() and not recovering):
            _tracked_camera.stop()
            if _direct_capture:
                _direct_capture.stereo_command(None)
    if camera_command:
        if camera_command['active'] and cfg.allow_viewport_control and _server.camera_lease_active() and area and region:
            try:
                was_active = _tracked_camera.active
                _tracked_camera.apply(camera_command,scene,area,region)
                if not was_active and _direct_capture and _direct_capture.stereo_mm:
                    _tracked_camera.saved[4]['render'] = _direct_capture.before_render
                if _direct_capture:
                    _direct_capture.stereo_command(camera_command)
            except (ValueError,ReferenceError,RuntimeError):
                _tracked_camera.stop()
                cfg.allow_viewport_control=False
                _message='Camera sync stopped. Reopen the viewport and align again.'
        elif not camera_command['active']:
            if _direct_capture:
                _direct_capture.stereo_command(None)
            _tracked_camera.stop()
    return 0.008 if _tracked_camera.active else 0.025


def tick():
    global _last_frame, _message, _dirty_at, _direct_capture
    if not _server:
        return None
    update_source_visibility(_server.source_hidden())
    scene = bpy.context.scene
    cfg = scene.pico_bridge
    if _direct_capture:
        error = _direct_capture.poll()
        if error:
            _message = error
            _direct_capture = None
    area, region = _display.target() if _display else (None, None)
    if _display:
        _display.update(time.monotonic() - _server.last_client < 3)
    with _server.lock:
        publisher_ready = _server.render_signaling.state()['publisherReady']
    update_capture_view(area, publisher_ready)
    viewport = area.spaces.active.region_3d if area else None
    command = _server.take_viewport_command()
    if command and cfg.allow_viewport_control and viewport and region and viewport.view_perspective != 'CAMERA':
        from mathutils import Quaternion, Vector
        # Inverse viewport orbit/pan/zoom, never authored object transforms.
        x,y,z,w = command['rotation']
        rotation = Quaternion((w,x,y,z)).normalized()
        projection = viewport.window_matrix
        span = 2 * (viewport.view_distance if viewport.view_perspective == 'PERSP' else 1) / max(abs(projection[1][1]), .0001)
        viewport.view_location -= viewport.view_rotation @ (Vector(command['pan']) * span)
        viewport.view_rotation = viewport.view_rotation @ rotation.conjugated()
        viewport.view_distance = max(.001, min(1e6, viewport.view_distance / command['zoom']))
        area.tag_redraw()
        _server.publish_state({'lastViewportRequest': command['requestId']})
    playing = any(screen.is_animation_playing for screen in bpy.data.screens)
    fps = scene.render.fps / scene.render.fps_base
    if cfg.auto_export and _export_signature != export_signature(scene) and not _dirty_at:
        _dirty_at = time.monotonic()
    # Transform packets cover object/constraint animation even when the SDK exposes
    # imported animation resources only on skinned meshes. Basis conversion matches glTF Y-up.
    from mathutils import Matrix
    basis = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))
    transforms = []
    depsgraph = bpy.context.evaluated_depsgraph_get()
    for obj in scoped_objects():
        if obj.name not in _node_names or obj.type == "ARMATURE":
            continue
        evaluated = obj.evaluated_get(depsgraph)
        matrix = evaluated.matrix_world
        if obj.parent and obj.parent.name in _node_names:
            matrix = obj.parent.evaluated_get(depsgraph).matrix_world.inverted_safe() @ matrix
        position, rotation, scale = (basis @ matrix @ basis.inverted()).decompose()
        transforms.append({"name": obj.name, "p": list(position),
                           "q": [rotation.x, rotation.y, rotation.z, rotation.w], "s": list(scale)})
    # Map exported Y-up geometry into the active Blender viewport's camera axes.
    # The model keeps this snapshot only when the user stages a take in PICO.
    view_rotation = None
    if viewport:
        q = (viewport.view_rotation.inverted().to_matrix() @ basis.to_3x3().inverted()).to_quaternion()
        view_rotation = [q.x,q.y,q.z,q.w]
    view_state = None
    if viewport and region:
        q = viewport.view_rotation
        view_state = {'location': list(viewport.view_location), 'rotation': [q.x,q.y,q.z,q.w],
                      'distance': viewport.view_distance, 'perspective': viewport.view_perspective,
                      'lens': area.spaces.active.lens, 'width': region.width, 'height': region.height}
    capture_viewport = None
    if area and region:
        window = next((w for w in bpy.context.window_manager.windows if area in w.screen.areas[:]), None)
        if window:
            capture_viewport = viewport_crop(window.width, window.height,
                (region.x,region.y,region.width,region.height),
                [(r.type,r.x,r.y,r.width,r.height) for r in area.regions])
            if _direct_capture and _direct_capture.running and _direct_capture.last_size:
                # Encoder rounding must not alter the physical camera/window aspect.
                width, height = region.width, region.height
                capture_viewport = {"x": region.x, "y": region.y, "width": width, "height": height,
                    "windowWidth": window.width, "windowHeight": window.height, "crop": [0, 0, 1, 1]}
    _server.publish_state({"frame": scene.frame_current, "frameStart": scene.frame_start,
                           "frameEnd": scene.frame_end, "fps": fps, "playing": playing,
                           "timeSeconds": (scene.frame_current - _clip_start) / _clip_fps,
                           "transforms": transforms, "viewQuaternion": view_rotation, "sourceHidden": bool(_source_visibility),
                           "viewport": view_state, "viewportControlEnabled": bool(cfg.allow_viewport_control and viewport and viewport.view_perspective != 'CAMERA'),
                           "cameraSyncVersion": 1, "cameraControlEnabled": bool(cfg.allow_viewport_control and viewport),
                           "cameraSyncActive": _tracked_camera.active,
                           "renderStreamVersion": 3, "captureViewport": capture_viewport,
                           "renderStereoEyeSeparationMm": _direct_capture.stereo_mm if _direct_capture else 0,
                           "calibrationBoard": _display.board if _display else None,
                           "sentAtUnixMs": time.time_ns() // 1000000})
    if cfg.auto_export and _dirty_at and not _source_visibility and not playing and time.monotonic() - _dirty_at > 0.75:
        try:
            export_model()
        except Exception as exc:
            _message = "Export failed: " + str(exc)
            _server.publish_state({"error": _message})
            _dirty_at = 0.0
    _last_frame = scene.frame_current
    return 0.05


@persistent
def changed(scene, depsgraph):
    global _dirty_at
    if not _server or _exporting or _source_visibility or scene.frame_current != _last_frame:
        return
    if any(screen.is_animation_playing for screen in bpy.data.screens):
        return
    if any(update.is_updated_geometry or isinstance(update.id, (bpy.types.Material, bpy.types.Image))
           or (isinstance(update.id, bpy.types.Object) and not update.id.animation_data)
           for update in depsgraph.updates
           if not (isinstance(update.id, bpy.types.Object) and update.id.type == "CAMERA")
           and not isinstance(update.id, bpy.types.Camera)):
        _dirty_at = time.monotonic()


@persistent
def loaded(_):
    global _auto_start_attempted
    stop_bridge()
    _auto_start_attempted = False


def start_bridge():
    global _server, _scope, _message, _display
    if _server:
        return
    cfg = settings()
    _scope = [obj.name for obj in bpy.context.view_layer.objects if obj.select_get()]
    _server = BridgeServer(port=cfg.port, token=cfg.token or None)
    cfg.token = _server.token
    area = bpy.context.area
    if not area or area.type != 'VIEW_3D':
        area = next((a for w in bpy.context.window_manager.windows for a in w.screen.areas if a.type == 'VIEW_3D'), None)
    _display = PairingDisplay(pairing_payload(pairing_endpoint(), cfg.token, _server.session), area)
    bpy.app.timers.register(tick, first_interval=0.05)
    bpy.app.timers.register(camera_tick, first_interval=0.008)
    _message = "Listening"
    try:
        export_model()
    except Exception as exc:
        _message = 'Bridge ready · choose geometry and Refresh Model'
        _server.publish_state({'error': str(exc)})


def stop_bridge():
    global _server, _message, _display, _direct_capture
    if _direct_capture:
        _direct_capture.stop()
        _direct_capture = None
    restore_source_visibility()
    update_capture_view()
    _tracked_camera.stop()
    current, _server = _server, None
    if current:
        current.close()
    if _display:
        _display.close()
        _display = None
    if bpy.app.timers.is_registered(tick):
        bpy.app.timers.unregister(tick)
    if bpy.app.timers.is_registered(camera_tick):
        bpy.app.timers.unregister(camera_tick)
    _message = "Stopped"


def pairing_endpoint():
    cfg = settings()
    host = '127.0.0.1' if cfg.pairing_route == 'USB' else lan_address()
    return f'http://{host}:{_server.port if _server else cfg.port}'


def pairing_route_changed(_self, _context):
    if _display and _server:
        _display.set_payload(pairing_payload(pairing_endpoint(), settings().token, _server.session))
        _display.update(time.monotonic() - _server.last_client < 3)


class PicoBridgePreferences(bpy.types.AddonPreferences):
    bl_idname = __package__
    adb_path: bpy.props.StringProperty(name="ADB executable", subtype="FILE_PATH", default="",
        description="Optional platform-tools/adb (adb.exe on Windows); otherwise search PATH and standard Android SDK locations")
    worker_python: bpy.props.StringProperty(name="Render worker Python", subtype="FILE_PATH", default="")
    stream_size: bpy.props.IntProperty(name="Stream maximum side", default=960, min=128, max=1280)
    stream_shading: bpy.props.EnumProperty(name="Stream appearance",items=[
        ("RENDERED","Scene renderer","Use the selected Cycles, Eevee or Workbench renderer"),
        ("SOLID","Solid","Stream the viewport's Solid shading, studio light, colour and cavity settings"),
        ("MATERIAL","Material Preview","Stream material-preview lighting; requires Cycles or Eevee selected"),
        ("WIREFRAME","Wireframe","Stream geometry wireframes with a transparent background")],default="RENDERED")
    stream_stereo: bpy.props.EnumProperty(name="Stream views", items=[("MONO","Mono","One view for both eyes"),
        ("STEREO","Stereo (manual IPD)","Distinct eye views; uses explicitly confirmed manual eye separation")], default="MONO")
    stream_ipd_mm: bpy.props.FloatProperty(name="Manual eye separation (mm)",default=64.0,min=40.0,max=80.0,precision=1,
        update=lambda self,context: setattr(self,"stream_ipd_confirmed",False))
    stream_ipd_confirmed: bpy.props.BoolProperty(name="Use this manually entered eye separation",default=False)
    stream_fps: bpy.props.IntProperty(name="Stream frames per second", default=15, min=1, max=30)

    def draw(self, context):
        self.layout.prop(self, "adb_path")
        self.layout.label(text="Direct viewport streaming needs the optional Python encoder environment.")
        self.layout.label(text="Run tools/setup_render_worker.py from the bridge repository once.")
        self.layout.prop(self, "worker_python")
        self.layout.prop(self, "stream_size")
        self.layout.prop(self, "stream_fps")
        self.layout.prop(self, "stream_shading")
        self.layout.prop(self, "stream_stereo")
        if self.stream_stereo == "STEREO":
            self.layout.prop(self,"stream_ipd_mm")
            self.layout.prop(self,"stream_ipd_confirmed")


class PicoBridgeSettings(bpy.types.PropertyGroup):
    scope: bpy.props.EnumProperty(name="Source", items=[("COLLECTION", "Collection", "Publish a model collection"),
                                                       ("SELECTION", "Selection", "Selection captured when connecting"),
                                                       ("VISIBLE", "Visible Scene", "All visible model geometry")], default="VISIBLE")
    collection: bpy.props.PointerProperty(name="Collection", type=bpy.types.Collection)
    port: bpy.props.IntProperty(name="Port", default=8766, min=1024, max=65535)
    pairing_route: bpy.props.EnumProperty(name="Connect via", default='LAN', update=pairing_route_changed,
        items=[('LAN', 'Wi-Fi / LAN', 'Both devices must reach this computer over the network'),
               ('USB', 'USB / Emulator (ADB)', 'Requires adb reverse tcp:8766 tcp:8766 for the selected device')])
    token: bpy.props.StringProperty(name="Pairing token", subtype="PASSWORD")
    native_materials: bpy.props.EnumProperty(name="Native materials", default="AUTO", update=native_materials_changed,
        description="Appearance of the native PICO model, independent of the render stream",
        items=[("AUTO", "Auto", "Use matte material viewport colours for Workbench; authored PBR for Cycles/Eevee"),
               ("PBR", "PBR materials", "Export authored shader materials and supported textures"),
               ("VIEWPORT", "Viewport colours (matte)", "Use Material Viewport Display colours without gloss, textures or MatCap lighting")])
    auto_export: bpy.props.BoolProperty(name="Sync model edits", default=True)
    auto_start: bpy.props.BoolProperty(name="Start when panel opens", default=True)
    allow_viewport_control: bpy.props.BoolProperty(name="Allow PICO viewport control", default=False,
        description="Allow a temporary head-tracked PICO camera in Room, or viewport orbit in Preview. Source objects and scene camera stay unchanged")
    last_revision: bpy.props.StringProperty()


class PICO_BRIDGE_OT_start(bpy.types.Operator):
    bl_idname = "pico_bridge.start"
    bl_label = "Start Bridge"
    def execute(self, context):
        try:
            start_bridge()
        except Exception as exc:
            stop_bridge()
            self.report({"ERROR"}, str(exc))
            return {"CANCELLED"}
        return {"FINISHED"}


class PICO_BRIDGE_OT_stop(bpy.types.Operator):
    bl_idname = "pico_bridge.stop"
    bl_label = "Stop Bridge"
    def execute(self, context):
        global _auto_start_attempted
        _auto_start_attempted = True
        stop_bridge()
        return {"FINISHED"}


class PICO_BRIDGE_OT_refresh(bpy.types.Operator):
    bl_idname = "pico_bridge.refresh"
    bl_label = "Refresh Model"
    def execute(self, context):
        try:
            export_model()
        except Exception as exc:
            self.report({"ERROR"}, str(exc))
            return {"CANCELLED"}
        return {"FINISHED"}


class PICO_BRIDGE_OT_pairing(bpy.types.Operator):
    bl_idname = "pico_bridge.copy_pairing"
    bl_label = "Copy Pairing Details"
    def execute(self, context):
        context.window_manager.clipboard = f"{pairing_endpoint()}\n{settings().token}"
        return {"FINISHED"}


class PICO_BRIDGE_OT_connect_emulator(bpy.types.Operator):
    bl_idname = 'pico_bridge.connect_emulator'
    bl_label = 'Connect / Reconnect Emulator'
    bl_description = 'Restore ADB forwarding and reopen the installed viewer with current pairing; keep saved alignment'

    @classmethod
    def poll(cls, context):
        return _server is not None and settings().pairing_route == 'USB' and not _emulator_connecting

    def execute(self, context):
        global _emulator_connecting, _emulator_message
        cfg = settings()
        prefs = context.preferences.addons[__package__].preferences
        args = (_server.port, _server.token, bpy.path.abspath(prefs.adb_path) if prefs.adb_path else '')
        result = queue.Queue(maxsize=1)
        _emulator_connecting = True
        _emulator_message = 'Connecting emulator…'

        def worker():
            try:
                result.put(connect_emulator(*args))
            except Exception as exc:
                # Only this helper's sanitized errors are user-facing.
                result.put(str(exc) if isinstance(exc, RuntimeError) else 'Emulator connection failed; check ADB preferences.')

        def collect():
            global _emulator_connecting, _emulator_message
            try:
                message = result.get_nowait()
            except queue.Empty:
                return .2
            _emulator_connecting = False
            _emulator_message = message
            for window in bpy.context.window_manager.windows:
                for area in window.screen.areas:
                    if area.type == 'VIEW_3D':
                        area.tag_redraw()
            return None

        threading.Thread(target=worker, name='PICO emulator pairing', daemon=True).start()
        bpy.app.timers.register(collect, first_interval=.2)
        return {'FINISHED'}


class PICO_BRIDGE_OT_board(bpy.types.Operator):
    bl_idname = 'pico_bridge.calibration_board'
    bl_label = 'Show QR and corner markers'
    def execute(self, context):
        if _display:
            if context.area and context.area.type == 'VIEW_3D':
                _display.area_id = context.area.as_pointer()
            _display.overlay = not _display.overlay
            _display.show_qr = _display.overlay
            _display.update(time.monotonic() - _server.last_client < 3)
        return {'FINISHED'}


class PICO_BRIDGE_OT_qr(bpy.types.Operator):
    bl_idname = 'pico_bridge.large_qr'
    bl_label = 'Show large pairing QR'
    def execute(self, context):
        if _display:
            _display.show_qr = not _display.show_qr
            _display.update(time.monotonic() - _server.last_client < 3)
        return {'FINISHED'}


class PICO_BRIDGE_OT_center_markers(bpy.types.Operator):
    bl_idname = 'pico_bridge.center_marker_only'
    bl_label = 'Center reference only'
    bl_description = 'Use one measured marker for size; screen curvature must be set manually'
    def execute(self, context):
        if _display and _display.board and _display.board.get('referenceMarker'):
            _display.center_only = not _display.center_only
            _display.overlay = True
            _display.show_qr = False
            _display.update(time.monotonic() - _server.last_client < 3)
        else:
            self.report({'WARNING'}, 'Enable markers in a larger viewport to show the center reference')
        return {'FINISHED'}


def auto_start_once():
    global _auto_start_attempted, _message
    _auto_start_attempted = True
    try:
        start_bridge()
    except Exception as exc:
        stop_bridge()
        _message = 'Start failed: ' + str(exc)
    return None


def lan_address():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


class PICO_BRIDGE_OT_render_window(bpy.types.Operator):
    bl_idname = "pico_bridge.render_window"
    bl_label = "Start viewport stream"
    bl_description = "Stream the scene renderer, Solid, Material Preview or Wireframe directly from Blender"

    def execute(self, context):
        global _direct_capture, _message
        if not _server:
            self.report({'WARNING'}, "Start the bridge first")
            return {'CANCELLED'}
        if _direct_capture:
            _direct_capture.stop()
            _direct_capture = None
            _message = "Direct viewport stream stopped"
            return {'FINISHED'}
        area, region = _display.target() if _display else (None, None)
        if not area or not region:
            self.report({'WARNING'}, "Open a Blender 3D viewport first")
            return {'CANCELLED'}
        window = next((w for w in context.window_manager.windows if area in w.screen.areas[:]), None)
        prefs = context.preferences.addons[__package__].preferences
        if prefs.stream_stereo == "STEREO" and not prefs.stream_ipd_confirmed:
            self.report({'ERROR'}, "Enter and confirm your manual eye separation before starting stereo")
            return {'CANCELLED'}
        try:
            _direct_capture = DirectViewportCapture(area, window, _server, scoped_objects,
                prefs.worker_python or default_worker_python(), prefs.stream_fps, prefs.stream_size,
                isolate_source=lambda: settings().scope != "VISIBLE",
                stereo_mm=prefs.stream_ipd_mm if prefs.stream_stereo == "STEREO" else 0,
                shading=prefs.stream_shading)
            _message = "Direct " + _direct_capture.engine + " stream starting"
        except Exception as error:
            self.report({'ERROR'}, str(error))
            return {'CANCELLED'}
        return {'FINISHED'}


class PICO_BRIDGE_PT_panel(bpy.types.Panel):
    bl_label = "PICO Blender Bridge"
    bl_idname = "PICO_BRIDGE_PT_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "PICO Bridge"
    def draw(self, context):
        layout = self.layout
        cfg = context.scene.pico_bridge
        layout.prop(cfg, 'auto_start')
        if not _server and cfg.auto_start and not _auto_start_attempted and not bpy.app.timers.is_registered(auto_start_once):
            bpy.app.timers.register(auto_start_once, first_interval=.1)
        layout.prop(cfg, "scope")
        if cfg.scope == "COLLECTION":
            layout.prop(cfg, "collection")
        layout.prop(cfg, "native_materials")
        if material_profile(context.scene.render.engine, cfg.native_materials) == "VIEWPORT":
            layout.label(text="Native: matte material viewport colours")
        layout.prop(cfg, "auto_export")
        if _server:
            layout.prop(cfg, 'pairing_route')
            layout.label(text=pairing_endpoint())
            if cfg.pairing_route == 'USB':
                layout.operator('pico_bridge.connect_emulator', icon='LINKED')
                if _emulator_message:
                    layout.label(text=_emulator_message)
            if _display:
                layout.template_icon(icon_value=_display.icon, scale=8)
                layout.operator('pico_bridge.calibration_board', text='Hide markers' if _display.overlay else 'Show QR and markers')
                layout.operator('pico_bridge.large_qr', text='Hide pairing QR' if _display.show_qr else 'Show large pairing QR')
                layout.operator('pico_bridge.center_marker_only', text='Show all markers' if _display.center_only else 'Center reference only')
            layout.prop(cfg, 'allow_viewport_control')
            layout.prop(cfg, "token")
            prefs = context.preferences.addons[__package__].preferences
            if not _direct_capture:
                layout.prop(prefs,"stream_shading")
                if prefs.stream_shading == "MATERIAL" and context.scene.render.engine == "BLENDER_WORKBENCH":
                    layout.label(text="Material Preview needs Cycles or Eevee",icon='INFO')
                layout.prop(prefs,"stream_stereo")
                if prefs.stream_stereo == "STEREO":
                    layout.prop(prefs,"stream_ipd_mm")
                    layout.prop(prefs,"stream_ipd_confirmed")
            layout.operator("pico_bridge.render_window", text="Stop viewport stream" if _direct_capture else "Start viewport stream")
            if _direct_capture and _direct_capture.running:
                if _direct_capture.stereo_mm:
                    layout.label(text=f"Stereo · manual {_direct_capture.stereo_mm:.1f} mm")
                layout.label(text=_direct_capture.label + " · alpha")
            layout.operator("pico_bridge.copy_pairing")
            layout.operator("pico_bridge.refresh")
            layout.operator("pico_bridge.stop")
            layout.label(text="Headset connected" if time.monotonic() - _server.last_client < 3 else "Waiting for headset")
        else:
            layout.prop(cfg, "port")
            layout.operator("pico_bridge.start")
        layout.label(text=_message)
        layout.label(text="Blender controls animation playback")
        layout.label(text="Use image textures + Principled BSDF")


CLASSES = (PicoBridgePreferences, PicoBridgeSettings, PICO_BRIDGE_OT_start, PICO_BRIDGE_OT_stop,
           PICO_BRIDGE_OT_refresh, PICO_BRIDGE_OT_pairing, PICO_BRIDGE_OT_connect_emulator, PICO_BRIDGE_OT_board, PICO_BRIDGE_OT_qr, PICO_BRIDGE_OT_center_markers, PICO_BRIDGE_OT_render_window, PICO_BRIDGE_PT_panel)


@persistent
def before_save(_unused):
    # Saving a .blend must not bake temporary Film/camera-ray isolation into the scene.
    global _direct_capture, _message
    if _direct_capture:
        _direct_capture.stop()
        _direct_capture = None
        _message = "Stream stopped for save; scene settings restored"


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Scene.pico_bridge = bpy.props.PointerProperty(type=PicoBridgeSettings)
    bpy.app.handlers.depsgraph_update_post.append(changed)
    bpy.app.handlers.load_pre.append(loaded)
    bpy.app.handlers.save_pre.append(before_save)


def unregister():
    stop_bridge()
    if bpy.app.timers.is_registered(auto_start_once):
        bpy.app.timers.unregister(auto_start_once)
    for handlers, callback in ((bpy.app.handlers.depsgraph_update_post, changed),
                               (bpy.app.handlers.load_pre, loaded),
                               (bpy.app.handlers.save_pre, before_save)):
        if callback in handlers:
            handlers.remove(callback)
    del bpy.types.Scene.pico_bridge
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
