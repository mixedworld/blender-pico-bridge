"""Direct viewport RGBA capture. Only draw callbacks touch Blender/GPU state."""
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import time

import bpy
import gpu
import numpy as np

from .capture_protocol import frame_header, output_size
from .color_transform import DisplayTransform, ColorTransformError
from .capture_visibility import CaptureVisibility


def default_worker_python():
    root = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local/share")) / "blender-pico-bridge/render-worker"
    return root / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


class DirectViewportCapture:
    """One active viewport, one bounded latest-frame pipe, reversible camera-ray visibility."""
    def __init__(self, area, window, server, source_objects, python, fps=15, max_size=960, isolate_source=lambda: True, stereo_mm=0, shading="RENDERED"):
        self.rig = None
        self.stereo_mm = stereo_mm
        self.engine = window.scene.render.engine
        if self.engine not in {"CYCLES", "BLENDER_EEVEE", "BLENDER_WORKBENCH"}:
            raise RuntimeError("Direct capture supports Cycles, Eevee and Workbench")
        if shading not in {"RENDERED", "SOLID", "MATERIAL", "WIREFRAME"}:
            raise ValueError("Unsupported stream appearance")
        if self.engine == "BLENDER_WORKBENCH" and shading == "MATERIAL":
            raise ValueError("Material Preview requires Cycles or Eevee as the scene renderer")
        self.shading = shading
        self.label = ({"CYCLES":"Cycles","BLENDER_EEVEE":"Eevee","BLENDER_WORKBENCH":"Workbench"}[self.engine]
            if shading == "RENDERED" else {"SOLID":"Solid","MATERIAL":"Material Preview","WIREFRAME":"Wireframe"}[shading])
        self.cycles_beauty = self.engine == "CYCLES" and shading == "RENDERED"
        self.color = DisplayTransform() if self.cycles_beauty else None
        self.engine_type = None
        self.offscreen = None
        self.handler = None
        self.busy = False
        if self.cycles_beauty:
            import cycles
            self.engine_type = cycles.CyclesRender
            self.original_draw = self.engine_type.view_draw
        self.area = area
        self.window = window
        self.scene = window.scene
        self.space = area.spaces.active
        self.server = server
        self.source_objects = source_objects
        self.isolate_source = isolate_source
        self.fps = fps
        self.max_size = max_size
        self.running = False
        self.error = None
        self.frames = 0
        self.last_size = None
        self.last_capture = 0
        self.last_read_ms = 0
        self.last_convert_ms = 0
        self.queue = queue.Queue(maxsize=1)
        self.visibility = CaptureVisibility()
        self.flags = self.visibility.saved
        self.source_key = None
        self.stop_event = threading.Event()
        self.before = (self.scene.render.film_transparent, self.space.shading.type)
        render = self.scene.render
        self.before_render = (render.resolution_x,render.resolution_y,render.resolution_percentage,
            render.pixel_aspect_x,render.pixel_aspect_y)
        self.process = None
        self.writer = None
        self.reader = None
        python = Path(python).expanduser()
        if not python.is_file():
            raise RuntimeError("Set up the render worker; choose its Python in add-on Preferences")
        try:
            self.process = subprocess.Popen([str(python), str(Path(__file__).with_name("render_worker.py"))],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, bufsize=0)
            config = {"endpoint": "http://127.0.0.1:" + str(server.port), "token": server.token,
                      "fps": fps, "maxSize": max_size, "premultiplied": True, "stereo": bool(stereo_mm)}
            self.process.stdin.write(json.dumps(config).encode() + b"\n")
            self.writer = threading.Thread(target=self._write, name="PICO render pixels", daemon=True)
            self.reader = threading.Thread(target=self._status, name="PICO render status", daemon=True)
            self.writer.start()
            self.reader.start()
            self.scene.render.film_transparent = True
            self.space.shading.type = self.shading
            self.running = True
            self.refresh_source()
            if stereo_mm:
                from .stereo_capture import StereoViewportRig
                region = next(r for r in area.regions if r.type == "WINDOW")
                render.resolution_x,render.resolution_y = region.width,region.height
                render.resolution_percentage = 100
                render.pixel_aspect_x = render.pixel_aspect_y = 1.0
                self.rig = StereoViewportRig(area, window, stereo_mm, fps, viewport_renderers=self.cycles_beauty)

            def draw(engine, context, depsgraph):
                self.original_draw(engine, context, depsgraph)
                if self.running and context.scene == self.scene:
                    if self.rig and context.area in self.rig.views:
                        self.capture_stereo(context)
                    elif not self.rig and context.area == self.area:
                        self.capture(context)

            self.draw = draw
            if self.engine_type:
                self.engine_type.view_draw = draw
            else:
                self.handler = bpy.types.SpaceView3D.draw_handler_add(self.capture_offscreen, (), "WINDOW", "POST_PIXEL")
        except Exception:
            self.stop()
            raise

    def _write(self):
        try:
            while not self.stop_event.is_set():
                try:
                    header, pixels, processor = self.queue.get(timeout=.2)
                except queue.Empty:
                    continue
                if isinstance(pixels, tuple):
                    from .stereo_capture import resize_rgba
                    left, right, width, height = pixels
                    pixels = np.concatenate([resize_rgba(eye, width, height) for eye in (left, right)], axis=1)
                    if processor is None:
                        pixels = pixels[::-1].tobytes()
                if processor is not None:
                    start = time.perf_counter()
                    pixels = self.color.convert(pixels, processor=processor)[::-1].tobytes()
                    self.last_convert_ms = (time.perf_counter() - start) * 1000
                for value in (header, pixels):
                    remaining = memoryview(value)
                    while remaining and not self.stop_event.is_set():
                        count = self.process.stdin.write(remaining)
                        if not count:
                            return
                        remaining = remaining[count:]
        except (OSError, ValueError, RuntimeError):
            if not self.stop_event.is_set():
                self.error = "Render pixel conversion or encoder pipe stopped; restart the stream"

    def _status(self):
        try:
            for line in self.process.stderr:
                if len(line) > 4096:
                    continue
                try:
                    event = json.loads(line)
                except (ValueError, UnicodeError):
                    continue
                if event.get("event") == "error":
                    self.error = "Render encoder stopped; check its dependencies and restart the stream"
        except (OSError, ValueError):
            pass

    def refresh_source(self):
        if not self.engine_type:
            return  # Offscreen viewport modes include visible geometry; camera-ray isolation is Cycles only.
        with bpy.context.temp_override(window=self.window):
            sources = set(self.source_objects())
            eligible = [o for o in self.window.view_layer.objects
                if o.type in {"MESH", "CURVE", "FONT", "SURFACE", "META", "VOLUME"}
                and not o.hide_render and o.visible_get(view_layer=self.window.view_layer)]
            self.visibility.refresh(self.scene.objects, eligible, sources, self.isolate_source())

    def capture_offscreen(self):
        context = bpy.context
        if self.busy or not self.running or context.area != self.area:
            return
        if time.monotonic() - self.last_capture < 1 / self.fps:
            return
        self.busy = True
        overlay = self.space.overlay.show_overlays
        gizmo = self.space.show_gizmo
        camera_before = (self.space.camera, self.space.use_local_camera)
        try:
            self.space.overlay.show_overlays = False
            self.space.show_gizmo = False
            region = context.region
            width, height = output_size(region.width, region.height, self.max_size)
            if self.offscreen is None or (self.offscreen.width, self.offscreen.height) != (width, height):
                if self.offscreen:
                    self.offscreen.free()
                self.offscreen = gpu.types.GPUOffScreen(width, height, format="RGBA8")
            if self.rig:
                # Both eyes draw in this callback from the same evaluated scene.
                depsgraph = context.evaluated_depsgraph_get()
                for index, camera in enumerate(self.rig.cameras):
                    # Workbench Rendered also reads camera type/settings from SpaceView3D.
                    # Keep that camera consistent with the explicit eye view/projection.
                    self.space.use_local_camera = True
                    self.space.camera = camera
                    self.offscreen.draw_view3d(context.scene, context.view_layer, self.space, region,
                        camera.matrix_world.inverted(),camera.calc_matrix_camera(depsgraph,x=width,y=height),
                        do_color_management=True, draw_background=False)
                    with self.offscreen.bind():
                        self.capture_stereo(context, index=index, rect=(0,0,width,height))
            else:
                self.offscreen.draw_view3d(context.scene, context.view_layer, self.space, region,
                    self.space.region_3d.view_matrix, self.space.region_3d.window_matrix,
                    do_color_management=True, draw_background=False)
                with self.offscreen.bind():
                    self.capture(context)
        except (ValueError, RuntimeError, ReferenceError) as error:
            self.error = "Viewport capture unavailable (" + type(error).__name__ + ")"
        finally:
            self.space.camera, self.space.use_local_camera = camera_before
            self.space.overlay.show_overlays = overlay
            self.space.show_gizmo = gizmo
            self.busy = False

    def stereo_command(self, command):
        if self.rig:
            self.rig.submit(command)

    def capture_stereo(self, context, index=None, rect=None):
        if time.monotonic() - self.last_capture < 1 / self.fps:
            return
        try:
            if rect is None:
                crop = self.rig.crop(context)
                if crop is None:
                    return
                index, rect = crop
            x, y, width, height = rect
            processor = None
            if self.color:
                view = self.scene.view_settings
                if view.use_curve_mapping or getattr(view, "use_white_balance", False):
                    raise ColorTransformError("Direct Cycles colour requires view curves and white balance off")
                processor = self.color.processor(view=view.view_transform, look=view.look,
                    exposure=view.exposure, gamma=view.gamma,
                    display=self.scene.display_settings.display_device,
                    working=getattr(getattr(bpy.data,"colorspace",None),"working_space","scene_linear"))
            data = gpu.state.active_framebuffer_get().read_color(x,y,width,height,4,0,"FLOAT" if self.color else "UBYTE")
            pixels = np.frombuffer(data,dtype=np.float32 if self.color else np.uint8).reshape(height,width,4).copy()
            pair = self.rig.collect(index,pixels,processor)
            if pair is None:
                return
            left,right,processor = pair
            self.last_capture = time.monotonic()
            # Keep the shared camera aspect, even though auxiliary eye areas differ in size.
            aspect = self.scene.render.resolution_x / self.scene.render.resolution_y
            w,h = output_size(min(left.shape[1],right.shape[1]),
                min(left.shape[1],right.shape[1])/aspect,self.max_size)
            header = frame_header(w*2,h,time.monotonic_ns())
            value = (header,(left,right,w,h),processor)
            try:
                self.queue.put_nowait(value)
            except queue.Full:
                try:self.queue.get_nowait()
                except queue.Empty:pass
                try:self.queue.put_nowait(value)
                except queue.Full:pass
            self.frames += 1
            self.last_size = (w,h)
        except ColorTransformError as error:
            self.error = str(error)
        except (ValueError,RuntimeError,ReferenceError) as error:
            self.last_warning = "Waiting for stereo pixels ("+type(error).__name__+")"

    def capture(self, context):
        now = time.monotonic()
        if now - self.last_capture < 1 / self.fps:
            return
        self.last_capture = now
        try:
            x, y, width, height = gpu.state.viewport_get()
            header = frame_header(width, height, time.monotonic_ns())
            start = time.perf_counter()
            if self.color:
                view = self.scene.view_settings
                if view.use_curve_mapping or getattr(view, "use_white_balance", False):
                    raise ColorTransformError("Direct Cycles colour currently requires view curves and white balance off")
                buffer = gpu.state.active_framebuffer_get().read_color(x, y, width, height, 4, 0, "FLOAT")
                # Own plain CPU memory before leaving the draw thread. The immutable OCIO
                # processor is selected here; the writer must never read bpy or GPU state.
                pixels = np.frombuffer(buffer, dtype=np.float32).reshape(height, width, 4).copy()
                working = getattr(getattr(bpy.data, "colorspace", None), "working_space", "scene_linear")
                processor = self.color.processor(view=view.view_transform, look=view.look,
                    exposure=view.exposure, gamma=view.gamma,
                    display=self.scene.display_settings.display_device, working=working)
            else:
                # Offscreen draw_view3d(do_color_management=True) already applied the view.
                buffer = gpu.state.active_framebuffer_get().read_color(x, y, width, height, 4, 0, "UBYTE")
                pixels = np.frombuffer(buffer, dtype=np.uint8).reshape(height, width, 4)[::-1].tobytes()
                processor = None
            # The colour worker flips Cycles rows after processing; Eevee bytes are ready.
            value = (header, pixels, processor)
            try:
                self.queue.put_nowait(value)
            except queue.Full:
                try:
                    self.queue.get_nowait()
                except queue.Empty:
                    pass
                try:
                    self.queue.put_nowait(value)
                except queue.Full:
                    pass
            self.frames += 1
            self.last_size = output_size(width, height, self.max_size)
            self.last_read_ms = (time.perf_counter() - start) * 1000
        except ColorTransformError as error:
            self.error = str(error)
        except (ValueError, RuntimeError, ReferenceError) as error:
            self.last_warning = "Waiting for rendered pixels (" + type(error).__name__ + ")"

    def poll(self):
        if self.process and self.process.poll() is not None:
            self.error = self.error or "Render worker exited; check its Python environment"
        if self.error:
            error = self.error
            self.stop()
            return error
        if self.running:
            try:
                if self.area.type != "VIEW_3D" or self.window.scene != self.scene or self.space.shading.type != self.shading or self.scene.render.engine != self.engine:
                    self.stop()
                    return "Direct stream stopped after the viewport changed"
                self.refresh_source()
                if self.rig:
                    if not self.rig.alive():
                        self.stop()
                        return "Stereo stream stopped after its capture window closed"
                    self.rig.refresh()
                self.area.tag_redraw()
            except (ReferenceError, RuntimeError):
                self.stop()
                return "Direct stream stopped after its viewport closed"
        return None

    def stop(self):
        self.running = False
        self.stop_event.set()
        if self.engine_type and getattr(self, "draw", None) is self.engine_type.view_draw:
            self.engine_type.view_draw = self.original_draw
        if self.handler:
            bpy.types.SpaceView3D.draw_handler_remove(self.handler, "WINDOW")
            self.handler = None
        if self.rig:
            self.rig.close()
            self.rig = None
        if self.offscreen:
            self.offscreen.free()
            self.offscreen = None
        try:
            self.scene.render.film_transparent = self.before[0]
            try:
                self.space.shading.type = self.before[1]
            except TypeError:
                pass  # Workbench cannot restore a prior Eevee/Cycles Material Preview mode.
            if self.stereo_mm:
                render = self.scene.render
                render.resolution_x,render.resolution_y,render.resolution_percentage,render.pixel_aspect_x,render.pixel_aspect_y = self.before_render
        except (ReferenceError, RuntimeError):
            pass
        try:
            self.visibility.restore(self.scene.objects)
        except ReferenceError:
            self.visibility.restore(())
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=1)
        for thread in (self.writer, self.reader):
            if thread:
                thread.join(timeout=.5)
        if self.process:
            self.process.stdin.close()
            self.process.stderr.close()
        with self.server.lock:
            self.server.render_signaling.command({"type": "unpublish"})
