import importlib.util
import io
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).parents[1]/'blender-addon/pico_blender_bridge'
spec=importlib.util.spec_from_file_location('capture_protocol',ROOT/'capture_protocol.py')
protocol=importlib.util.module_from_spec(spec);spec.loader.exec_module(protocol)
sys.modules.setdefault('capture_protocol',protocol)
spec=importlib.util.spec_from_file_location('render_worker',ROOT/'render_worker.py')
worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)

class CaptureProtocolTest(unittest.TestCase):
    def test_round_trip_and_truncated_pipe(self):
        raw=bytes(range(16));header=protocol.frame_header(2,2,123)
        self.assertEqual((2,2,123,raw),protocol.read_frame(io.BytesIO(header+raw)))
        with self.assertRaises(EOFError):protocol.read_frame(io.BytesIO(header+raw[:-1]))
    def test_rejects_invalid_header_before_reading_payload(self):
        for values in [(b'BAD!',2,2,16,0),(b'PBA1',4096,4096,67108864,0),(b'PBA1',2,2,15,0)]:
            with self.assertRaises(ValueError):protocol.read_frame(io.BytesIO(protocol.HEADER.pack(*values)))
        for size in [(True,2),(0,4),(4097,2)]:
            with self.assertRaises(ValueError):protocol.frame_header(*size,0)
    def test_output_aligns_both_halves_and_preserves_budget(self):
        w,h=protocol.output_size(1920,1080,960)
        self.assertEqual((960,540),(w,h));self.assertEqual(0,h%4)

try:
    import numpy as np
    import av
except ImportError:
    np=None

@unittest.skipIf(np is None,'Optional render-worker environment required')
class AlphaPackingTest(unittest.TestCase):
    def test_alpha_is_synchronized_and_unassociates_edges(self):
        rgba=np.zeros((4,4,4),dtype=np.uint8)
        rgba[:,:2]=[64,32,16,128]
        rgba[:,2:]=[255,255,255,0]
        result=worker.pack_atlas((4,4,1,rgba.tobytes()),128)
        self.assertEqual((8,4,3),result.shape)
        np.testing.assert_array_equal(result[0,0],[128,64,32])
        np.testing.assert_array_equal(result[0,3],[0,0,0])
        np.testing.assert_array_equal(result[4,0],[128,128,128])
        np.testing.assert_array_equal(result[7,3],[0,0,0])
    def test_straight_colour_can_pass_through_without_double_unassociation(self):
        rgba=np.full((4,4,4),128,dtype=np.uint8)
        result=worker.pack_atlas((4,4,1,rgba.tobytes()),128,False)
        self.assertEqual(128,int(result[0,0,0]))

@unittest.skipIf(np is None,'Optional render-worker environment required')
class StereoPackingTest(unittest.TestCase):
    def test_keeps_eye_order_alpha_and_aspect_while_resizing(self):
        rgba=np.zeros((12,32,4),dtype=np.uint8)
        rgba[:,:16]=[128,0,0,128]
        rgba[:,16:]=[0,255,0,255]
        atlas=worker.pack_atlas((32,12,42,rgba.tobytes()),8,True,True)
        self.assertEqual((8,16,3),atlas.shape)
        np.testing.assert_allclose(atlas[1,2],[255,0,0],atol=2)
        np.testing.assert_allclose(atlas[1,13],[0,255,0],atol=2)
        np.testing.assert_allclose(atlas[5,2],[128,128,128],atol=2)
        np.testing.assert_array_equal(atlas[5,13],[255,255,255])
        self.assertEqual((16,4),protocol.encoded_size(32,12,8,True))
    def test_rejects_an_incomplete_eye_pair(self):
        with self.assertRaises(ValueError):protocol.encoded_size(13,8,128,True)
