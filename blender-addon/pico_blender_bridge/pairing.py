"""Versioned local pairing payload and QR PNG; no Blender or pip dependency."""
import struct
import zlib
from urllib.parse import urlencode, urlsplit
from .qrcodegen import QrCode


def pairing_payload(endpoint, token, session):
    address = urlsplit(endpoint)
    if address.scheme != "http" or not address.hostname or address.username or address.password:
        raise ValueError("Expected local bridge HTTP endpoint")
    if address.path not in ("", "/") or address.query or address.fragment:
        raise ValueError("Bridge endpoint must not contain a path or query")
    if not token or not session:
        raise ValueError("Start Bridge before displaying pairing")
    return "blender-pico://pair?" + urlencode({"v": "1", "endpoint": endpoint.rstrip("/"),
                                               "token": token, "session": session})


def qr_matrix(payload):
    code = QrCode.encode_text(payload, QrCode.Ecc.MEDIUM)
    return [[code.get_module(x, y) for x in range(code.get_size())]
            for y in range(code.get_size())]


def matrix_png(matrix, scale=6, border=4):
    """Lossless monochrome PNG with the required four-module quiet zone."""
    size = len(matrix)
    if not size or any(len(row) != size for row in matrix):
        raise ValueError("Expected square matrix")
    width = (size + border * 2) * scale
    rows = bytearray()
    for y in range(size + border * 2):
        pixels = bytes(0 if border <= y < size + border and border <= x < size + border
                       and matrix[y - border][x - border] else 255
                       for x in range(size + border * 2))
        row = b"\x00" + bytes(v for v in pixels for _ in range(scale))
        rows.extend(row * scale)
    def chunk(kind, value):
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, width, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows))) + chunk(b"IEND", b""))
