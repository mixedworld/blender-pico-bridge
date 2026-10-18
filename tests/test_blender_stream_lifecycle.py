"""Run inside Blender for real ID-copy and temporary camera lifecycle regressions."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

try:
    import bpy
    from mathutils import Quaternion, Vector
except ImportError:
    bpy = None

ADDON = Path(__file__).parents[1] / 'blender-addon/pico_blender_bridge'


def load(name):
    spec = importlib.util.spec_from_file_location('lifecycle_' + name, ADDON / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipIf(bpy is None, 'Requires Blender Python')
class BlenderStreamLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.scene = bpy.data.scenes.new('Bridge lifecycle regression')

    def tearDown(self):
        for obj in list(self.scene.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.scenes.remove(self.scene)

    def object(self, name):
        obj = bpy.data.objects.new(name, None)
        self.scene.collection.objects.link(obj)
        return obj

    def test_real_object_copy_recovers_temporary_camera_mask(self):
        mask = load('capture_visibility').CaptureVisibility()
        source, background, library, authored_hidden = [self.object(n) for n in ('source', 'background', 'library', 'hidden')]
        authored_hidden.visible_camera = False
        mask.refresh(self.scene.objects, [source, background, authored_hidden], [source], True)
        copied = background.copy()
        self.scene.collection.objects.link(copied)
        try:
            self.assertFalse(copied.visible_camera)
            self.assertEqual(copied.get(mask.MARKER), mask.token)
            self.assertTrue(library.visible_camera)
            mask.refresh(self.scene.objects, [source, background, copied, authored_hidden], [source, copied], True)
            self.assertTrue(copied.visible_camera)
            self.assertIsNone(copied.get(mask.MARKER))
        finally:
            mask.restore(self.scene.objects)
        self.assertTrue(background.visible_camera)
        self.assertFalse(authored_hidden.visible_camera)
        self.assertFalse(mask.saved)

    def test_camera_rebind_and_viewport_resize_restore_original_view(self):
        camera = load('tracked_camera').TrackedCamera()
        view = SimpleNamespace(view_rotation=Quaternion(), view_location=Vector((1, 2, 3)),
            view_distance=6.0, view_perspective='PERSP', view_camera_zoom=12.0,
            view_camera_offset=(.1, .2))
        space = SimpleNamespace(region_3d=view, use_local_camera=False, camera=None, lock_camera=True)
        area = SimpleNamespace(spaces=SimpleNamespace(active=space), tag_redraw=lambda: None)
        render = self.scene.render
        before = (render.resolution_x, render.resolution_y, render.resolution_percentage)
        command = dict(stream='first', rotation=[0, 0, 0, 1], position=[0, 1, 2],
            slopes=[-.6, .6, -.4, .4], near=.01, far=1000)
        try:
            with bpy.context.temp_override(scene=self.scene, view_layer=self.scene.view_layers[0]):
                camera.apply(command, self.scene, area, SimpleNamespace(width=960, height=640))
                first_object, original_snapshot = camera.object, camera.saved
                name = first_object.name
                camera.apply(dict(command, stream='reconnected'), self.scene, area, SimpleNamespace(width=1280, height=720))
                self.assertIs(camera.object, first_object)
                self.assertIs(camera.saved, original_snapshot)
                self.assertEqual(camera.stream, 'reconnected')
                self.assertEqual((render.resolution_x, render.resolution_y), (1280, 720))
                self.assertEqual(view.view_perspective, 'CAMERA')
        finally:
            camera.stop()
        self.assertFalse(camera.active)
        self.assertIsNone(bpy.data.objects.get(name))
        self.assertEqual((render.resolution_x, render.resolution_y, render.resolution_percentage), before)
        self.assertEqual(view.view_perspective, 'PERSP')
        self.assertEqual(tuple(view.view_location), (1, 2, 3))
        self.assertFalse(space.use_local_camera)
        self.assertIsNone(space.camera)
        self.assertTrue(space.lock_camera)
