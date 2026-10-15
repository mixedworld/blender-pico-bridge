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
