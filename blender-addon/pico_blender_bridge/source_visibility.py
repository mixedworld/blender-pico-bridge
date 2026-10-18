"""Temporary source hiding, scoped to Blender's active view layer."""
import logging


def hide_source_objects(objects, view_layer, saved):
    for obj in objects:
        if obj.type not in {"MESH", "CURVE", "FONT", "SURFACE", "META"}:
            continue
        # Collections can include objects excluded from this view layer. Blender's
        # hide_get can return False for them, but hide_set raises and kills a timer.
        if view_layer.objects.get(obj.name) != obj:
            continue
        try:
            hidden = obj.hide_get(view_layer=view_layer)
            saved[obj.name] = (hidden, view_layer)
            obj.hide_set(True, view_layer=view_layer)
        except (RuntimeError, ReferenceError) as error:
            # One unavailable object must not stop camera updates or lease expiry.
            logging.getLogger(__name__).warning("Cannot temporarily hide source: %s", error)
