"""Reversible camera isolation limited to geometry visible in the capture view layer."""
import uuid


class CaptureVisibility:
    MARKER = '_pico_bridge_camera_mask'

    def __init__(self):
        self.token = uuid.uuid4().hex
        self.saved = {}

    def refresh(self, all_objects, eligible, sources, isolate):
        eligible, sources = set(eligible), set(sources)
        # ID properties follow object copies. Recover copied temporary visibility rather
        # than treating camera=False inherited from our mask as the artist's preference.
        for obj in all_objects:
            try:
                if obj.get(self.MARKER) == self.token and obj not in self.saved:
                    self.saved[obj] = True
            except (ReferenceError, RuntimeError, TypeError, AttributeError):
                continue
        for obj in list(self.saved):
            if not isolate or obj not in eligible or obj in sources:
                self._restore(obj)
        if not isolate:
            return
        for obj in eligible - sources:
            try:
                if obj in self.saved or not obj.visible_camera or obj.get(self.MARKER):
                    continue
                obj[self.MARKER] = self.token
                self.saved[obj] = True
                obj.visible_camera = False
            except (ReferenceError, RuntimeError, TypeError, AttributeError):
                self._restore(obj)

    def _restore(self, obj):
        original = self.saved.pop(obj, None)
        try:
            if original is not None:
                obj.visible_camera = original
            if obj.get(self.MARKER) == self.token:
                del obj[self.MARKER]
        except (ReferenceError, RuntimeError, TypeError, AttributeError):
            pass

    def restore(self, all_objects):
        self.refresh(all_objects, (), (), False)
