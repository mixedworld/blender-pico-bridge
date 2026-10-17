"""Bounded local WebRTC signaling. Video goes directly between the paired peers."""
import re
import time


class RenderSignaling:
    def __init__(self):
        self.stream = None
        self.offer = None
        self.answer = None
        self.viewer_until = 0.0
        self.publisher_until = 0.0
        self.publisher_size = [0, 0]
        self.candidates = {"viewer": [], "publisher": []}

    def state(self):
        now = time.monotonic()
        if self.viewer_until < now:
            self.stream = self.offer = self.answer = None
            self.candidates = {"viewer": [], "publisher": []}
        return {"stream": self.stream, "offer": self.offer, "answer": self.answer,
                "publisherReady": self.publisher_until > now, "size": self.publisher_size,
                "viewerCandidates": list(self.candidates["viewer"]), "publisherCandidates": list(self.candidates["publisher"])}

    def command(self, body):
        if not isinstance(body, dict):
            raise ValueError("Expected signaling object")
        kind = body.get("type")
        required = {"offer": {"type", "stream", "sdp"}, "answer": {"type", "stream", "sdp"},
                    "viewer": {"type", "stream"}, "publisher": {"type", "width", "height"},
                    "ice": {"type", "stream", "side", "candidate", "mid", "index"},
                    "stop": {"type", "stream"}, "unpublish": {"type"}}
        if not isinstance(kind,str) or kind not in required or set(body) != required[kind]:
            raise ValueError("Unknown signaling command")
        if "stream" in body and (not isinstance(body["stream"], str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", body["stream"])):
            raise ValueError("Invalid stream ID")
        if "sdp" in body and (not isinstance(body["sdp"], str) or not 1 <= len(body["sdp"]) <= 262144 or not body["sdp"].startswith("v=0")):
            raise ValueError("Invalid SDP")
        if kind == "publisher" and any(type(body[k]) is not int or not 1 <= body[k] <= 8192 for k in ("width", "height")):
            raise ValueError("Invalid video dimensions")
        if kind == "ice" and (body["side"] not in ("viewer", "publisher") or not isinstance(body["candidate"],str)
                or not body["candidate"].startswith("candidate:") or len(body["candidate"])>8192
                or not isinstance(body["mid"],str) or not 1<=len(body["mid"])<=64
                or type(body["index"]) is not int or not 0<=body["index"]<=32):
            raise ValueError("Invalid ICE candidate")
        self.state()
        now = time.monotonic()
        if kind == "offer":
            if self.stream is not None and self.stream != body["stream"]:
                return False
            if self.offer != body["sdp"]:
                self.answer = None
                self.candidates = {"viewer": [], "publisher": []}
            self.stream, self.offer = body["stream"], body["sdp"]
            self.viewer_until = now + 20
        elif kind == "answer":
            if self.stream != body["stream"]:
                return False
            self.answer = body["sdp"]
        elif kind == "ice":
            if self.stream != body["stream"]:
                return False
            candidate={key:body[key] for key in ("candidate","mid","index")}
            values=self.candidates[body["side"]]
            if candidate not in values:
                if len(values)>=64:
                    return False
                values.append(candidate)
        elif kind == "viewer":
            if self.stream != body["stream"]:
                return False
            self.viewer_until = now + 20
        elif kind == "publisher":
            self.publisher_until = now + 12
            self.publisher_size = [body["width"], body["height"]]
        elif kind == "stop":
            if self.stream == body["stream"]:
                self.stream = self.offer = self.answer = None
                self.viewer_until = 0
                self.candidates = {"viewer": [], "publisher": []}
        elif kind == "unpublish":
            self.publisher_until = 0
        return True


def viewport_capture(window_width, window_height, region, panels):
    """Crop overlapping Blender chrome; retain full-region UVs for off-axis projection."""
    x,y,width,height=region
    left=right=top=bottom=0
    for kind,px,py,pw,ph in panels:
        if pw<=1 or ph<=1:
            continue
        if kind in {"UI","TOOLS"}:
            if px+pw/2 < x+width/2: left=max(left,px+pw-x)
            else: right=max(right,x+width-px)
        elif kind in {"HEADER","TOOL_HEADER","ASSET_SHELF","ASSET_SHELF_HEADER"}:
            if py+ph/2 < y+height/2: bottom=max(bottom,py+ph-y)
            else: top=max(top,y+height-py)
    left=max(0,left);right=max(0,right);bottom=max(0,bottom);top=max(0,top)
    if width-left-right<=1 or height-top-bottom<=1:
        return None
    return {"x":x+left,"y":y+bottom,"width":width-left-right,"height":height-top-bottom,
            "windowWidth":window_width,"windowHeight":window_height,
            "crop":[left/width,bottom/height,1-right/width,1-top/height]}
