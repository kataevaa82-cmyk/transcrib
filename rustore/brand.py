"""Shared brand drawing for RuStore assets: the app icon (vector + raster) and the Onest wordmark.

Geometry comes from app/src/main/res/drawable/ic_launcher_{background,foreground}.xml: a 108-unit canvas
whose central 72 units are visible.
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(ROOT, "..", "app", "src", "main", "res", "font", "onest.ttf")

CORAL = "#FF6A45"
CORAL_DEEP = "#E4432A"
ORANGE = "#FF9B3D"
GRAPHITE = "#15181E"
GRAPHITE_900 = "#0F1115"
GRAPHITE_700 = "#1D2129"

BUBBLE_D = ("M38,31H70A12,12 0,0 1,82 43V59A12,12 0,0 1,70 71H49L39.2,79.4C38.1,80.3 36.6,79.3 37,78"
            "L38.6,71H38A12,12 0,0 1,26 59V43A12,12 0,0 1,38 31Z")
BARS_D = ("M33.5,48.25a1.75,1.75 0,0 1,3.5 0v5.5a1.75,1.75 0,0 1,-3.5 0z "
          "M39,43.25a1.75,1.75 0,0 1,3.5 0v15.5a1.75,1.75 0,0 1,-3.5 0z "
          "M44.5,39.75a1.75,1.75 0,0 1,3.5 0v22.5a1.75,1.75 0,0 1,-3.5 0z "
          "M50,46.25a1.75,1.75 0,0 1,3.5 0v9.5a1.75,1.75 0,0 1,-3.5 0z")
LINES_D = ("M58.75,43.5h13.5a1.75,1.75 0,0 1,0 3.5h-13.5a1.75,1.75 0,0 1,0 -3.5z "
           "M58.75,49.25h8.5a1.75,1.75 0,0 1,0 3.5h-8.5a1.75,1.75 0,0 1,0 -3.5z "
           "M58.75,55h11.5a1.75,1.75 0,0 1,0 3.5h-11.5a1.75,1.75 0,0 1,0 -3.5z")

# Corner radius of the rounded-square logo, in icon units (of the 72-unit visible area).
ROUND_RX = 16


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ---------------------------------------------------------------- vector

def svg_icon_body(rounded, mid=""):
    """SVG elements for the icon in 108-unit coordinates; ids get the `mid` suffix so several icons can share a file."""
    clip = f' clip-path="url(#clip{mid})"' if rounded else ""
    return f"""<defs>
  <linearGradient id="bg{mid}" gradientUnits="userSpaceOnUse" x1="10" y1="0" x2="98" y2="108">
    <stop offset="0" stop-color="{ORANGE}"/><stop offset=".5" stop-color="{CORAL}"/><stop offset="1" stop-color="{CORAL_DEEP}"/>
  </linearGradient>
  <radialGradient id="light{mid}" gradientUnits="userSpaceOnUse" cx="30" cy="22" r="70">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity=".22"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>
  <linearGradient id="wave{mid}" gradientUnits="userSpaceOnUse" x1="33" y1="64" x2="54" y2="38">
    <stop offset="0" stop-color="{CORAL_DEEP}"/><stop offset="1" stop-color="{ORANGE}"/>
  </linearGradient>
  <filter id="blur{mid}" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation=".75"/></filter>
  <clipPath id="clip{mid}"><rect x="18" y="18" width="72" height="72" rx="{ROUND_RX}"/></clipPath>
</defs>
<g{clip}>
  <rect width="108" height="108" fill="url(#bg{mid})"/>
  <rect width="108" height="108" fill="url(#light{mid})"/>
  <path transform="translate(0 2.2)" fill="#7A1E0A" fill-opacity=".28" filter="url(#blur{mid})" d="{BUBBLE_D}"/>
  <path fill="#FFFFFF" d="{BUBBLE_D}"/>
  <path fill="url(#wave{mid})" d="{BARS_D}"/>
  <path fill="{GRAPHITE}" d="{LINES_D}"/>
