import importlib.util
import json
from pathlib import Path
import struct
import hashlib
import unittest

spec = importlib.util.spec_from_file_location('bridge_glb', Path(__file__).resolve().parents[1] / 'blender-addon/pico_blender_bridge/glb.py')
glb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(glb)

class InspectionMetadataTest(unittest.TestCase):
    def test_offset_edit_changes_revision_without_changing_geometry(self):
        document = json.dumps({'asset': {'version':'2.0'}, 'nodes':[{'name':'rim'}]}).encode()
        document += b' ' * (-len(document)%4)
        binary = struct.pack('<II', 4, 0x004E4942) + b'abcd'
        original = struct.pack('<III', 0x46546C67, 2, 20+len(document)+len(binary)) + struct.pack('<II',len(document),0x4E4F534A) + document + binary
        first = glb.with_explosion_parts(original, [{'name':'rim','offset':[1,0,0]}])
        second = glb.with_explosion_parts(original, [{'name':'rim','offset':[2,0,0]}])
        self.assertNotEqual(hashlib.sha256(first).digest(),hashlib.sha256(second).digest())
        for result in (first, second):
            length = struct.unpack_from('<I', result, 12)[0]
            self.assertEqual(binary, result[20+length:])
            self.assertEqual(len(result), struct.unpack_from('<I', result, 8)[0])
            parsed = json.loads(result[20:20+length])
            self.assertEqual(['picoBridge'], list(parsed['asset']['extras']))
            self.assertEqual([{'name':'rim'}], parsed['nodes'])
        self.assertEqual(first,glb.with_explosion_parts(original,[{'name':'rim','offset':[1,0,0]}]))
