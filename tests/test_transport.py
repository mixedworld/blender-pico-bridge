import importlib.util
import pathlib
import urllib.error
import urllib.request
import unittest
import json

source = pathlib.Path(__file__).parents[1] / "blender-addon/pico_blender_bridge/server.py"
spec = importlib.util.spec_from_file_location("transport", source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.server = module.BridgeServer("127.0.0.1", 0, "test-secret")

    def tearDown(self):
        self.server.close()

    def fetch(self, path, token="test-secret"):
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.port}{path}",
                                        headers={"Authorization": "Bearer " + token})
        return urllib.request.urlopen(request, timeout=2)

    def test_pairing_is_required_and_paths_are_closed(self):
        for path, token, status in (("/v1/state", "wrong", 401),
                                    ("/../../etc/passwd", "test-secret", 404)):
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.fetch(path, token)
            self.assertEqual(error.exception.code, status)

    def post_visibility(self, body, token="test-secret", path="/v1/source-visibility"):
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.port}{path}", data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + token}, method="POST")
        return urllib.request.urlopen(request, timeout=2)

    def test_visibility_is_authenticated_bounded_and_expires(self):
        for body, token, code in (({"hidden": True}, "wrong", 401), ({"hidden": "yes"}, "test-secret", 400),
                                  ({"hidden": True, "python": "evil"}, "test-secret", 400)):
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.post_visibility(body, token)
            self.assertEqual(code, error.exception.code)
            self.assertFalse(self.server.source_hidden())
        with self.post_visibility({"hidden": True}): pass
        self.assertTrue(self.server.source_hidden())
        self.server.visibility_expires = 0
        self.assertFalse(self.server.source_hidden())
        with self.post_visibility({"hidden": True}): pass
        with self.post_visibility({"hidden": False}): pass
        self.assertFalse(self.server.source_hidden())

    def test_revision_is_content_addressed_and_download_matches(self):
        data = b"glTF fixture"
        revision = self.server.publish_model(data)
        self.assertEqual(revision, self.server.publish_model(data))
        with self.fetch(f"/v1/models/{revision}.glb") as response:
            self.assertEqual(response.read(), data)

    def test_state_sequence_and_retention(self):
        first = self.server.publish_model(b"first")
        for n in range(5):
            self.server.publish_model(str(n).encode())
            self.server.publish_state({"frame": n, "playing": n == 4})
        with self.fetch("/v1/state") as response:
            state = json.load(response)
        self.assertEqual(state["sequence"], 5)
        self.assertEqual(state["frame"], 4)
        self.assertTrue(state["playing"])
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.fetch(f"/v1/models/{first}.glb")
        self.assertEqual(error.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
