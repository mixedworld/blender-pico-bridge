"""Authenticated LAN transport with one bounded, reversible visibility command. This module never imports bpy."""
import hashlib
import hmac
import json
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
                if urlsplit(self.path).path != "/v1/source-visibility":
                    self.respond(404, b'{"error":"Not found"}', "application/json")
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 64:
                        raise ValueError("Invalid body size")
                    body = json.loads(self.rfile.read(length))
                    if not isinstance(body, dict) or set(body) != {"hidden"} or type(body["hidden"]) is not bool:
                        raise ValueError("Expected hidden boolean only")
                except (ValueError, TypeError):
                    self.respond(400, b'{"error":"Expected hidden boolean"}', "application/json")
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

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
