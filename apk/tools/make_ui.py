"""Inline the Lucide sprite into the WebView page.

Android WebView cannot <use> symbols from an external file:// document, so the
sprite has to live in the same HTML. Source stays editable in web/.
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TEMPLATE = os.path.join(ROOT, "web", "index.template.html")
SPRITE = os.path.join(ROOT, "web", "sprite.svg")
OUT = os.path.join(ROOT, "assets", "ui", "index.html")

PLACEHOLDER = "<!--ICONS-->"


def main():
    with open(TEMPLATE, encoding="utf-8") as f:
        html = f.read()
    with open(SPRITE, encoding="utf-8") as f:
        sprite = f.read()

    if PLACEHOLDER not in html:
        raise SystemExit("placeholder %s missing from template" % PLACEHOLDER)

    html = html.replace(PLACEHOLDER, sprite.strip())
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(html)
    print("ui: %d bytes -> %s" % (len(html.encode("utf-8")), OUT))


if __name__ == "__main__":
    main()
