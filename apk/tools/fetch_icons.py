"""Fetch Lucide icons (ISC) and build an inline SVG sprite for the WebView UI.

Icons are cached in apk/icons/ so the UI can be rebuilt offline.
"""
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(ROOT, "icons")
OUT = os.path.join(ROOT, "web", "sprite.svg")
CDN = "https://unpkg.com/lucide-static@latest/icons/{}.svg"

NAMES = [
    "power", "power-off", "home", "arrow-left", "menu", "settings", "keyboard",
    "gamepad-2", "layout-grid", "chevron-up", "chevron-down", "chevron-left",
    "chevron-right", "volume-1", "volume-2", "volume-x", "tv", "list-video",
    "info", "search", "mic", "play", "pause", "skip-back", "skip-forward",
    "rewind", "fast-forward", "cast", "wifi", "wifi-off", "refresh-cw",
    "loader-circle", "check", "alert-circle", "x", "plus", "subtitles",
    "signal", "shield-check", "smartphone", "scan-line", "trash-2", "log-out",
    "circle-dot", "key-round", "fingerprint", "router", "network", "clock",
    "star", "history", "external-link", "copy", "circle-help", "plug", "type",
    "delete", "corner-down-left", "monitor", "radio", "sliders-horizontal",
    "activity", "arrow-up-right", "chevrons-up", "chevrons-down", "maximize",
    "minimize", "volume-off", "circle-play", "square", "minus", "sun",
    "more-horizontal",
    "pencil",
]


def fetch(name):
    path = os.path.join(CACHE, name + ".svg")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(CDN.format(name), headers={"User-Agent": "xstream-remote-build"})
    with urllib.request.urlopen(req, timeout=25) as r:
        svg = r.read().decode("utf-8")
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)
    return svg


def inner(svg):
    m = re.search(r"<svg[^>]*>(.*)</svg>", svg, re.S)
    if not m:
        raise ValueError("no <svg> body")
    body = m.group(1)
    body = re.sub(r"<!--.*?-->", "", body, flags=re.S)
    return body.strip()


def main():
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">']
    missing = []
    for name in NAMES:
        try:
            svg = fetch(name)
        except Exception as exc:  # noqa: BLE001
            missing.append(name)
            print("skip %-18s %s" % (name, exc), file=sys.stderr)
            continue
        parts.append('<symbol id="i-%s" viewBox="0 0 24 24">%s</symbol>' % (name, inner(svg)))
    parts.append("</svg>")
    sprite = "\n".join(parts)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(sprite)
    print("sprite: %d icons, %d bytes -> %s" % (len(NAMES) - len(missing), len(sprite), OUT))
    if missing:
        print("missing: " + ", ".join(missing))


if __name__ == "__main__":
    main()
