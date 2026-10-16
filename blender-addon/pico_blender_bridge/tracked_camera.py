"""Main-thread-only temporary Blender camera for a tracked physical-screen window."""
import math
import bpy
from mathutils import Matrix, Quaternion, Vector


class TrackedCamera:
    def __init__(self):
        self.saved = None
        self.object = None
        self.stream = None

    @property
    def active(self):
        return self.saved is not None

    def start(self, scene, area, stream):
        self.stop()
        space = area.spaces.active
        view = space.region_3d
        render = scene.render
        self.saved = (scene, area, space, view, {
            'local': space.use_local_camera, 'camera': space.camera, 'lock': space.lock_camera,
            'rotation': view.view_rotation.copy(), 'location': view.view_location.copy(),
            'distance': view.view_distance, 'perspective': view.view_perspective,
            'zoom': view.view_camera_zoom, 'offset': tuple(view.view_camera_offset),
            'region': None,
            'render': (render.resolution_x, render.resolution_y, render.resolution_percentage,
                       render.pixel_aspect_x, render.pixel_aspect_y),
        })
        data = bpy.data.cameras.new('PICO Bridge Camera')
        self.object = bpy.data.objects.new('PICO Bridge Camera', data)
        scene.collection.objects.link(self.object)
        self.stream = stream
        data.type = 'PERSP'
        data.sensor_fit = 'HORIZONTAL'
        data.sensor_width = 36.0
        data.dof.use_dof = False
        data.show_passepartout = False
        self.object.hide_set(True)
        space.use_local_camera = True
        space.camera = self.object
        space.lock_camera = False
        view.view_perspective = 'CAMERA'
        view.view_camera_offset = (0, 0)
        # Blender's camera frame scale is (sqrt(2)+zoom/50)^2/4.
        view.view_camera_zoom = 50.0*(2.0-math.sqrt(2.0))

    def apply(self, command, scene, area, region):
        if not self.active or self.stream != command['stream']:
            self.start(scene, area, command['stream'])
        if self.saved[1] != area:
            raise ValueError('Tracked viewport changed')
        dimensions=(region.width,region.height)
        if self.saved[4]['region'] not in (None,dimensions):
            raise ValueError('Viewport resized')
        self.saved[4]['region']=dimensions
        # glTF Y-up -> Blender Z-up. Camera-local axes remain +Y up, -Z forward.
        basis = Matrix.Rotation(math.pi/2, 4, 'X')
        q = command['rotation']
        rotation = Quaternion((q[3], q[0], q[1], q[2])).normalized()
        self.object.matrix_world = basis @ Matrix.LocRotScale(Vector(command['position']), rotation, Vector((1, 1, 1)))
        left, right, bottom, top = command['slopes']
        data = self.object.data
        data.lens = data.sensor_width/(right-left)
        data.shift_x = (right+left)/(2*(right-left))
        data.shift_y = (top+bottom)/(2*(right-left))
        data.clip_start, data.clip_end = command['near'], command['far']
        scene.render.resolution_x, scene.render.resolution_y = region.width, region.height
        scene.render.resolution_percentage = 100
        scene.render.pixel_aspect_x = scene.render.pixel_aspect_y = 1.0
        area.tag_redraw()

    def stop(self):
        saved, self.saved = self.saved, None
        if saved:
            try:
                scene, area, space, view, state = saved
                space.camera, space.use_local_camera, space.lock_camera = state['camera'], state['local'], state['lock']
                view.view_rotation, view.view_location, view.view_distance = state['rotation'], state['location'], state['distance']
                view.view_perspective, view.view_camera_zoom, view.view_camera_offset = state['perspective'], state['zoom'], state['offset']
                render = scene.render
                render.resolution_x, render.resolution_y, render.resolution_percentage, render.pixel_aspect_x, render.pixel_aspect_y = state['render']
                area.tag_redraw()
            except ReferenceError:
                pass  # The previous .blend was closed; its RNA objects no longer exist.
        if self.object:
            try:
                data = self.object.data
                bpy.data.objects.remove(self.object, do_unlink=True)
                if data.users == 0:
                    bpy.data.cameras.remove(data)
            except ReferenceError:
                pass
        self.object = self.stream = None
