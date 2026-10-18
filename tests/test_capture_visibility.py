import importlib.util
from pathlib import Path
import unittest

source=Path(__file__).parents[1]/'blender-addon/pico_blender_bridge/capture_visibility.py'
spec=importlib.util.spec_from_file_location('capture_visibility_test',source)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Object:
    def __init__(self,visible=True):self.visible_camera=visible;self.properties={}
    def get(self,key):return self.properties.get(key)
    def __setitem__(self,key,value):self.properties[key]=value
    def __delitem__(self,key):del self.properties[key]
    def copy(self):
        obj=Object(self.visible_camera);obj.properties=self.properties.copy();return obj

class CaptureVisibilityTest(unittest.TestCase):
    def test_visible_scene_never_masks_excluded_library_templates(self):
        model,library=Object(),Object();mask=module.CaptureVisibility()
        mask.refresh([model,library],[model],[model],False)
        self.assertTrue(library.visible_camera)
        self.assertTrue(library.copy().visible_camera)
        self.assertFalse(mask.saved)
    def test_collection_isolation_only_masks_eligible_geometry(self):
        model,background,library=Object(),Object(),Object();mask=module.CaptureVisibility()
        mask.refresh([model,background,library],[model,background],[model],True)
        self.assertTrue(model.visible_camera);self.assertFalse(background.visible_camera);self.assertTrue(library.visible_camera)
        mask.restore([model,background,library]);self.assertTrue(background.visible_camera);self.assertFalse(background.properties)
    def test_copy_of_temporarily_masked_object_recovers_when_added_to_source(self):
        model,background=Object(),Object();mask=module.CaptureVisibility()
        mask.refresh([model,background],[model,background],[model],True)
        copy=background.copy();self.assertFalse(copy.visible_camera)
        mask.refresh([model,background,copy],[model,background,copy],[model,copy],True)
        self.assertTrue(copy.visible_camera);self.assertFalse(copy.properties)
        self.assertFalse(background.visible_camera)
    def test_stop_recovers_unobserved_copies_without_enabling_authored_hidden_objects(self):
        original,hidden=Object(),Object(False);mask=module.CaptureVisibility()
        mask.refresh([original,hidden],[original,hidden],[],True)
        copy=original.copy()
        mask.restore([original,hidden,copy])
        self.assertTrue(original.visible_camera);self.assertTrue(copy.visible_camera);self.assertFalse(hidden.visible_camera)
    def test_scope_or_view_layer_change_restores_previous_overrides(self):
        background=Object();mask=module.CaptureVisibility()
        mask.refresh([background],[background],[],True)
        mask.refresh([background],[],[],True)
        self.assertTrue(background.visible_camera);self.assertFalse(mask.saved)
