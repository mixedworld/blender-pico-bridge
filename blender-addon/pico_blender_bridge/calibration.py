"""Screen-space layout for an OpenCV DICT_4X4_50 viewport calibration board.

OpenCV DICT_4X4_50 patterns, IDs 0..4; canonical corner order tested natively.
Coordinates are pixels from the viewport's TOP LEFT, independent of UI scale.
No physical dimensions are inferred here; PICO uses depth or an explicitly known marker side.
"""

MARKERS = (
    ('111111', '101001', '110101', '111001', '111011', '111111'),
    ('111111', '111111', '100001', '101101', '101011', '111111'),
    ('111111', '111001', '111001', '111011', '100101', '111111'),
    ('111111', '101101', '101101', '110111', '110011', '111111'),
    ('111111', '110101', '110111', '101101', '100011', '111111'),
)


def board_layout(width, height, insets=(0, 0, 0, 0)):
    left, top, right, bottom = insets
    usable_width, usable_height = width-left-right, height-top-bottom
    size = min(192, (min(usable_width, usable_height) - 48) // 4)
    if size < 36:
        return None
    # Preserve a full white module and 24px of breathing room inside visible content.
    margin = (size + 5) // 6 + 24
    board = {"version": 1, "dictionary": "DICT_4X4_50", "width": width, "height": height,
            "markers": [{"id": index, "rect": [x, y, size, size]}
                        for index, (x, y) in enumerate(((left+margin, top+margin),
                            (width-right-margin-size, top+margin),
                            (width-right-margin-size, height-bottom-margin-size),
                            (left+margin, height-bottom-margin-size)))]}

    # Separate optional field keeps the existing four-corner protocol compatible.
    cx, cy = left+(usable_width-size)//2, top+(usable_height-size)//2
    q = size/6
    if all(cx+size+q <= m['rect'][0]-q or cx-q >= m['rect'][0]+size+q or
           cy+size+q <= m['rect'][1]-q or cy-q >= m['rect'][1]+size+q for m in board['markers']):
        board['referenceMarker'] = {"id": 4, "rect": [cx, cy, size, size]}
    return board


def visible_markers(board, overlay, show_qr, center_only=False):
    if not overlay:
        return []
    reference = board.get('referenceMarker')
    if center_only and reference and not show_qr:
        return [reference]
    return board['markers'] + ([reference] if reference and not show_qr else [])
