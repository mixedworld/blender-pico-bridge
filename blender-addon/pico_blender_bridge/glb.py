"""Attach only public inspection metadata, keeping it part of the model digest."""
import json
import struct


def with_explosion_parts(data, parts):
    magic, version, total = struct.unpack_from('<III', data)
    if magic != 0x46546C67 or version != 2 or total != len(data):
        raise ValueError('Invalid GLB header')
    length, chunk_type = struct.unpack_from('<II', data, 12)
    if chunk_type != 0x4E4F534A:
        raise ValueError('GLB first chunk must be JSON')
    document = json.loads(data[20:20 + length])
    # Never copy Blender custom properties: these can contain private pairing settings.
    document.setdefault('asset', {}).setdefault('extras', {})['picoBridge'] = {'explodeParts': parts}
    encoded = json.dumps(document, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    encoded += b' ' * (-len(encoded) % 4)
    remaining = data[20 + length:]
    return struct.pack('<III', magic, version, 20 + len(encoded) + len(remaining)) + \
        struct.pack('<II', len(encoded), chunk_type) + encoded + remaining


def material_profile(engine, requested="AUTO"):
    """Workbench displays viewport colours rather than authored shader networks."""
    if requested not in {"AUTO", "PBR", "VIEWPORT"}:
        raise ValueError("Unknown native material profile")
    return "VIEWPORT" if requested == "VIEWPORT" or (requested == "AUTO" and engine == "BLENDER_WORKBENCH") else "PBR"


def with_viewport_materials(data, colors):
    """Create matte material-colour approximations in the GLB only; never edit bpy materials.

    Colour factors remain scene-linear, as required by glTF. MatCap, procedural shaders,
    studio lighting and view-dependent effects cannot be represented by these materials.
    """
    import math
    magic, version, total = struct.unpack_from('<III', data)
    if magic != 0x46546C67 or version != 2 or total != len(data):
        raise ValueError('Invalid GLB header')
    length, chunk_type = struct.unpack_from('<II', data, 12)
    if chunk_type != 0x4E4F534A:
        raise ValueError('GLB first chunk must be JSON')
    document = json.loads(data[20:20 + length])
    materials = document.setdefault('materials', [])
    fallback = None
    # Missing primitive materials also need an explicit dielectric fallback, rather
    # than glTF's default white metallic material.
    for mesh in document.get('meshes', []):
        for primitive in mesh.get('primitives', []):
            if 'material' not in primitive:
                if fallback is None:
                    fallback = len(materials)
                    materials.append({'name': 'PICO Viewport Default'})
                primitive['material'] = fallback
    for material in materials:
        rgba = list(colors.get(material.get('name'), (.8, .8, .8, 1)))
        if len(rgba) != 4 or not all(isinstance(c, (int, float)) and math.isfinite(c) for c in rgba):
            raise ValueError('Invalid viewport colour')
        rgba = [min(1., max(0., c)) for c in rgba]
        material['pbrMetallicRoughness'] = {'baseColorFactor': rgba, 'metallicFactor': 0., 'roughnessFactor': 1.}
        for key in ('normalTexture', 'occlusionTexture', 'emissiveTexture', 'emissiveFactor', 'extensions', 'alphaCutoff'):
            material.pop(key, None)
        material['alphaMode'] = 'BLEND' if rgba[3] < 1 else 'OPAQUE'
    # Remove only unused material extensions. Geometry/texture extensions may be
    # declared without an extension object (for example KHR_mesh_quantization).
    def used_extensions(value):
        result = set()
        if isinstance(value, dict):
            result.update(value.get('extensions', {}))
            for child in value.values():
                result.update(used_extensions(child))
        elif isinstance(value, list):
            for child in value:
                result.update(used_extensions(child))
        return result
    used = used_extensions(document)
    for key in ('extensionsUsed', 'extensionsRequired'):
        if key in document:
            document[key] = [name for name in document[key] if not name.startswith('KHR_materials_') or name in used]
            if not document[key]:
                document.pop(key)
    document.setdefault('asset', {}).setdefault('extras', {}).setdefault('picoBridge', {})['nativeMaterials'] = 'VIEWPORT'
    encoded = json.dumps(document, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    encoded += b' ' * (-len(encoded) % 4)
    remaining = data[20 + length:]
    return struct.pack('<III', magic, version, 20 + len(encoded) + len(remaining)) + \
        struct.pack('<II', len(encoded), chunk_type) + encoded + remaining
