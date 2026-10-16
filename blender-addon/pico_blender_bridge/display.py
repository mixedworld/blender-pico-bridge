"""Disposable QR preview and pixel-aligned calibration overlay on one viewport."""
import os
import tempfile
import bpy
import bpy.utils.previews
import gpu
from gpu_extras.batch import batch_for_shader
from .pairing import qr_matrix, matrix_png
from .calibration import MARKERS, board_layout, visible_markers


class PairingDisplay:
    def __init__(self, payload, area):
        self.area_id = area.as_pointer() if area and area.type == 'VIEW_3D' else None
        self.overlay = False
        self.show_qr = False
        self.center_only = False
        self.board = None
        self.connected = False
        self.previews = bpy.utils.previews.new()
        descriptor, self.path = tempfile.mkstemp(prefix='pico-pair-', suffix='.png')
        os.close(descriptor)
        self.set_payload(payload)
        self.handler = bpy.types.SpaceView3D.draw_handler_add(self.draw, (), 'WINDOW', 'POST_PIXEL')

    def set_payload(self, payload):
        self.matrix = qr_matrix(payload)
        with open(self.path, 'wb') as stream:
            stream.write(matrix_png(self.matrix))
        if 'pair' in self.previews:
            del self.previews['pair']
        self.icon = self.previews.load('pair', self.path, 'IMAGE').icon_id

    def target(self):
        for window in bpy.context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D' and area.as_pointer() == self.area_id:
                    region = next((r for r in area.regions if r.type == 'WINDOW'), None)
                    return area, region
        return None, None

    def layout(self, area, region):
        # Blender's WINDOW region also includes overlapping headers/sidebar/tool regions.
        # Keep detection borders inside the unobstructed part, retaining full-viewport UVs.
        left = top = right = bottom = 0
        for panel in area.regions:
            if panel.width <= 1 or panel.height <= 1:
                continue
            if panel.type in {'UI', 'TOOLS'}:
                if panel.x + panel.width/2 < region.x + region.width/2:
                    left = max(left, panel.x+panel.width-region.x)
                else:
                    right = max(right, region.x+region.width-panel.x)
            elif panel.type in {'HEADER', 'TOOL_HEADER', 'ASSET_SHELF', 'ASSET_SHELF_HEADER'}:
                if panel.y + panel.height/2 < region.y + region.height/2:
                    bottom = max(bottom, panel.y+panel.height-region.y)
                else:
                    top = max(top, region.y+region.height-panel.y)
        return board_layout(region.width, region.height, (left, top, right, bottom))

    def update(self, connected):
        self.connected = connected
        area, region = self.target()
        self.board = self.layout(area, region) if self.overlay and region else None
        if area:
            area.tag_redraw()

    def draw(self):
        area, region = bpy.context.area, bpy.context.region
        if not (self.overlay or self.show_qr) or not area or area.as_pointer() != self.area_id or not region:
            return
        shader = gpu.shader.from_builtin('UNIFORM_COLOR')
        def rect(x, y, width, height, black):
            points = ((x,y),(x+width,y),(x+width,y+height),(x,y+height))
            shader.bind()
            shader.uniform_float('color', (0,0,0,1) if black else (1,1,1,1))
            batch_for_shader(shader, 'TRIS', {'pos': points}, indices=((0,1,2),(0,2,3))).draw(shader)
        def matrix(values, left, top, size, border):
            pitch = size / len(values)
            rect(left-border*pitch, region.height-top-size-border*pitch,
                 size+2*border*pitch, size+2*border*pitch, False)
            points = []
            indices = []
            for y,row in enumerate(values):
                for x,pixel in enumerate(row):
                    if pixel:
                        px,py = left+x*pitch, region.height-top-(y+1)*pitch
                        index = len(points)
                        points.extend(((px,py),(px+pitch,py),(px+pitch,py+pitch),(px,py+pitch)))
                        indices.extend(((index,index+1,index+2),(index,index+2,index+3)))
            if points:
                shader.bind()
                shader.uniform_float('color', (0,0,0,1))
                batch_for_shader(shader, 'TRIS', {'pos': points}, indices=indices).draw(shader)
        previous_depth = gpu.state.depth_test_get()
        previous_blend = gpu.state.blend_get()
        gpu.state.depth_test_set('NONE')
        gpu.state.blend_set('NONE')
        try:
            board = self.layout(area, region)
            if not board:
                return
            for marker in visible_markers(board, self.overlay, self.show_qr, self.center_only):
                x,y,size,_ = marker['rect']
                values = [[p == '1' for p in row] for row in MARKERS[marker['id']]]
                matrix(values, x, y, size, 1)
            if self.show_qr:
                modules = len(self.matrix)
                qr_room = min(region.width, region.height)*0.75
                if self.overlay:
                    tl, tr, br, bl = [marker['rect'] for marker in board['markers']]
                    quiet = tl[2]/6
                    qr_room = min(qr_room, max(tr[0]-tl[0]-tl[2], bl[1]-tl[1]-tl[3])-2*quiet-16)
                pitch = max(1, int(qr_room)//(modules+8))
                size = pitch * modules
                matrix(self.matrix, (region.width-size)//2, (region.height-size)//2, size, 4)
        finally:
            gpu.state.depth_test_set(previous_depth)
            gpu.state.blend_set(previous_blend)

    def close(self):
        bpy.types.SpaceView3D.draw_handler_remove(self.handler, 'WINDOW')
        bpy.utils.previews.remove(self.previews)
        if os.path.exists(self.path):
            os.unlink(self.path)
        area, _ = self.target()
        if area:
            area.tag_redraw()
