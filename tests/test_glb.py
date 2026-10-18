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

class ViewportMaterialsTest(unittest.TestCase):
    def make(self, document):
        encoded=json.dumps(document).encode();encoded+=b' '*(-len(encoded)%4)
        self.binary=struct.pack('<II',4,0x004E4942)+b'abcd'
        return struct.pack('<III',0x46546C67,2,20+len(encoded)+len(self.binary))+struct.pack('<II',len(encoded),0x4E4F534A)+encoded+self.binary
    def unpack(self, data):
        size=struct.unpack_from('<I',data,12)[0]
        self.assertEqual(self.binary,data[20+size:])
        self.assertEqual(len(data),struct.unpack_from('<I',data,8)[0])
        return json.loads(data[20:20+size])
    def test_profile_keeps_authored_pbr_unless_workbench_or_explicit_override(self):
        for engine in ('CYCLES','BLENDER_EEVEE'):
            self.assertEqual('PBR',glb.material_profile(engine))
            self.assertEqual('VIEWPORT',glb.material_profile(engine,'VIEWPORT'))
        self.assertEqual('VIEWPORT',glb.material_profile('BLENDER_WORKBENCH'))
        self.assertEqual('PBR',glb.material_profile('BLENDER_WORKBENCH','PBR'))
        with self.assertRaises(ValueError):glb.material_profile('CYCLES','unknown')
    def test_matte_colours_replace_gloss_and_maps_without_changing_geometry_animation_or_original(self):
        original={'asset':{'version':'2.0','extras':{'picoBridge':{'explodeParts':[{'name':'part'}]}}},
            'nodes':[{'name':'part','mesh':0}], 'animations':[{'name':'clip'}],
            'meshes':[{'primitives':[{'attributes':{'POSITION':0},'material':0}]}],
            'materials':[{'name':'paint','doubleSided':True,'extras':{'author':'kept'},
                'pbrMetallicRoughness':{'metallicFactor':1,'roughnessFactor':.02,'baseColorTexture':{'index':0}},
                'normalTexture':{'index':1},'emissiveFactor':[1,1,1],
                'extensions':{'KHR_materials_clearcoat':{'clearcoatFactor':1}}}],
            'extensionsUsed':['KHR_materials_clearcoat','KHR_mesh_quantization'],
            'extensionsRequired':['KHR_materials_clearcoat']}
        data=self.make(original)
        result=self.unpack(glb.with_viewport_materials(data,{'paint':[.12,.3,.07,1]}))
        for key in ('nodes','animations','meshes'):self.assertEqual(original[key],result[key])
        mat=result['materials'][0]
        self.assertEqual({'baseColorFactor':[.12,.3,.07,1],'metallicFactor':0.,'roughnessFactor':1.},mat['pbrMetallicRoughness'])
        self.assertNotIn('normalTexture',mat);self.assertNotIn('extensions',mat);self.assertNotIn('emissiveFactor',mat)
        self.assertTrue(mat['doubleSided']);self.assertEqual({'author':'kept'},mat['extras'])
        self.assertEqual(original['asset']['extras']['picoBridge']['explodeParts'],result['asset']['extras']['picoBridge']['explodeParts'])
        self.assertNotIn('extensionsRequired',result)
        # Attribute/quantization extensions may be declared without an extension object.
        self.assertIn('KHR_mesh_quantization',result.get('extensionsUsed',[]))
        self.assertEqual(original,self.unpack(data))
    def test_unassigned_material_gets_dielectric_fallback_and_transparency_is_explicit(self):
        data=self.make({'asset':{'version':'2.0'},'meshes':[{'primitives':[{'attributes':{}}]}]})
        doc=self.unpack(glb.with_viewport_materials(data,{}))
        self.assertEqual(0,doc['meshes'][0]['primitives'][0]['material'])
        self.assertEqual(0,doc['materials'][0]['pbrMetallicRoughness']['metallicFactor'])
        data=self.make({'asset':{'version':'2.0'},'materials':[{'name':'glass'}]})
        doc=self.unpack(glb.with_viewport_materials(data,{'glass':[.2,.4,.6,.5]}))
        self.assertEqual('BLEND',doc['materials'][0]['alphaMode'])
        with self.assertRaises(ValueError):glb.with_viewport_materials(data,{'glass':[float('nan'),0,0,1]})
