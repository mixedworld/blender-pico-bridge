import importlib
import pathlib
import struct
import sys
import types
import unittest
import urllib.parse
import zlib

root = pathlib.Path(__file__).parents[1] / 'blender-addon/pico_blender_bridge'
package = types.ModuleType('bridge_pairing_test')
package.__path__ = [str(root)]
sys.modules[package.__name__] = package
pairing = importlib.import_module('bridge_pairing_test.pairing')
calibration = importlib.import_module('bridge_pairing_test.calibration')


class PairingTest(unittest.TestCase):
    def test_versioned_payload_keeps_special_characters(self):
        payload = pairing.pairing_payload('http://192.168.1.3:8766', 'a+/=&_?b', 'fixture-session')
        parsed = urllib.parse.urlsplit(payload)
        self.assertEqual((parsed.scheme, parsed.netloc), ('blender-pico', 'pair'))
        fields = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(fields['token'], ['a+/=&_?b'])
        self.assertEqual(fields['v'], ['1'])
        for endpoint in ('https://example.com', 'http://a:b@localhost', 'http://localhost/execute'):
            with self.assertRaises(ValueError):
                pairing.pairing_payload(endpoint, 'fixture', 'fixture')

    def test_png_contains_lossless_matrix_and_quiet_zone(self):
        matrix = pairing.qr_matrix('fixture-qr')
        png = pairing.matrix_png(matrix, scale=2)
        offset, compressed = 8, b''
        while offset < len(png):
            size = struct.unpack_from('>I', png, offset)[0]
            kind = png[offset+4:offset+8]
            data = png[offset+8:offset+8+size]
            self.assertEqual(zlib.crc32(kind+data), struct.unpack_from('>I', png, offset+8+size)[0])
            if kind == b'IHDR': width, height = struct.unpack_from('>II', data)
            if kind == b'IDAT': compressed += data
            offset += size+12
        rows = zlib.decompress(compressed)
        self.assertEqual(width, (len(matrix)+8)*2)
        self.assertEqual(width, height)
        for y in range(height):
            row = rows[y*(width+1)+1:(y+1)*(width+1)]
            for x, pixel in enumerate(row):
                mx, my = x//2-4, y//2-4
                expected = 0 if 0 <= mx < len(matrix) and 0 <= my < len(matrix) and matrix[my][mx] else 255
                self.assertEqual(pixel, expected)

    def test_marker_layout_uses_top_left_coordinates_and_disjoint_regions(self):
        board = calibration.board_layout(1920, 1080)
        self.assertEqual([m['id'] for m in board['markers']], [0, 1, 2, 3])
        for marker in board['markers']:
            x,y,w,h = marker['rect']
            self.assertTrue(0 <= x < x+w <= 1920)
            self.assertTrue(0 <= y < y+h <= 1080)
        self.assertIsNone(calibration.board_layout(100, 100))

    def test_marker_quiet_zones_avoid_overlapping_blender_ui_regions(self):
        board = calibration.board_layout(2849,1260,(60,26,400,80))
        self.assertIsNotNone(board)
        for marker in board['markers']:
            x,y,w,h = marker['rect']
            quiet = w/6
            self.assertGreaterEqual(x-quiet,60+24)
            self.assertGreaterEqual(y-quiet,26+24)
            self.assertLessEqual(x+w+quiet,2849-400-24)
            self.assertLessEqual(y+h+quiet,1260-80-24)
        self.assertIsNone(calibration.board_layout(300,300,(0,0,200,0)))
