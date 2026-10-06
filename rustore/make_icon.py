"""Renders the RuStore store icon (512x512 PNG) from the same shapes as the adaptive launcher icon.

The launcher icon uses a 108x108 canvas with a 72x72 visible area; here the 108 canvas is cropped to the
central 72 units and scaled to 512 px, supersampled 4x for smooth edges.
"""
import math
from PIL import Image, ImageDraw, ImageFilter

OUT = 512
SS = 4
N = OUT * SS
VIEW0, VIEW = 18.0, 72.0  # visible part of the 108-unit canvas


def u(v):
    """icon units -> supersampled pixels"""
    return (v - VIEW0) / VIEW * N


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# Background: diagonal 3-stop gradient + soft light top-left.
bg = Image.new("RGB", (N, N))
px = bg.load()
c0, c1, c2 = hexrgb("FF9B3D"), hexrgb("FF6A45"), hexrgb("E4432A")
sx, sy, ex, ey = u(10), u(0), u(98), u(108)
dx, dy = ex - sx, ey - sy
dd = dx * dx + dy * dy
lx, ly, lr = u(30), u(22), u(70) - u(0)
for y in range(N):
    for x in range(N):
        t = max(0.0, min(1.0, ((x - sx) * dx + (y - sy) * dy) / dd))
        c = lerp(c0, c1, t / 0.5) if t < 0.5 else lerp(c1, c2, (t - 0.5) / 0.5)
        d = math.hypot(x - lx, y - ly) / lr
        a = max(0.0, 1 - d) * 0.22
        px[x, y] = tuple(int(c[i] + (255 - c[i]) * a) for i in range(3))


def bubble_polygon():
    pts = []

    def arc(cx, cy, r, a0, a1, steps=24):
        for i in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * i / steps)
            pts.append((u(cx + r * math.cos(a)), u(cy + r * math.sin(a))))

    arc(70, 43, 12, -90, 0)       # top-right
    arc(70, 59, 12, 0, 90)        # bottom-right
    pts += [(u(49), u(71)), (u(39.2), u(79.4)), (u(37.4), u(79.2)), (u(37), u(78)), (u(38.6), u(71))]
    arc(38, 59, 12, 90, 180)      # bottom-left
    arc(38, 43, 12, 180, 270)     # top-left
    return pts


bub = bubble_polygon()

# Shadow
shadow = Image.new("L", (N, N), 0)
ImageDraw.Draw(shadow).polygon([(x, y + u(2.2) - u(0)) for x, y in bub], fill=int(255 * 0.28))
shadow = shadow.filter(ImageFilter.GaussianBlur(SS * 3))
bg.paste(hexrgb("7A1E0A"), (0, 0), shadow)

img = bg.convert("RGBA")
d = ImageDraw.Draw(img)
d.polygon(bub, fill=(255, 255, 255, 255))


def capsule(x0, y0, w, h, fill):
    d.rounded_rectangle([u(x0), u(y0), u(x0 + w), u(y0 + h)], radius=min(u(x0 + w) - u(x0), u(y0 + h) - u(y0)) / 2,
                        fill=fill)


# Sound bars with a vertical coral gradient.
bars = [(33.5, 46.5, 9), (39, 41.5, 19), (44.5, 38, 26), (50, 44.5, 13)]
grad = Image.new("RGB", (N, N))
gp = ImageDraw.Draw(grad)
for y in range(N):
    t = max(0.0, min(1.0, (y - u(38)) / (u(64) - u(38))))
    gp.line([(0, y), (N, y)], fill=lerp(hexrgb("FF9B3D"), hexrgb("E4432A"), t))
mask = Image.new("L", (N, N), 0)
md = ImageDraw.Draw(mask)
for x0, y0, h in bars:
    md.rounded_rectangle([u(x0), u(y0), u(x0 + 3.5), u(y0 + h)], radius=(u(x0 + 3.5) - u(x0)) / 2, fill=255)
img.paste(grad, (0, 0), mask)

# Text lines
for y0, w in [(43.5, 17), (49.25, 12), (55, 15)]:
    capsule(57, y0, w, 3.5, (21, 24, 30, 255))

img = img.resize((OUT, OUT), Image.LANCZOS)
img.convert("RGB").save(r"C:\transcrib\rustore\icon-512.png")
print("saved")
