"""Draws the launcher icon and the Android TV banner (app/res/drawable-nodpi).

    python3 tools/artwork.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/Inter.ttf")
ICON = os.path.join(ROOT, "app/assets/icons/tv.png")
OUT = os.path.join(ROOT, "app/res/drawable-nodpi")
TOP, BOTTOM = (20, 60, 70), (60, 40, 110)   # Aurora: teal into violet


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.6 + y / h * 0.4)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
    return img


def glyph(size):
    g = Image.open(ICON).convert("RGBA")
    scale = size / max(g.size)
    return g.resize((round(g.width * scale), round(g.height * scale)), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    banner = gradient(640, 360)
    tv = glyph(150)
    banner.paste(tv, (60, (360 - tv.height) // 2), tv)
    font = ImageFont.truetype(FONT, 78)
    values = []
    for axis in font.get_variation_axes():
        name = axis["name"] if isinstance(axis["name"], str) else axis["name"].decode()
        values.append(700 if name.lower().startswith("weight") else axis["default"])
    font.set_variation_by_axes(values)
    d = ImageDraw.Draw(banner)
    d.text((240, 112), "Debloat", font=font, fill=(255, 255, 255))
    d.text((240, 192), "TV", font=font, fill=(200, 230, 225))
    banner.save(os.path.join(OUT, "banner.png"))
    icon = gradient(432, 432)
    tv = glyph(250)
    icon.paste(tv, ((432 - tv.width) // 2, (432 - tv.height) // 2), tv)
    icon.save(os.path.join(OUT, "icon.png"))
    print("banner.png and icon.png in", OUT)


if __name__ == "__main__":
    main()
