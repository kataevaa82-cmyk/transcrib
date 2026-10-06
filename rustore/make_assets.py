"""Builds the RuStore graphics: store icon, logo set (SVG + PNG) and the 16:9 promo image.

    python rustore/make_assets.py <out_dir>

    python rustore/make_assets.py <out_dir> --screens

The second form frames phone screenshots from rustore/screens_raw/ (see screens.txt there) into store-ready
1080x1920 JPGs with a caption on top.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

import brand as b

ROOT = os.path.dirname(os.path.abspath(__file__))


def save_jpg_under(img, path, limit=3 * 1024 * 1024):
    for q in (95, 92, 90, 88, 85, 80):
        img.convert("RGB").save(path, "JPEG", quality=q, optimize=True, progressive=True, subsampling=0)
        if os.path.getsize(path) <= limit:
            return q
    raise RuntimeError(f"{path} does not fit into {limit} bytes")


# ---------------------------------------------------------------- icon + logos

def make_icon(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    b.render_icon(512).convert("RGB").save(os.path.join(out_dir, "icon-512.png"), optimize=True)


def logo_svg(text_fill, h=256):
    """Horizontal logo: rounded icon + Onest Bold wordmark, text converted to outlines."""
    size = 0.40 * h
    d, adv, cap = b.wordmark_path("Транскрибатор", 700, size)
    gap = 0.26 * h
    w = h + gap + adv + 0.02 * h
    baseline = h / 2 + cap / 2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h}" viewBox="0 0 {w:.2f} {h}">\n'
            f'<g transform="scale({h / 72:.6f}) translate(-18 -18)">\n{b.svg_icon_body(True)}\n</g>\n'
            f'<path fill="{text_fill}" transform="translate({h + gap:.2f} {baseline:.2f})" d="{d}"/>\n</svg>\n')


def logo_png(text_rgb, h=256, ss=2):
    H = h * ss
    f = b.font(int(0.40 * H), 700)
    text = "Транскрибатор"
    gap = int(0.26 * H)
    adv = int(f.getlength(text))
    w = H + gap + adv + int(0.02 * H)
    img = Image.new("RGBA", (w, H), (0, 0, 0, 0))
    img.alpha_composite(b.render_icon(H, rounded=True))
    # Cap height of Cyrillic "Т" centred on the icon.
    top = f.getbbox("Т")[1]
    capb = f.getbbox("Т")[3]
    y = H / 2 - (capb - top) / 2 - top
    ImageDraw.Draw(img).text((H + gap, y), text, font=f, fill=text_rgb + (255,))
    return img.resize((w // ss, h), Image.LANCZOS)


def make_logos(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    p = lambda n: os.path.join(out_dir, n)  # noqa: E731

    with open(p("transcrib-icon.svg"), "w", encoding="utf-8") as fh:
        fh.write(b.svg_icon(1024, rounded=False))
    with open(p("transcrib-icon-rounded.svg"), "w", encoding="utf-8") as fh:
        fh.write(b.svg_icon(1024, rounded=True))
    b.render_icon(1024).convert("RGB").save(p("transcrib-icon-1024.png"), optimize=True)
    rounded = b.render_icon(1024, rounded=True)
    rounded.save(p("transcrib-icon-rounded-1024.png"), optimize=True)
    rounded.resize((256, 256), Image.LANCZOS).save(p("transcrib-icon-rounded-256.png"), optimize=True)

    for name, color in (("transcrib-logo", b.GRAPHITE), ("transcrib-logo-white", "#FFFFFF")):
        with open(p(name + ".svg"), "w", encoding="utf-8") as fh:
            fh.write(logo_svg(color))
        logo_png(b.hexrgb(color), h=256).save(p(name + ".png"), optimize=True)


# ---------------------------------------------------------------- promo

def text_gradient(img, xy, text, font, stops, angle=90):
    """Draws text filled with a gradient onto img (RGBA)."""
    bbox = ImageDraw.Draw(img).textbbox(xy, text, font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    grad = b.linear_gradient(max(w, 1), max(h, 1), stops, angle)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=font, fill=255)
    layer = Image.new("RGB", img.size)
    layer.paste(grad, (bbox[0], bbox[1]))
    img.paste(layer, (0, 0), mask)


def wrap(text, font, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if font.getlength(t) <= width or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def make_promo(out_dir, W=3840, H=2160):
    os.makedirs(out_dir, exist_ok=True)
    s = W / 1920  # layout is designed on a 1920x1080 grid

    def S(v):
        return int(round(v * s))

    img = b.linear_gradient(W, H, [(0, b.GRAPHITE_700), (1, b.GRAPHITE_900)], 160)
    img = b.radial_glow(img, S(1480), S(560), S(820), b.CORAL, 0.22)
    img = b.radial_glow(img, S(120), S(40), S(620), b.ORANGE, 0.10)
    # Keep the canvas RGB: ImageDraw only blends translucent fills into RGB images.
    d = ImageDraw.Draw(img, "RGBA")

    # Waveform along the bottom, as the app's WaveDecoration.
    bars, x0, x1, yc, hmax = 64, S(-20), S(1940), S(1000), S(150)
    step = (x1 - x0) / bars
    bw = step * 0.55
    wave = Image.new("L", img.size, 0)
    wd = ImageDraw.Draw(wave)
    for i in range(bars):
        x = i / bars
        env = 0.35 + 0.65 * math.sin(math.pi * x)
        v = 0.15 + 0.85 * env * (0.55 + 0.45 * math.sin(i * 0.55)) * (0.6 + 0.4 * abs(math.sin(i * 1.7)))
        h = hmax * max(0.08, min(v, 1.0))
        bx = x0 + i * step
        wd.rounded_rectangle([bx, yc - h / 2, bx + bw, yc + h / 2], radius=bw / 2, fill=int(255 * 0.16))
    img.paste(b.linear_gradient(W, H, [(0, b.ORANGE), (1, b.CORAL_DEEP)], 90), (0, 0), wave)

    # Left: icon, headline, subtitle, chips.
    icon = b.render_icon(S(168), rounded=True)
    sh = Image.new("L", img.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle([S(140), S(150) + S(14), S(140) + S(168), S(150) + S(168) + S(14)],
                                         radius=S(168) * 16 / 72, fill=110)
    sh = sh.filter(ImageFilter.GaussianBlur(S(22)))
    img.paste((0, 0, 0), (0, 0), sh)
    img.paste(icon, (S(140), S(150)), icon)

    fh = b.font(S(112), 800)
    d.text((S(132), S(372)), "Речь в текст", font=fh, fill=(255, 255, 255))
    text_gradient(img, (S(132), S(500)), "без интернета", fh, [(0, b.ORANGE), (1, b.CORAL)], 90)

    fs = b.font(S(38), 400)
    sub = "Расшифровка аудио и видео прямо на телефоне: пунктуация, разделение по спикерам, экспорт в Word."
    y = S(668)
    for line in wrap(sub, fs, S(780)):
        d.text((S(138), y), line, font=fs, fill=(255, 255, 255, 190))
        y += S(54)

    fc = b.font(S(30), 600)
    cx, cy = S(138), y + S(34)
    for chip in ("Офлайн", "Спикеры", "Word · TXT · SRT"):
        w = fc.getlength(chip) + S(56)
        d.rounded_rectangle([cx, cy, cx + w, cy + S(64)], radius=S(32),
                            fill=(255, 106, 69, 46), outline=(255, 138, 107, 255), width=max(2, S(2)))
        d.text((cx + S(28), cy + S(32)), chip, font=fc, fill=(255, 255, 255), anchor="lm")
        cx += w + S(18)

    # Right: a conversation, as the transcript looks in the app (speaker colours from the dark theme).
    fl = b.font(S(26), 600)
    ft = b.font(S(33), 400)
    msgs = [
        (1090, "#6FA2FF", "Анна · 0:02",
         "Добрый день, коллеги. Сегодня обсуждаем бюджет, сроки запуска и найм сотрудников."),
        (1230, "#FF8A6B", "Павел · 0:12",
         "Предлагаю начать с бюджета — от него зависят остальные решения."),
        (1090, "#6FA2FF", "Анна · 0:24",
         "Когда запускаем новый продукт?"),
    ]
    y = S(168)
    for mx, col, label, text in msgs:
        bw_ = S(600)
        lines = wrap(text, ft, bw_ - S(64))
        bh = S(30 + 34 + 18) + len(lines) * S(46) + S(22)
        x = S(mx)
        shadow = Image.new("L", img.size, 0)
        ImageDraw.Draw(shadow).rounded_rectangle([x, y + S(16), x + bw_, y + bh + S(16)], radius=S(30), fill=140)
        img.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(S(24))))
        d.rounded_rectangle([x, y, x + bw_, y + bh], radius=S(30), fill=b.hexrgb(b.GRAPHITE) + (255,),
                            outline=(255, 255, 255, 26), width=max(2, S(1.5)))
        d.rounded_rectangle([x, y + S(24), x + S(6), y + bh - S(24)], radius=S(3), fill=b.hexrgb(col) + (255,))
        d.text((x + S(32), y + S(30)), label, font=fl, fill=b.hexrgb(col) + (255,))
        ty = y + S(30 + 34 + 12)
        for ln in lines:
            d.text((x + S(32), ty), ln, font=ft, fill=(236, 238, 242, 255))
            ty += S(46)
        y += bh + S(34)

    base = os.path.join(out_dir, f"promo-{W}x{H}.jpg")
    q = save_jpg_under(img, base)
    return base, q


# ---------------------------------------------------------------- screenshots (optional)

def frame_screens(raw_dir, out_dir, W=1080, H=1920):
    """Frames phone screenshots listed in raw_dir/screens.txt: `file | crop_top | crop_bottom | caption`.

    crop_top/crop_bottom are pixel rows that cut off the Android status and navigation bars
    (RuStore does not allow system UI on screenshots). Saves framed JPGs and the plain crops.
    """
    with open(os.path.join(raw_dir, "screens.txt"), encoding="utf-8") as fh:
        rows = [[c.strip() for c in ln.split("|")] for ln in fh if ln.strip() and not ln.startswith("#")]
    os.makedirs(os.path.join(out_dir, "no-frame"), exist_ok=True)
    fcap = b.font(66, 700)
    for i, (name, top_px, bottom_px, cap) in enumerate(rows):
        shot = Image.open(os.path.join(raw_dir, name)).convert("RGB")
        shot = shot.crop((0, int(top_px), shot.width, int(bottom_px)))
        plain = os.path.join(out_dir, "no-frame", f"screen-{i + 1:02d}.png")
        shot.save(plain, optimize=True)

        bg = b.linear_gradient(W, H, [(0, b.ORANGE), (0.5, b.CORAL), (1, b.CORAL_DEEP)], 160)
        bg = b.radial_glow(bg, W * 0.15, 0, W * 0.9, "#FFFFFF", 0.16).convert("RGBA")
        d = ImageDraw.Draw(bg, "RGBA")
        y = 120
        for ln in wrap(cap, fcap, W - 150):
            d.text((W / 2, y), ln, font=fcap, fill=(255, 255, 255), anchor="ma")
            y += 84
        top = y + 56
        k = min(860 / shot.width, (H - top - 90) / shot.height)
        sw, sh_ = int(shot.width * k), int(shot.height * k)
        shot = shot.resize((sw, sh_), Image.LANCZOS)
        x = (W - sw) // 2
        r = 44
        shadow = Image.new("L", bg.size, 0)
        ImageDraw.Draw(shadow).rounded_rectangle([x, top + 24, x + sw, top + sh_ + 24], radius=r, fill=130)
        bg.paste((110, 25, 5), (0, 0), shadow.filter(ImageFilter.GaussianBlur(34)))
        m = Image.new("L", shot.size, 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, sw - 1, sh_ - 1], radius=r, fill=255)
        bg.paste(shot, (x, top), m)
        out = os.path.join(out_dir, f"screen-{i + 1:02d}.jpg")
        save_jpg_under(bg, out)
        print("framed", out, shot.size)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else os.path.join(ROOT, "build")
    if "--screens" in sys.argv:
        frame_screens(os.path.join(ROOT, "screens_raw"), os.path.join(out, "04_screenshots"))
        sys.exit(0)
    make_icon(os.path.join(out, "02_icon"))
    make_logos(os.path.join(out, "03_logos"))
    print(make_promo(os.path.join(out, "05_promo")))
    print(make_promo(os.path.join(out, "05_promo"), 1920, 1080))
