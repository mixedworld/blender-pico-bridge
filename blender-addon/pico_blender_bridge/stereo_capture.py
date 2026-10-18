"""Temporary independent Cycles eye views; the artist's viewport remains monoscopic.

Only the main thread touches bpy. Pair pixels are owned arrays and may be resized
on the writer thread. Eye commands are latched until both views have drawn.
"""
import math
import time

import bpy
import numpy as np
from mathutils import Matrix, Quaternion, Vector
from bpy_extras.view3d_utils import location_3d_to_region_2d


def resize_rgba(image, width, height):
    """Bilinear resampling of associated RGBA, before any nonlinear view transform."""
    h, w = image.shape[:2]
    if (w, h) == (width, height):
        return image
    x = np.linspace(0, w - 1, width); y = np.linspace(0, h - 1, height)
    x0 = x.astype(int); y0 = y.astype(int)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[None, :, None]; fy = (y - y0)[:, None, None]
    a = image[y0[:, None], x0[None, :]].astype(np.float32)
    b = image[y0[:, None], x1[None, :]].astype(np.float32)
    c = image[y1[:, None], x0[None, :]].astype(np.float32)
    d = image[y1[:, None], x1[None, :]].astype(np.float32)
    result = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    return result.astype(image.dtype)


def set_camera(obj, command):
    basis = Matrix.Rotation(math.pi / 2, 4, 'X')
    q = command['rotation']
    rotation = Quaternion((q[3], q[0], q[1], q[2])).normalized()
    obj.matrix_world = basis @ Matrix.LocRotScale(Vector(command['position']), rotation, Vector((1, 1, 1)))
    l, r, b, t = command['slopes']
    data = obj.data
    data.lens = data.sensor_width / (r - l)
    data.shift_x = (r + l) / (2 * (r - l))
    data.shift_y = (t + b) / (2 * (r - l))
    data.clip_start, data.clip_end = command['near'], command['far']


