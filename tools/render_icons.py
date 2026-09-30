#!/usr/bin/env python3
"""Renders the bitmap assets the Fire TV launcher and notification service need.

Fire OS decodes the launcher banner, the app icon and the notification icon as bitmaps
(vector drawables fail with "getBitmapFromPackage: failed to decode bitmap"), so they are
PNGs. Drawn here from the same shapes as res/drawable/hero.xml, 4x supersampled.

    python3 tools/render_icons.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

RES = Path(__file__).resolve().parent.parent / "receiver/src/main/res"
BG_TOP = (10, 16, 32)
BG_BOTTOM = (16, 26, 51)
GLOW = (47, 107, 255)
ACCENT = (125, 184, 255)
WHITE = (244, 247, 252)
SS = 4


def background(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse((w * 0.45, h * 0.05, w * 1.1, h * 0.95), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(radius=w / 8))
    img.paste(Image.new("RGB", (w, h), GLOW), (0, 0), glow)
    return img


def hero(draw, ox, oy, s, stroke):
    """Phone next to a TV, coordinates from hero.xml (viewport 360x260) scaled by s."""
    def r(x0, y0, x1, y1):
        return (ox + x0 * s, oy + y0 * s, ox + x1 * s, oy + y1 * s)
    w = max(1, round(stroke * s))
    draw.rounded_rectangle(r(86, 20, 350, 186), radius=10 * s, outline=ACCENT, width=w, fill=(28, 44, 84))
    draw.rounded_rectangle(r(190, 40, 246, 166), radius=8 * s, fill=(49, 76, 130))
    draw.line(r(218, 186, 218, 214), fill=ACCENT, width=w)
    draw.line(r(180, 214, 256, 214), fill=ACCENT, width=w)
    draw.rounded_rectangle(r(12, 96, 96, 248), radius=12 * s, outline=WHITE, width=w, fill=BG_BOTTOM)
    draw.rounded_rectangle(r(44, 104, 64, 110), radius=3 * s, fill=WHITE)
    draw.arc(r(104, 60, 172, 128), start=180, end=270, fill=WHITE, width=w)
    draw.arc(r(100, 16, 216, 132), start=180, end=270, fill=(170, 176, 190), width=w)


def banner():
    w, h = 320 * SS, 180 * SS
    img = background(w, h)
    d = ImageDraw.Draw(img)
    s = 0.62 * SS
    hero(d, (w - 360 * s) / 2, (h - 260 * s) / 2, s, 6)
    out = RES / "drawable-xhdpi/banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.resize((320, 180), Image.LANCZOS).save(out, optimize=True)


def launcher(size, folder):
    w = size * SS
    img = background(w, w)
    d = ImageDraw.Draw(img)
    s = w / 360 * 0.82
    hero(d, (w - 360 * s) / 2, (w - 260 * s) / 2, s, 9)
    out = RES / folder / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(out, optimize=True)


def notification(size, folder):
    """White glyph on transparent, as Android expects for small icons."""
    w = size * SS
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = w / 24
    d.rounded_rectangle((2 * u, 4 * u, 22 * u, 17 * u), radius=1.2 * u, outline=(255, 255, 255, 255), width=round(2 * u))
    d.rectangle((9 * u, 17 * u, 15 * u, 19 * u), fill=(255, 255, 255, 255))
    d.rectangle((7 * u, 19 * u, 17 * u, 20.4 * u), fill=(255, 255, 255, 255))
    out = RES / folder / "ic_notification.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(out, optimize=True)


if __name__ == "__main__":
    banner()
    for size, folder in [(48, "mipmap-mdpi"), (96, "mipmap-xhdpi"), (144, "mipmap-xxhdpi")]:
        launcher(size, folder)
    for size, folder in [(24, "drawable-mdpi"), (48, "drawable-xhdpi"), (72, "drawable-xxhdpi")]:
        notification(size, folder)
    print("written to", RES)
