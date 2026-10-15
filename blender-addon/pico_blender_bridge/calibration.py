"""Screen-space layout for an OpenCV DICT_4X4_50 viewport calibration board.

Patterns generated with OpenCV 4.12.0 generateImageMarker, IDs 0..3.
Coordinates are pixels from the viewport's TOP LEFT, independent of UI scale.
No physical dimensions are inferred here; the PICO depth path measures them.
"""

MARKERS = (
    ('111111', '101001', '110101', '111001', '111011', '111111'),
    ('111111', '111111', '100001', '101101', '101011', '111111'),
    ('111111', '111001', '111001', '111011', '100101', '111111'),
    ('111111', '101101', '101101', '110111', '110011', '111111'),
)


def board_layout(width, height, insets=(0, 0, 0, 0)):
    left, top, right, bottom = insets
    usable_width, usable_height = width-left-right, height-top-bottom
    size = min(192, (min(usable_width, usable_height) - 48) // 4)
    if size < 36:
        return None
    # Preserve a full white module and 24px of breathing room inside visible content.
    margin = (size + 5) // 6 + 24
    return {"version": 1, "dictionary": "DICT_4X4_50", "width": width, "height": height,
            "markers": [{"id": index, "rect": [x, y, size, size]}
                        for index, (x, y) in enumerate(((left+margin, top+margin),
                            (width-right-margin-size, top+margin),
                            (width-right-margin-size, height-bottom-margin-size),
                            (left+margin, height-bottom-margin-size)))]}