class StereoViewportRig:
    def __init__(self, source_area, source_window, separation_mm, fps, viewport_renderers=None):
        self.source_area = source_area
        self.source_window = source_window
        self.separation = separation_mm / 1000
        self.interval = 1 / fps
        self.window = None
        self.views = []
        self.cameras = []
        self.pending = {}
        self.latest = None
        self.generation = 0
        self.started = 0
        self.scene_frame = None
        self.command_key = None
        self.closed = False
        self.last_complete = 0
        try:
            if viewport_renderers is None:
                viewport_renderers = source_window.scene.render.engine == 'CYCLES'
            if viewport_renderers:
                before = {w.as_pointer() for w in bpy.context.window_manager.windows}
                with bpy.context.temp_override(window=source_window,area=source_area):
                    bpy.ops.wm.window_new()
                self.window = next(w for w in bpy.context.window_manager.windows if w.as_pointer() not in before)
                if len(self.window.screen.areas) == 1:
                    with bpy.context.temp_override(window=self.window,area=self.window.screen.areas[0]):
                        bpy.ops.screen.area_split(direction='VERTICAL',factor=.5)
                self.views = sorted(self.window.screen.areas,key=lambda a:a.x)[:2]
                if len(self.views) != 2:
                    raise RuntimeError('Stereo capture needs two temporary view areas')
            for index in range(2):
                data = bpy.data.cameras.new('PICO Stream ' + ('Left' if index == 0 else 'Right'))
                data.sensor_fit = 'HORIZONTAL'; data.sensor_width = 36
                data.dof.use_dof = False; data.show_passepartout = False
                obj = bpy.data.objects.new(data.name,data)
                source_window.scene.collection.objects.link(obj)
                obj.hide_set(True,view_layer=source_window.view_layer)
                self.cameras.append(obj)
                if self.views:
                    area = self.views[index]; area.type = 'VIEW_3D'
                    space = area.spaces.active
                    space.shading.type = 'RENDERED'; space.overlay.show_overlays = False
                    space.show_gizmo = False; space.show_region_ui = False
                    space.show_region_toolbar = False; space.show_region_tool_header = False
                    space.use_local_camera = True; space.lock_camera = False
                    space.stereo_3d_camera = 'S3D'; space.camera = obj
                    space.region_3d.view_perspective = 'CAMERA'
                    space.region_3d.view_camera_offset = (0,0)
                    space.region_3d.view_camera_zoom = 20
            self.refresh(force=True)
        except Exception:
            self.close()
            raise

    def submit(self, command):
        self.latest = command if command and len(command.get('eyes', [])) == 2 else None

    def refresh(self, force=False):
        now = time.monotonic()
        if not force and self.pending and self.source_window.scene.frame_current_final == self.scene_frame and now - self.started < 2:
            return
        if not force and now - self.started < self.interval:
            return
        self.pending = {}
        region = next(r for r in self.source_area.regions if r.type == 'WINDOW')
        render = self.source_window.scene.render
        if (render.resolution_x,render.resolution_y,render.resolution_percentage,
                render.pixel_aspect_x,render.pixel_aspect_y) != (region.width,region.height,100,1.0,1.0):
            render.resolution_x,render.resolution_y = region.width,region.height
            render.resolution_percentage = 100
            render.pixel_aspect_x = render.pixel_aspect_y = 1.0
        self.generation += 1
        self.started = now
        self.scene_frame = self.source_window.scene.frame_current_final
        view = self.source_area.spaces.active.region_3d
        signature = (str(self.latest['eyes']) if self.latest else
            (tuple(v for row in view.view_matrix for v in row),
             tuple(v for row in view.window_matrix for v in row),view.view_distance))
        if signature == self.command_key:
            for area in self.views:
                area.tag_redraw()
            return
        self.command_key = signature
        if self.latest:
            for obj, eye in zip(self.cameras, self.latest['eyes']):
                set_camera(obj, eye)
        else:
            # Live display/bootstrap uses Blender scene units. Scene-aligned mode
            # replaces this with two cameras converted from tracked HMD metres.
            view = self.source_area.spaces.active.region_3d
            matrix = view.view_matrix.inverted()
            p = view.window_matrix
            l, r = (p[0][2] - 1) / p[0][0], (p[0][2] + 1) / p[0][0]
            b, t = (p[1][2] - 1) / p[1][1], (p[1][2] + 1) / p[1][1]
            if abs(p[3][3]) > .5:
                # A Live display of an orthographic viewport has no natural stereo
                # convergence. Make a perspective view through its pivot plane.
                distance = max(view.view_distance,.01)
                l,r = (-1-p[0][3])/p[0][0]/distance,(1-p[0][3])/p[0][0]/distance
                b,t = (-1-p[1][3])/p[1][1]/distance,(1-p[1][3])/p[1][1]/distance
                matrix = Matrix.LocRotScale(view.view_location + view.view_rotation @ Vector((0,0,distance)),
                    view.view_rotation,Vector((1,1,1)))
            unit = max(self.source_window.scene.unit_settings.scale_length, 1e-6)
            distance = max(view.view_distance, .01)
            for obj, side in zip(self.cameras, (-.5, .5)):
                offset = side * self.separation / unit
                obj.matrix_world = matrix @ Matrix.Translation((offset, 0, 0))
                data = obj.data
                data.lens = data.sensor_width / (r - l)
                data.shift_x = (r + l - 2 * offset / distance) / (2 * (r - l))
                data.shift_y = (t + b) / (2 * (r - l))
                data.clip_start = self.source_area.spaces.active.clip_start
                data.clip_end = self.source_area.spaces.active.clip_end
        for area in self.views:
            area.tag_redraw()

    def crop(self, context):
        if context.window != self.window or context.area not in self.views:
            return None
        index = self.views.index(context.area)
        if self.source_window.scene.frame_current_final != self.scene_frame:
            self.pending.clear()
            return None
        region = context.region; view = context.space_data.region_3d
        camera = self.cameras[index]
        points = [location_3d_to_region_2d(region, view, camera.matrix_world @ p)
                  for p in camera.data.view_frame(scene=context.scene)]
        if any(p is None for p in points):
            return None
        x0, y0 = math.ceil(min(p.x for p in points)), math.ceil(min(p.y for p in points))
        x1, y1 = math.floor(max(p.x for p in points)), math.floor(max(p.y for p in points))
        if not (0 <= x0 < x1 <= region.width and 0 <= y0 < y1 <= region.height):
            return None
        return index, (x0, y0, x1 - x0, y1 - y0)

    def collect(self, index, pixels, processor):
        self.pending[index] = (pixels, processor)
        if len(self.pending) != 2:
            return None
        pair = (self.pending[0][0], self.pending[1][0], self.pending[0][1])
        self.pending = {}
        self.last_complete = time.monotonic()
        return pair

    def alive(self):
        return ((self.window is None or self.window in bpy.context.window_manager.windows[:])
            and all(a.type == 'VIEW_3D' for a in self.views))

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self.window and self.window in bpy.context.window_manager.windows[:]:
            with bpy.context.temp_override(window=self.window):
                bpy.ops.wm.window_close()
        for obj in self.cameras:
            try:
                data = obj.data
                bpy.data.objects.remove(obj, do_unlink=True)
                if data.users == 0:
                    bpy.data.cameras.remove(data)
            except ReferenceError:
                pass
        self.cameras.clear()
