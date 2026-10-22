"""Makes the app's icons: white glyphs with transparency, cut from Google's Material Symbols Rounded font.

    python3 tools/icons.py MaterialSymbolsRounded.ttf MaterialSymbolsRounded.codepoints app/assets/icons

Each icon is drawn big, trimmed to its ink and scaled down, so the PNGs are crisp and tightly framed. The app draws
them as masks in whatever color it needs.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

SIZE = 160          # final height of an icon, in pixels
SCALE = 6           # drawn this many times bigger, then scaled down (smooth edges)
WEIGHT = 600        # Material Symbols weight axis

# file name -> (Material Symbols name, filled)
ICONS = {
    "live_tv": ("live_tv", True), "input": ("settings_input_hdmi", True), "apps": ("apps", True),
    "wifi": ("wifi", True), "settings": ("settings", True), "home": ("home", True), "tv": ("tv", False),
    "sparkles": ("auto_awesome", True), "play": ("play_arrow", True), "tv_settings": ("display_settings", True),
    "sunny": ("sunny", True), "clear_night": ("clear_night", True), "partly_cloudy_day": ("partly_cloudy_day", True),
    "partly_cloudy_night": ("partly_cloudy_night", True), "cloud": ("cloud", True), "foggy": ("foggy", True),
    "rainy": ("rainy", True), "snow": ("ac_unit", False), "thunderstorm": ("thunderstorm", True),
    "remote": ("settings_remote", True),
    "rows": ("view_agenda", True), "location": ("location_on", True),
    "key": ("key", True),
}


def axis_values(font, filled):
    out = []
    for axis in font.get_variation_axes():
        name = axis["name"] if isinstance(axis["name"], str) else axis["name"].decode()
        name = name.lower()
        if "fill" in name:
            out.append(1 if filled else 0)
        elif "weight" in name or name == "wght":
            out.append(WEIGHT)
        elif "optical" in name or name == "opsz":
            out.append(48)
        else:
            out.append(0)
    return out


def main():
    font_path, codepoints_path, out_dir = sys.argv[1:4]
    codes = {}
    with open(codepoints_path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2:
                codes[parts[0]] = chr(int(parts[1], 16))
    os.makedirs(out_dir, exist_ok=True)
    big = SIZE * SCALE
    font = ImageFont.truetype(font_path, big)
    for file, (name, filled) in ICONS.items():
        font.set_variation_by_axes(axis_values(font, filled))
        canvas = Image.new("L", (big * 2, big * 2), 0)
        ImageDraw.Draw(canvas).text((big // 2, big // 2), codes[name], font=font, fill=255)
        box = canvas.getbbox()
        glyph = canvas.crop(box)
        h = SIZE
        w = max(1, round(glyph.width * h / glyph.height))
        if w > SIZE:   # wide icons fit the same square
            w, h = SIZE, max(1, round(glyph.height * SIZE / glyph.width))
        glyph = glyph.resize((w, h), Image.LANCZOS)
        icon = Image.new("RGBA", (w, h), (255, 255, 255, 0))
        icon.putalpha(glyph)
        icon.save(os.path.join(out_dir, file + ".png"))
    print(f"{len(ICONS)} icons in {out_dir}")


if __name__ == "__main__":
    main()
