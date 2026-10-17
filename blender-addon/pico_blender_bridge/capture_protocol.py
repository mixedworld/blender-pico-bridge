"""Private parent-to-encoder pipe: bounded, atomic RGBA frames, never a network listener."""
import struct

HEADER = struct.Struct("!4sHHIQ")
MAGIC = b"PBA1"
MAX_PIXELS = 8_388_608
ATLAS_FORMAT = "color-alpha-top-bottom-v1"
STEREO_ATLAS_FORMAT = "stereo-sbs-color-alpha-top-bottom-v1"


def encoded_size(width, height, limit=960, stereo=False):
    if stereo and (width % 2 or width < 8):
        raise ValueError("Stereo frame must contain two equal eye tiles")
    w, h = output_size(width // 2 if stereo else width, height, limit)
    return w * (2 if stereo else 1), h


def frame_header(width, height, timestamp_ns):
    if type(width) is not int or type(height) is not int or not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > MAX_PIXELS:
        raise ValueError("Capture dimensions exceed the frame budget")
    if type(timestamp_ns) is not int or not 0 <= timestamp_ns < 2**64:
        raise ValueError("Invalid capture timestamp")
    return HEADER.pack(MAGIC, width, height, width * height * 4, timestamp_ns)


def read_exact(source, length):
    chunks = bytearray()
    while len(chunks) < length:
        part = source.read(length - len(chunks))
        if not part:
            raise EOFError("Capture pipe closed")
        chunks.extend(part)
    return bytes(chunks)


def read_frame(source):
    magic, width, height, length, stamp = HEADER.unpack(read_exact(source, HEADER.size))
    expected = frame_header(width, height, stamp)
    if magic != MAGIC or length != width * height * 4 or expected != HEADER.pack(magic, width, height, length, stamp):
        raise ValueError("Invalid capture frame")
    return width, height, stamp, read_exact(source, length)


def output_size(width, height, limit=960):
    factor = min(1.0, limit / max(width, height))
    # Both halves remain aligned with a 4:2:0 chroma grid.
    return max(4, int(width * factor) // 4 * 4), max(4, int(height * factor) // 4 * 4)
