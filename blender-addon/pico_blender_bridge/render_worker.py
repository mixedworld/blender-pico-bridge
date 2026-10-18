"""Local WebRTC encoder for direct Blender pixels. Run with the optional worker environment."""
import asyncio
from fractions import Fraction
import json
import logging
import sys
import threading
import time
import urllib.request
import urllib.error
from urllib.parse import urlsplit

from capture_protocol import ATLAS_FORMAT, STEREO_ATLAS_FORMAT, output_size, encoded_size, read_frame


class LatestFrame:
    def __init__(self):
        self.lock = threading.Lock()
        self.value = None
        self.closed = False

    def read(self):
        with self.lock:
            return self.value, self.closed

    def consume(self, source):
        try:
            while True:
                value = read_frame(source)
                with self.lock:
                    self.value = value
        except (EOFError, ValueError, OSError):
            with self.lock:
                self.closed = True


def pack_atlas(frame, limit=960, premultiplied=True, stereo=False):
    """Encode zero RGB outside coverage; resample associated RGBA before unassociating edges."""
    import av
    import numpy as np
    width, height, stamp, raw = frame
    rgba = np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 4)
    if stereo:
        encoded_size(width, height, limit, True)
        # Resample eyes separately so RGB/alpha never bleed across the stereo seam.
        return np.concatenate([pack_atlas((width // 2, height, stamp,
            rgba[:, start:start + width // 2].tobytes()), limit, premultiplied)
            for start in (0, width // 2)], axis=1)
    w, h = output_size(width, height, limit)
    if (w, h) != (width, height):
        rgba = av.VideoFrame.from_ndarray(rgba, format="rgba").reformat(width=w, height=h, format="rgba").to_ndarray(format="rgba")
    alpha = rgba[:, :, 3:4].astype(np.uint16)
    rgb = rgba[:, :, :3].astype(np.uint16)
    if premultiplied:
        rgb = np.minimum(255, (rgb * 255 + alpha // 2) // np.maximum(alpha, 1))
    rgb = np.where(alpha > 0, rgb, 0).astype(np.uint8)
    atlas = np.empty((h * 2, w, 3), dtype=np.uint8)
    atlas[:h] = rgb
    atlas[h:] = alpha.astype(np.uint8)
    return atlas


def report(event, **data):
    print(json.dumps({"event": event, **data}), file=sys.stderr, flush=True)


async def run(config, mailbox):
    import av
    from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription, RTCRtpSender, VideoStreamTrack
    from aiortc.mediastreams import MediaStreamError
    from aiortc.sdp import candidate_from_sdp

    endpoint = config["endpoint"].rstrip("/")
    parsed = urlsplit(endpoint)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("Encoder signaling must be local")
    token = config["token"]
    fps = int(config.get("fps", 15))
    limit = int(config.get("maxSize", 960))
    stereo = config.get("stereo", False)
    if type(stereo) is not bool:
        raise ValueError("Invalid stereo configuration")
    if not 1 <= fps <= 30 or not 128 <= limit <= 1280 or not isinstance(token, str) or not 8 <= len(token) <= 512:
        raise ValueError("Invalid encoder configuration")

    async def request(body=None):
        def call():
            payload = json.dumps(body).encode() if body is not None else None
            request = urllib.request.Request(endpoint + "/v1/render-stream", data=payload,
                headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=3) as response:
                data = response.read(1_600_001)
            if len(data) > 1_600_000:
                raise ValueError("Oversized signaling response")
            return json.loads(data)
        return await asyncio.to_thread(call)

    class CaptureTrack(VideoStreamTrack):
        def __init__(self):
            super().__init__()
            self.tick = 0
            self.next_at = time.monotonic()
            self.stamp = None
            self.atlas = None

        async def recv(self):
            await asyncio.sleep(max(0, self.next_at - time.monotonic()))
            self.next_at = max(self.next_at + 1 / fps, time.monotonic())
            value, closed = mailbox.read()
            if closed or value is None:
                raise MediaStreamError
            if self.stamp != value[2]:
                self.atlas = await asyncio.to_thread(pack_atlas, value, limit, config.get("premultiplied", True), stereo)
                self.stamp = value[2]
            output = av.VideoFrame.from_ndarray(self.atlas, format="rgb24")
            output.pts = self.tick
            output.time_base = Fraction(1, 90000)
            self.tick += round(90000 / fps)
            return output

    peer = None
    stream = None
    track = None
    remote_index = 0
    last_beat = 0
    report("ready")
    try:
        while True:
            frame, closed = mailbox.read()
            if closed:
                break
            if frame is None:
                await asyncio.sleep(.05)
                continue
            # Imports/exports can block Blender capture. Keep the peer and replay the
            # last complete image; EOF/explicit stop still terminates publication.
            try:
                w, h = encoded_size(frame[0], frame[1], limit, stereo)
                if time.monotonic() - last_beat > 2:
                    await request({"type": "publisher", "width": w, "height": h * 2,
                        "format": STEREO_ATLAS_FORMAT if stereo else ATLAS_FORMAT})
                    last_beat = time.monotonic()
                state = await request()
                next_stream = state.get("stream")
                if next_stream != stream:
                    if peer:
                        await peer.close()
                        peer = None
                    if track:
                        track.stop()
                    stream = next_stream
                    remote_index = 0
                    if stream and state.get("offer"):
                        peer = RTCPeerConnection(RTCConfiguration(iceServers=[]))
                        track = CaptureTrack()
                        await peer.setRemoteDescription(RTCSessionDescription(sdp=state["offer"], type="offer"))
                        peer.addTrack(track)
                        capabilities = RTCRtpSender.getCapabilities("video")
                        for transceiver in peer.getTransceivers():
                            if transceiver.kind == "video":
                                transceiver.setCodecPreferences([c for c in capabilities.codecs if c.mimeType.lower() == "video/vp8"])
                        await peer.setLocalDescription(await peer.createAnswer())
                        await request({"type": "answer", "stream": stream, "sdp": peer.localDescription.sdp})
                        report("answer", width=w, height=h)
                if peer:
                    remote = state.get("viewerCandidates", [])
                    while remote_index < len(remote):
                        item = remote[remote_index]
                        candidate = candidate_from_sdp(item["candidate"].removeprefix("candidate:"))
                        candidate.sdpMid = item["mid"]
                        candidate.sdpMLineIndex = item["index"]
                        await peer.addIceCandidate(candidate)
                        remote_index += 1
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                if isinstance(error, urllib.error.HTTPError) and error.code in (400, 401, 403):
                    raise
                # A timed-out answer POST may already have reached the viewer. Do not
                # destroy its live peer and replace the answer under the same offer.
                last_beat = 0
                await asyncio.sleep(.5)
            await asyncio.sleep(.1)
    finally:
        if track:
            track.stop()
        if peer:
            await peer.close()
        try:
            await request({"type": "unpublish"})
        except Exception:
            pass


def main():
    logging.basicConfig(level=logging.ERROR)
    source = sys.stdin.buffer
    line = source.readline(16385)
    if len(line) > 16384 or not line.endswith(b"\n"):
        raise ValueError("Invalid encoder startup message")
    config = json.loads(line)
    mailbox = LatestFrame()
    reader = threading.Thread(target=mailbox.consume, args=(source,), daemon=True)
    reader.start()
    asyncio.run(run(config, mailbox))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Deliberately omit URLs, SDP, raw exception text and the private pairing token.
        report("error", kind=type(error).__name__)
        sys.exit(1)
