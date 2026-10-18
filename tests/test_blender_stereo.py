"""Run in Blender for physical-window projection and associated-alpha resizing."""
import importlib.util
import math
from pathlib import Path
import unittest

try:
    import bpy
    import numpy as np
    from mathutils import Matrix,Vector
except ImportError:
    bpy=None

@unittest.skipIf(bpy is None,'Requires Blender Python')
class BlenderStereoTest(unittest.TestCase):
    def setUp(self):
        spec=importlib.util.spec_from_file_location('stereo_blender_regression',
            Path(__file__).parents[1]/'blender-addon/pico_blender_bridge/stereo_capture.py')
        self.stereo=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.stereo)
    def test_both_real_camera_matrices_project_the_window_to_the_same_pixels(self):
        scene=bpy.data.scenes.new('Stereo projection regression')
        scene.render.resolution_x=1500;scene.render.resolution_y=1000
        scene.render.resolution_percentage=100
        objects=[]
        try:
            for x in (-.032,.032):
                data=bpy.data.cameras.new('Stereo regression');obj=bpy.data.objects.new(data.name,data)
                data.sensor_fit='HORIZONTAL';data.sensor_width=36
                scene.collection.objects.link(obj);objects.append(obj)
                self.stereo.set_camera(obj,dict(position=[x,0,1],rotation=[0,0,0,1],
                    slopes=[-.45-x,.45-x,-.3,.3],near=.01,far=1000))
            with bpy.context.temp_override(scene=scene,view_layer=scene.view_layers[0]):
                depsgraph=bpy.context.evaluated_depsgraph_get()
                basis=Matrix.Rotation(math.pi/2,4,'X')
                for obj in objects:
                    projection=obj.calc_matrix_camera(depsgraph,x=1500,y=1000)
                    for x,y,u,v in [(-.45,-.3,-1,-1),(.45,.3,1,1),(0,0,0,0)]:
                        clip=projection @ obj.matrix_world.inverted() @ basis @ Vector((x,y,0,1))
                        self.assertAlmostEqual(u,clip.x/clip.w,places=5)
                        self.assertAlmostEqual(v,clip.y/clip.w,places=5)
        finally:
            for obj in objects:
                data=obj.data;bpy.data.objects.remove(obj,do_unlink=True);bpy.data.cameras.remove(data)
            bpy.data.scenes.remove(scene)
    def test_resizing_preserves_associated_colour_and_coverage(self):
        rgba=np.empty((8,12,4),dtype=np.float32);rgba[:]=[.2,.1,.05,.5]
        resized=self.stereo.resize_rgba(rgba,16,12)
        self.assertEqual((12,16,4),resized.shape)
        np.testing.assert_allclose(resized,np.broadcast_to(rgba[0,0],resized.shape),rtol=1e-6)
