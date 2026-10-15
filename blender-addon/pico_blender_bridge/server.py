"""Authenticated LAN transport with one bounded, reversible visibility command. This module never imports bpy."""
import hashlib
import hmac
import json
import math
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class BridgeServer:
    def __init__(self, host="0.0.0.0", port=8766, token=None):
        self.token = token or secrets.token_urlsafe(24)
        self.session = secrets.token_hex(12)
        self.lock = threading.Lock()
        self.state = {"protocol": 1, "session": self.session, "sequence": 0,
                      "revision": None, "playing": False, "error": "Export a model first"}
        self.models = {}
        self.last_client = 0.0
        self.visibility_hidden = False
        self.visibility_expires = 0.0
        self.viewport_command = None
        self.viewport_requests = {}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                supplied = self.headers.get("Authorization", "")
                if not hmac.compare_digest(supplied, "Bearer " + owner.token):
                    self.respond(401, b'{"error":"Pairing token required"}', "application/json")
                    return
                path = urlsplit(self.path).path
                with owner.lock:
                    owner.last_client = time.monotonic()
                    if path == "/v1/state":
                        data = json.dumps(owner.state, separators=(",", ":")).encode()
                        mime = "application/json"
                    elif path.startswith("/v1/models/") and path.endswith(".glb"):
                        revision = path.removeprefix("/v1/models/").removesuffix(".glb")
                        data = owner.models.get(revision)
                        mime = "model/gltf-binary"
                    else:
                        data = None
                        mime = "application/json"
                self.respond(200 if data is not None else 404,
                             data if data is not None else b'{"error":"Not found"}', mime)

            def do_POST(self):
                if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + owner.token):
                    self.respond(401, b'{"error":"Pairing token required"}', "application/json")
                    return
                path = urlsplit(self.path).path
                if path not in ("/v1/source-visibility", "/v1/viewport-delta"):
                    self.respond(404, b'{"error":"Not found"}', "application/json")
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= (2048 if path == "/v1/viewport-delta" else 64):
                        raise ValueError("Invalid body size")
                    body = json.loads(self.rfile.read(length))
                    if path == "/v1/viewport-delta":
                        owner.validate_viewport_delta(body)
                        with owner.lock:
                            if body["session"] != owner.session or not owner.state.get("viewportControlEnabled"):
                                self.respond(409, b'{"error":"Viewport control disabled or session changed"}', "application/json")
                                return
                            request_id = body["requestId"]
                            if request_id in owner.viewport_requests:
                                self.respond(200, b'{"ok":true,"duplicate":true}', "application/json")
                                return
                            if owner.viewport_command is not None:
                                self.respond(429, b'{"error":"Previous viewport command pending"}', "application/json")
                                return
                            owner.viewport_command = (time.monotonic(), body)
                            owner.viewport_requests[request_id] = True
                            while len(owner.viewport_requests) > 128:
                                owner.viewport_requests.pop(next(iter(owner.viewport_requests)))
                        self.respond(202, b'{"ok":true}', "application/json")
                        return
                    if not isinstance(body, dict) or set(body) != {"hidden"} or type(body["hidden"]) is not bool:
                        raise ValueError("Expected hidden boolean only")
                except (ValueError, TypeError):
                    self.respond(400, b'{"error":"Invalid command body"}', "application/json")
                    return
                with owner.lock:
                    owner.visibility_hidden = body["hidden"]
                    owner.visibility_expires = time.monotonic() + 8.0
                self.respond(200, b'{"ok":true}', "application/json")

            def respond(self, status, data, mime):
                self.send_response(status)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.httpd = ThreadingHTTPServer((host, port), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever,
                                       name="PICO Blender Bridge", daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.httpd.server_address[1]

    def publish_model(self, data, metadata=None):
        # Content addressing makes identical exports idempotent.
        revision = hashlib.sha256(data).hexdigest()
        with self.lock:
            self.models[revision] = data
            self.state.update(metadata or {})
            self.state["revision"] = revision
            self.state["modelPath"] = f"/v1/models/{revision}.glb"
            self.state["modelBytes"] = len(data)
            while len(self.models) > 3:
                self.models.pop(next(iter(self.models)))
        return revision

    def publish_state(self, update):
        with self.lock:
            self.state = {**self.state, **update,
                          "sequence": self.state["sequence"] + 1}

    def source_hidden(self):
        # Main-thread Blender timer consumes this intent; HTTP threads never touch bpy.
        with self.lock:
            return self.visibility_hidden and time.monotonic() < self.visibility_expires

    @staticmethod
    def validate_viewport_delta(body):
        if not isinstance(body, dict) or set(body) != {"session", "requestId", "pan", "rotation", "zoom"}:
            raise ValueError("Expected bounded viewport delta")
        for name in ("session", "requestId"):
            if not isinstance(body[name], str) or not 1 <= len(body[name]) <= 64:
                raise ValueError("Invalid command identity")
        def numeric(value):
            return type(value) in (int, float) and abs(value) <= 1e6 and math.isfinite(value)
        for name, size in (("pan", 3), ("rotation", 4)):
            values = body[name]
            if not isinstance(values, list) or len(values) != size or not all(numeric(v) for v in values):
                raise ValueError("Invalid vector")
        if any(abs(v) > 1 for v in body["pan"]):
            raise ValueError("Pan exceeds one viewport span")
        if not .99 <= sum(v*v for v in body["rotation"]) <= 1.01:
            raise ValueError("Rotation must be normalized")
        if not numeric(body["zoom"]) or not .25 <= body["zoom"] <= 4:
            raise ValueError("Invalid zoom")

    def take_viewport_command(self):
        """Called only by Blender's main-thread timer; stale commands never jump the view."""
        with self.lock:
            pending, self.viewport_command = self.viewport_command, None
            if not pending or time.monotonic() - pending[0] > .5:
                return None
            return pending[1]

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
