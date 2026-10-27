"""Build the real brand-icon sprite for the WebView UI.

Sources live in apk/brands/:
  * Simple Icons SVGs (CC0) for YouTube / Netflix / Spotify / Google Play / Airtel
  * the site's own favicon for Hotstar and Prime Video, which are not in Simple Icons

The Android WebView cannot load an external sprite, so the result is inlined
into the generated HTML by make_ui.py.
"""
import base64
import os
import re

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BRANDS = os.path.join(ROOT, "brands")
OUT = os.path.join(ROOT, "web", "brands.svg")

# Real brand colours for the monochrome Simple Icons paths.
COLORS = {
    "youtube": "#FF0000",
    "netflix": "#E50914",
    "spotify": "#1DB954",
    "airtel": "#E40000",
}

# Official Google Play facet colours: blue, green, yellow, red.
PLAY_COLORS = ["#00C4FF", "#00E676", "#FFC107", "#FF3A44"]
PLAY_PATHS = [
    "M1.337.924a1.486 1.486 0 0 0-.112.568v21.017c0 .217.045.419.124.6l11.155-11.087L1.337.924z",
    "M13.544 10.989l3.258-3.238L3.45.195a1.466 1.466 0 0 0-.946-.179l11.04 10.973z",
    "M22.018 13.298l-3.919 2.218-3.515-3.493 3.543-3.521 3.891 2.202a1.49 1.49 0 0 1 0 2.594z",
    "M13.544 13.056l-11 10.933c.298.036.612-.016.906-.183l13.324-7.54-3.23-3.21z",
]

# Favicon-backed brands (real app artwork, no vector source available).
PNG_BRANDS = ["hotstar", "primevideo"]


def read_paths(path):
    with open(path, encoding="utf-8") as f:
        return re.findall(r'd="([^"]+)"', f.read())


def vector_symbol(name, color):
    paths = read_paths(os.path.join(BRANDS, name + ".svg"))
    body = "".join('<path fill="%s" d="%s"/>' % (color, d) for d in paths)
    return '<symbol id="b-%s" viewBox="0 0 24 24">%s</symbol>' % (name, body)


def play_symbol():
    body = "".join('<path fill="%s" d="%s"/>' % (c, d) for c, d in zip(PLAY_COLORS, PLAY_PATHS))
    return '<symbol id="b-googleplay" viewBox="0 0 24 24">%s</symbol>' % body


def png_symbol(name):
    path = os.path.join(BRANDS, name + ".png")
    with Image.open(path) as im:
        width, height = im.size
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return ('<symbol id="b-%s" viewBox="0 0 %d %d">'
            '<image width="%d" height="%d" preserveAspectRatio="xMidYMid meet" '
            'href="data:image/png;base64,%s"/></symbol>'
            % (name, width, height, width, height, b64))


def main():
    symbols = [vector_symbol(name, color) for name, color in COLORS.items()]
    symbols.append(play_symbol())
    symbols.extend(png_symbol(name) for name in PNG_BRANDS)

    sprite = ('<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">'
              + "".join(symbols) + "</svg>\n")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(sprite)
    print("brands: %d symbols, %d bytes -> %s"
          % (len(symbols), len(sprite.encode("utf-8")), OUT))


if __name__ == "__main__":
    main()
