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

    def test_viewport_commands_are_opt_in_session_bound_and_deduplicated(self):
        body = dict(session=self.server.session, requestId='gesture-1', pan=[.1,0,0], rotation=[0,0,0,1], zoom=1.2)
        def post(value, token='test-secret'):
            return self.post_visibility(value, token, '/v1/viewport-delta')
        for token, code in (('wrong',401), ('test-secret',409)):
            with self.assertRaises(urllib.error.HTTPError) as error: post(body, token)
            self.assertEqual(error.exception.code, code)
        self.server.publish_state({'viewportControlEnabled': True})
        for patch in ({'zoom': 0}, {'pan': [True,0,0]}, {'rotation': [0,0,0,0]}, {'pan': [2,0,0]}, {'zoom': float('nan')}, {'extra': 1}):
            with self.assertRaises(urllib.error.HTTPError) as error: post({**body, **patch})
            self.assertEqual(error.exception.code, 400)
        with self.assertRaises(urllib.error.HTTPError) as error: post({**body,'session':'old'})
        self.assertEqual(error.exception.code,409)
        with post(body) as response: self.assertEqual(response.status, 202)
        with post(body) as response: self.assertEqual(response.status, 200)
        with self.assertRaises(urllib.error.HTTPError) as error: post({**body,'requestId':'gesture-2'})
        self.assertEqual(error.exception.code, 429)
        self.assertEqual(self.server.take_viewport_command(), body)
        self.assertIsNone(self.server.take_viewport_command())
        with post({**body,'requestId':'expired'}): pass
        self.server.viewport_command = (0,self.server.viewport_command[1])
        self.assertIsNone(self.server.take_viewport_command())

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

    def test_camera_stream_is_latest_wins_ordered_leased_and_model_bound(self):
        revision=self.server.publish_model(b'camera fixture')
        body=dict(session=self.server.session,stream='head-1',sequence=1,revision=revision,active=True,
                  position=[0,0,1],rotation=[0,0,0,1],slopes=[-.4,.4,-.2,.2],near=1,far=100)
        def post(value,token='test-secret'):
            return self.post_visibility(value,token,'/v1/view-camera')
        for token,code in [('wrong',401),('test-secret',409)]:
            with self.assertRaises(urllib.error.HTTPError) as error: post(body,token)
            self.assertEqual(code,error.exception.code)
        self.server.publish_state({'cameraControlEnabled':True})
        for patch in ({'rotation':[0,0,0,0]},{'slopes':[1,1,-1,1]}, {'near':-1}, {'position':[True,0,1]}, {'sequence':True}, {'extra':1}):
            with self.assertRaises(urllib.error.HTTPError) as error: post({**body,**patch})
            self.assertEqual(400,error.exception.code)
        with post(body): pass
        with post({**body,'sequence':3,'position':[.2,0,1]}): pass
        with post({**body,'sequence':2}): pass
        self.assertEqual(3,self.server.take_camera_command()['sequence'])
        self.assertTrue(self.server.camera_lease_active())
        with self.assertRaises(urllib.error.HTTPError) as error: post({**body,'stream':'other'})
        self.assertEqual(409,error.exception.code)
        release={k:body[k] for k in ('session','stream','revision')}
        with post({**release,'sequence':4,'active':False}): pass
        self.assertFalse(self.server.camera_lease_active())
        self.assertFalse(self.server.take_camera_command()['active'])
        with post({**body,'sequence':5}): pass
        self.server.publish_model(b'changed')
        self.assertFalse(self.server.camera_lease_active())


if __name__ == "__main__":
    unittest.main()
