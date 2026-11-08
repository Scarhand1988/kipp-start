"""Zeichnet die App-Symbole der iPhone-Web-App (wie wallpaper_icon.xml der Android-App).
Aufruf: python3 web/tools/icons.py <Zielordner>   (läuft im Workflow pages.yml)"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

BACKGROUND = (0x17, 0x27, 0x2E)
MINT = (0xA5, 0xDA, 0xD2)
SUN = (0xEF, 0xD7, 0xB4)


def icon(size, path):
    s = 8  # Kantenglättung durch Überabtastung
    n = size * s
    k = n / 96.0
    img = Image.new('RGB', (n, n), BACKGROUND)
    d = ImageDraw.Draw(img)
    p = lambda x, y: (x * k, y * k)
    d.rounded_rectangle([p(21, 27), p(75, 70)], radius=5 * k, outline=MINT, width=round(3 * k))
    d.polygon([p(25, 64), p(41, 42), p(50, 52), p(59, 41), p(73, 64)], fill=MINT)
    d.ellipse([p(55, 30), p(65, 40)], fill=SUN)
    img.resize((size, size), Image.LANCZOS).save(path, optimize=True)


if __name__ == '__main__':
    target = Path(sys.argv[1] if len(sys.argv) > 1 else 'icons')
    target.mkdir(parents=True, exist_ok=True)
    for size in (180, 192, 512):
        icon(size, target / f'icon-{size}.png')
    print('Symbole:', ', '.join(str(p) for p in sorted(target.glob('*.png'))))
