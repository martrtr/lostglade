#!/usr/bin/env python3
"""Generate translucent camera-size frames for the picker."""

from pathlib import Path

from PIL import Image, ImageDraw


GLYPH_WIDTH = 176
GLYPH_HEIGHT = 222
GRID_CELL = 18
GRID_LEFT = 7
GRID_TOP = 17
GRID_COLUMNS = 6
GRID_ROWS = 4
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/lg2/textures/font/camera_menu_selection.png"
FRAME_COLOR = (0x50, 0xFF, 0x00, 0xFF)
# Keep the selection readable without competing with the camera panel below.
# 0x20 is four times more transparent than the previous 0x80 fill.
FRAME_FILL_COLOR = (0x50, 0xFF, 0x00, 0x20)
FRAME_WIDTH = 2
# The prior panel-derived outline was GRID_CELL * size + 1. This outline is
# exactly five pixels narrower and centred within that former footprint.
FRAME_INSET_LEFT = 2
FRAME_INSET_TOP = 2
FRAME_NARROWING = 5
FRAME_ADVANCE_SENTINEL_X = 173


def selection_bounds(maps_wide: int, maps_high: int) -> tuple[int, int, int, int]:
    left = GRID_LEFT + FRAME_INSET_LEFT
    top = GRID_TOP + FRAME_INSET_TOP
    width = maps_wide * GRID_CELL + 1 - FRAME_NARROWING
    height = maps_high * GRID_CELL + 1 - FRAME_NARROWING
    return left, top, left + width - 1, top + height - 1


def draw_frame(glyph: Image.Image, maps_wide: int, maps_high: int) -> tuple[int, int, int, int]:
    """Draw only the 2px #50ff00 outer frame around the camera preview."""
    left, top, right, bottom = selection_bounds(maps_wide, maps_high)
    draw = ImageDraw.Draw(glyph)

    draw.rectangle((left, top, right, bottom), outline=FRAME_COLOR, width=FRAME_WIDTH)

    # Bitmap glyph advance is calculated from the rightmost non-transparent
    # pixel. The alpha-one marker is visually invisible but keeps every glyph
    # at the panel's 175px advance without adding another visible colour.
    glyph.putpixel((FRAME_ADVANCE_SENTINEL_X, GLYPH_HEIGHT - 1), (0x50, 0xFF, 0x00, 1))
    return left, top, right, bottom


def main() -> None:
    atlas = Image.new("RGBA", (GLYPH_WIDTH * GRID_COLUMNS, GLYPH_HEIGHT * GRID_ROWS), (0, 0, 0, 0))
    for maps_high in range(1, GRID_ROWS + 1):
        for maps_wide in range(1, GRID_COLUMNS + 1):
            origin_x = (maps_wide - 1) * GLYPH_WIDTH
            origin_y = (maps_high - 1) * GLYPH_HEIGHT
            glyph = Image.new("RGBA", (GLYPH_WIDTH, GLYPH_HEIGHT), (0, 0, 0, 0))
            left, top, right, bottom = selection_bounds(maps_wide, maps_high)
            preview_width = right - left + 1 - 2 * FRAME_WIDTH
            preview_height = bottom - top + 1 - 2 * FRAME_WIDTH
            glyph.paste(FRAME_FILL_COLOR, (left + FRAME_WIDTH, top + FRAME_WIDTH, left + FRAME_WIDTH + preview_width, top + FRAME_WIDTH + preview_height))
            draw_frame(glyph, maps_wide, maps_high)
            atlas.alpha_composite(glyph, (origin_x, origin_y))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(OUTPUT)


if __name__ == "__main__":
    main()
