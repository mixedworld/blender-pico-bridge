import importlib.util
import json
from pathlib import Path
import unittest
import urllib.request
import urllib.error
import http.client

source=Path(__file__).parents[1]/"blender-addon/pico_blender_bridge/server.py"
spec=importlib.util.spec_from_file_location("render_transport",source)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RenderStreamTest(unittest.TestCase):
    def setUp(self): self.server=module.BridgeServer(host="127.0.0.1",port=0,token="test-secret")
    def tearDown(self): self.server.close()
    def request(self,body=None,token="test-secret",path="/v1/render-stream"):
        request=urllib.request.Request(f"http://127.0.0.1:{self.server.port}"+path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
        with urllib.request.urlopen(request) as response:return json.load(response)
    def test_offer_answer_and_peer_lease_are_bounded_and_authenticated(self):
        self.request({"type":"offer","stream":"one","sdp":"v=0\r\nfixture"})
        self.request({"type":"answer","stream":"one","sdp":"v=0\r\nanswer"})
        value=self.request();self.assertEqual("one",value["stream"]);self.assertIn("answer",value["answer"])
        with self.assertRaises(urllib.error.HTTPError) as error:self.request({"type":"offer","stream":"two","sdp":"v=0"})
        self.assertEqual(409,error.exception.code)
        with self.assertRaises(urllib.error.HTTPError) as error:self.request(token="wrong")
        self.assertEqual(401,error.exception.code)
        self.request({"type":"stop","stream":"wrong"});self.assertEqual("one",self.request()["stream"])
        self.request({"type":"stop","stream":"one"});self.assertIsNone(self.request()["stream"])
    def test_expired_peer_does_not_block_a_new_session(self):
        self.request({"type":"offer","stream":"old","sdp":"v=0"})
        self.server.render_signaling.viewer_until=0
        self.request({"type":"offer","stream":"new","sdp":"v=0"})
        self.assertEqual("new",self.request()["stream"])
        with self.assertRaises(urllib.error.HTTPError):self.request({"type":"answer","stream":"old","sdp":"v=0"})
    def test_invalid_payloads_cannot_replace_a_valid_offer(self):
        self.request({"type":"offer","stream":"one","sdp":"v=0"})
        invalid=[{},[],{"type":"offer","stream":"one","sdp":"invalid"},
            {"type":"offer","stream":"one","sdp":"v=0"+"x"*262144},
            {"type":"offer","stream":"one","sdp":"v=0","unexpected":1},
            {"type":"publisher","width":True,"height":1},
            {"type":"offer","stream":"../bad","sdp":"v=0"}]
        for body in invalid:
            with self.assertRaises(urllib.error.HTTPError) as error:self.request(body)
            self.assertEqual(400,error.exception.code)
        self.assertEqual("v=0",self.request()["offer"])
    def test_publisher_readiness_expires_and_crop_has_no_credentials(self):
        self.request({"type":"publisher","width":1280,"height":720})
        self.assertTrue(self.request()["publisherReady"])
        self.request({"type":"unpublish"});self.assertFalse(self.request()["publisherReady"])
        self.server.publish_state({"captureViewport":{"x":10,"y":20,"width":800,"height":600,"windowWidth":1000,"windowHeight":900}})
        result=self.request();self.assertEqual(800,result["capture"]["width"]);self.assertNotIn("test-secret",json.dumps(result))
    def test_local_publisher_page_contains_no_pairing_secret(self):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.server.port}/render") as response:
            text=response.read().decode()
        self.assertIn("getDisplayMedia",text);self.assertNotIn(self.server.token,text)

    def test_trickle_candidates_are_scoped_deduplicated_and_cleared(self):
        self.request({"type":"offer","stream":"one","sdp":"v=0"})
        candidate={"type":"ice","stream":"one","side":"viewer","candidate":"candidate:fixture","mid":"0","index":0}
        self.request(candidate);self.request(candidate)
        self.assertEqual(1,len(self.request()["viewerCandidates"]))
        self.assertEqual([],self.request()["publisherCandidates"])
        with self.assertRaises(urllib.error.HTTPError): self.request(dict(candidate,stream="old"))
        self.request({"type":"stop","stream":"one"})
        self.assertEqual([],self.request()["viewerCandidates"])

    def test_trickle_candidate_count_and_payload_are_bounded(self):
        self.request({"type":"offer","stream":"one","sdp":"v=0"})
        candidate={"type":"ice","stream":"one","side":"publisher","candidate":"candidate:fixture","mid":"0","index":0}
        for change in ({"side":"other"},{"index":True},{"candidate":"bad"},{"mid":None},{"candidate":"candidate:"+"x"*8192}):
            with self.assertRaises(urllib.error.HTTPError) as error:self.request(dict(candidate,**change))
            self.assertEqual(400,error.exception.code)
        for i in range(64):self.request(dict(candidate,candidate="candidate:"+str(i)))
        with self.assertRaises(urllib.error.HTTPError) as error:self.request(candidate)
        self.assertEqual(409,error.exception.code)

    def test_alpha_format_is_explicit_validated_and_lease_scoped(self):
        body={"type":"publisher","width":640,"height":856,"format":"color-alpha-top-bottom-v1"}
        self.request(body)
        self.assertEqual("color-alpha-top-bottom-v1",self.request()["format"])
        with self.assertRaises(urllib.error.HTTPError):self.request(dict(body,format="unknown"))
        self.server.render_signaling.publisher_until=0
        self.assertEqual("opaque",self.request()["format"])
        self.request({"type":"publisher","width":640,"height":428})
        self.assertEqual("opaque",self.request()["format"])

    def test_server_restart_closes_persistent_handlers(self):
        connection=http.client.HTTPConnection("127.0.0.1",self.server.port,timeout=1)
        connection.request("GET","/v1/render-stream",headers={"Authorization":"Bearer test-secret"})
        response=connection.getresponse();response.read()
        self.assertFalse(response.will_close)
        self.server.close()
        with self.assertRaises((OSError,http.client.HTTPException)):
            connection.request("GET","/v1/render-stream",headers={"Authorization":"Bearer test-secret"})
            connection.getresponse()
        connection.close()


class ViewportCropTest(unittest.TestCase):
    def test_overlapping_chrome_and_projection_uvs(self):
        # A 1000x600 render region with a 200px right sidebar and a 30px top header.
        result=module._module.viewport_capture(1400,900,(0,100,1000,600),
            [("UI",800,100,200,600),("HEADER",0,670,1000,30),("WINDOW",0,100,1000,600)])
        self.assertEqual((0,100,800,570),tuple(result[k] for k in ("x","y","width","height")))
        self.assertEqual([0,0,.8,.95],result["crop"])
    def test_no_chrome_preserves_the_region(self):
        result=module._module.viewport_capture(1400,900,(100,200,1000,600),[])
        self.assertEqual([0,0,1,1],result["crop"])
        self.assertEqual(100,result["x"]);self.assertEqual(200,result["y"])