</g>"""


def svg_icon(size, rounded):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">\n'
            f"{svg_icon_body(rounded)}\n</svg>\n")


def wordmark_path(text, weight, size_px):
    """Outlines of `text` in Onest at the given weight: (svg path d, advance width, ascent) in px."""
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont

    font = instantiateVariableFont(TTFont(FONT), {"wght": weight})
    upm = font["head"].unitsPerEm
    scale = size_px / upm
    cmap = font.getBestCmap()
    gs = font.getGlyphSet()
    hmtx = font["hmtx"]
    pen = SVGPathPen(gs)
    x = 0.0
    for ch in text:
        g = cmap[ord(ch)]
        # font units, y up -> px, y down; baseline at y=0
        gs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
        x += hmtx[g][0] * scale
    ascent = font["OS/2"].sCapHeight * scale
    return pen.getCommands(), x, ascent


# ---------------------------------------------------------------- raster

def _grid(n):
    """Pixel centres of an n x n canvas in icon units (visible area 18..90)."""
    c = (np.arange(n) + 0.5) / n * 72 + 18
    return np.meshgrid(c, c)


def icon_background(n):
    x, y = _grid(n)
    sx, sy, ex, ey = 10, 0, 98, 108
    dx, dy = ex - sx, ey - sy
    t = np.clip(((x - sx) * dx + (y - sy) * dy) / (dx * dx + dy * dy), 0, 1)[..., None]
    c0, c1, c2 = (np.array(hexrgb(h), float) for h in (ORANGE, CORAL, CORAL_DEEP))
    col = np.where(t < 0.5, c0 + (c1 - c0) * (t / 0.5), c1 + (c2 - c1) * ((t - 0.5) / 0.5))
    a = (np.clip(1 - np.hypot(x - 30, y - 22) / 70, 0, 1) * 0.22)[..., None]
    col = col + (255 - col) * a
    return Image.fromarray(col.clip(0, 255).astype(np.uint8), "RGB")


def _bubble_polygon(u):
    pts = []

    def arc(cx, cy, r, a0, a1, steps=32):
        for i in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * i / steps)
            pts.append((u(cx + r * math.cos(a)), u(cy + r * math.sin(a))))

    arc(70, 43, 12, -90, 0)
    arc(70, 59, 12, 0, 90)
    pts.extend([(u(49), u(71)), (u(39.2), u(79.4)), (u(37.4), u(79.2)), (u(37), u(78)), (u(38.6), u(71))])
    arc(38, 59, 12, 90, 180)
    arc(38, 43, 12, 180, 270)
    return pts


def render_icon(size, rounded=False, ss=4):
    """The launcher icon as an RGBA image; `rounded` gives a rounded square with transparent corners."""
    n = size * ss

    def u(v):
        return (v - 18) / 72 * n

    img = icon_background(n).convert("RGBA")
    bub = _bubble_polygon(u)

    shadow = Image.new("L", (n, n), 0)
    ImageDraw.Draw(shadow).polygon([(x, y + u(2.2) - u(0)) for x, y in bub], fill=int(255 * 0.28))
    shadow = shadow.filter(ImageFilter.GaussianBlur(u(0.75) - u(0) + 1))
    img.paste(hexrgb("7A1E0A"), (0, 0), shadow)

    d = ImageDraw.Draw(img)
    d.polygon(bub, fill=(255, 255, 255, 255))

    # Bars: gradient along (33,64) -> (54,38), as in the vector drawable.
    x, y = np.meshgrid(np.arange(n) + 0.5, np.arange(n) + 0.5)
    gx0, gy0, gx1, gy1 = u(33), u(64), u(54), u(38)
    gdx, gdy = gx1 - gx0, gy1 - gy0
    t = np.clip(((x - gx0) * gdx + (y - gy0) * gdy) / (gdx * gdx + gdy * gdy), 0, 1)[..., None]
    c0, c1 = np.array(hexrgb(CORAL_DEEP), float), np.array(hexrgb(ORANGE), float)
    grad = Image.fromarray((c0 + (c1 - c0) * t).astype(np.uint8), "RGB")
    mask = Image.new("L", (n, n), 0)
    md = ImageDraw.Draw(mask)
    for x0, y0, h in [(33.5, 46.5, 9), (39, 41.5, 19), (44.5, 38, 26), (50, 44.5, 13)]:
        md.rounded_rectangle([u(x0), u(y0), u(x0 + 3.5), u(y0 + h)], radius=(u(3.5) - u(0)) / 2, fill=255)
    img.paste(grad, (0, 0), mask)

    for y0, w in [(43.5, 17), (49.25, 12), (55, 15)]:
        d.rounded_rectangle([u(57), u(y0), u(57 + w), u(y0 + 3.5)], radius=(u(3.5) - u(0)) / 2, fill=hexrgb(GRAPHITE))

    if rounded:
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, n - 1, n - 1], radius=ROUND_RX / 72 * n, fill=255)
        img.putalpha(m)
    return img.resize((size, size), Image.LANCZOS)


def font(size, weight=700):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([weight])
    return f


def linear_gradient(w, h, stops, angle_deg=135):
    """RGB image with a linear gradient; stops = [(offset, '#hex'), ...]."""
    x, y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    a = math.radians(angle_deg)
    dx, dy = math.sin(a), -math.cos(a)  # css-like: 90deg = to the right, 180deg = down
    proj = (x - w / 2) * dx + (y - h / 2) * dy
    half = (abs(w * dx) + abs(h * dy)) / 2
    t = np.clip((proj + half) / (2 * half), 0, 1)
    out = np.zeros((h, w, 3))
    offs = [s[0] for s in stops]
    cols = [np.array(hexrgb(s[1]), float) for s in stops]
    for ch in range(3):
        out[..., ch] = np.interp(t, offs, [c[ch] for c in cols])
    return Image.fromarray(out.astype(np.uint8), "RGB")


def radial_glow(img, cx, cy, r, color="#FFFFFF", alpha=0.2):
    w, h = img.size
    x, y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    a = (np.clip(1 - np.hypot(x - cx, y - cy) / r, 0, 1) ** 1.5 * alpha)[..., None]
    base = np.asarray(img.convert("RGB"), float)
    c = np.array(hexrgb(color), float)
    return Image.fromarray((base + (c - base) * a).astype(np.uint8), "RGB")
