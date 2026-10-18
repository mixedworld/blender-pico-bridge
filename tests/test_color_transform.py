import importlib.util
from pathlib import Path
import unittest

try:
    import numpy as np
    import PyOpenColorIO as ocio
except ImportError:
    np = ocio = None

source = Path(__file__).parents[1] / 'blender-addon/pico_blender_bridge/color_transform.py'
spec = importlib.util.spec_from_file_location('bridge_color_transform_tests', source)
module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


@unittest.skipIf(ocio is None, "Run in Blender's Python for its active OCIO config")
class DisplayTransformTest(unittest.TestCase):
    def setUp(self):
        self.transform = module.DisplayTransform()
        self.working = self.transform.config.getColorSpace(ocio.ROLE_SCENE_LINEAR).getName()
        self.settings = dict(view='Standard', working=self.working)
        if self.working != 'Linear Rec.709' or 'Standard' not in self.transform.config.getViews('sRGB'):
            self.skipTest('Reference tests use the bundled Blender Rec.709/sRGB configuration')

    def convert(self, values, **settings):
        return self.transform.convert(np.array(values, dtype=np.float32), **dict(self.settings, **settings))

    def test_standard_encodes_linear_middle_grey_once(self):
        value = self.convert([[.18, .18, .18, 1]])[0]
        expected = round((1.055 * .18 ** (1 / 2.4) - .055) * 255)
        self.assertEqual(118, expected)
        np.testing.assert_allclose(value, [expected]*3+[255], atol=1)

    def test_hdr_highlights_are_tone_mapped_before_quantization(self):
        if 'Filmic' not in self.transform.config.getViews('sRGB'):
            self.skipTest('Filmic unavailable')
        values = self.convert([[1,1,1,1],[5,5,5,1]], view='Filmic')
        self.assertGreater(int(values[1,0]), int(values[0,0]))
        self.assertLess(int(values[1,0]), 255)
        self.assertEqual(np.uint8, values.dtype)

    def test_alpha_is_not_gamma_corrected_or_used_as_straight_colour(self):
        full = self.convert([[.18,.18,.18,1]])[0]
        # Half-covered pixels store half the linear radiance before colour management.
        half = self.convert([[.09,.09,.09,.5],[9,4,2,0]])
        np.testing.assert_allclose(half[0,:3], full[:3].astype(float)*.5, atol=1)
        self.assertEqual(128, half[0,3])
        np.testing.assert_array_equal(half[1], [0,0,0,0])

    def test_exposure_gamma_and_cached_settings_update(self):
        base = self.convert([[.09,.09,.09,1]])[0,0]
        bright = self.convert([[.09,.09,.09,1]], exposure=1)[0,0]
        expected = round((1.055 * .18 ** (1 / 2.4) - .055) * 255)
        self.assertAlmostEqual(expected, int(bright), delta=1)
        self.assertGreater(bright, base)
        gamma = self.convert([[.09,.09,.09,1]], exposure=1, gamma=2)[0,0]
        self.assertAlmostEqual(round((expected/255)**.5*255), int(gamma), delta=1)
        self.assertEqual(base, self.convert([[.09,.09,.09,1]])[0,0])

    def test_queued_frame_keeps_its_colour_processor_after_settings_change(self):
        processor = self.transform.processor(**self.settings)
        self.transform.processor(**dict(self.settings, exposure=2))
        pixels = np.array([[.18,.18,.18,1]], dtype=np.float32)
        result = self.transform.convert(pixels, processor=processor)
        np.testing.assert_allclose(result[0], [118,118,118,255], atol=1)
        np.testing.assert_array_equal(pixels[0], np.array([.18,.18,.18,1], dtype=np.float32))
