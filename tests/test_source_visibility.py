import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock


source = Path(__file__).parents[1] / "blender-addon/pico_blender_bridge/source_visibility.py"
spec = importlib.util.spec_from_file_location("source_visibility", source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def mesh(name, hidden=False):
    return SimpleNamespace(name=name, type="MESH", hide_get=Mock(return_value=hidden), hide_set=Mock())


class SourceVisibilityTest(unittest.TestCase):
    def test_excluded_collection_objects_are_not_hidden_or_saved(self):
        visible, excluded = mesh("Visible"), mesh("Excluded")
        layer = SimpleNamespace(objects={visible.name: visible})
        saved = {}
        module.hide_source_objects([excluded, visible], layer, saved)
        excluded.hide_get.assert_not_called()
        excluded.hide_set.assert_not_called()
        visible.hide_set.assert_called_once_with(True, view_layer=layer)
        self.assertEqual(saved, {visible.name: (False, layer)})

    def test_preserves_original_hidden_state_and_explicit_layer(self):
        original_hidden = mesh("Already hidden", hidden=True)
        layer = SimpleNamespace(objects={original_hidden.name: original_hidden})
        saved = {}
        module.hide_source_objects([original_hidden], layer, saved)
        original_hidden.hide_get.assert_called_once_with(view_layer=layer)
        self.assertEqual(saved[original_hidden.name], (True, layer))

    def test_unavailable_object_does_not_interrupt_remaining_objects(self):
        unavailable, visible = mesh("Unavailable"), mesh("Visible")
        unavailable.hide_set.side_effect = RuntimeError("Object left the view layer")
        layer = SimpleNamespace(objects={o.name: o for o in [unavailable, visible]})
        saved = {}
        with self.assertLogs("source_visibility", level="WARNING"):
            module.hide_source_objects([unavailable, visible], layer, saved)
        visible.hide_set.assert_called_once_with(True, view_layer=layer)
        # Retain the pre-hide state even if the failed write partially applied.
        self.assertEqual(saved[unavailable.name], (False, layer))
        self.assertEqual(saved[visible.name], (False, layer))


if __name__ == "__main__":
    unittest.main()
