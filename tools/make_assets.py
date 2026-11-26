#!/usr/bin/env python3
"""Membuat ikon PNG (Android lama), ikon 512px, dan GIF animasi untuk README.

Geometri sama persis dengan res/drawable/ic_launcher_foreground.xml (grid 108).
Jalankan:  python3 tools/make_assets.py
"""
import glob
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs", "assets")

C1 = (0x5B, 0x3F, 0xD9)  # ungu
C2 = (0x14, 0xB8, 0xD4)  # cyan
SS = 4  # supersampling


def gradient(size, c1=C1, c2=C2):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    t = (xs + ys) / (2.0 * (size - 1))
    arr = np.zeros((size, size, 4), np.uint8)
    for i in range(3):
        arr[..., i] = (c1[i] + (c2[i] - c1[i]) * t).astype(np.uint8)
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def draw_glyph(img, scale=1.0, arc_alpha=(230, 153), glow=0.0):
    """Gambar L + simpul + dua busur di atas img (grid 108 dipetakan ke ukuran img)."""
    size = img.size[0]
    k = size / 108.0

    def P(x, y):
        return (size / 2 + (x - 54) * scale * k, size / 2 + (y - 54) * scale * k)

    d = ImageDraw.Draw(img, "RGBA")
    w = 9 * scale * k
    pts = [P(34, 28), P(34, 72), P(58, 72)]
    d.line(pts, fill=(255, 255, 255, 255), width=int(w), joint="curve")
    for x, y in (pts[0], pts[2]):  # ujung bulat
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=(255, 255, 255, 255))
    cx, cy = P(58, 72)
    r = 6.5 * scale * k
    if glow > 0:
        # ImageDraw pada RGBA menimpa piksel (tidak blending), jadi gunakan mask + paste.
        g = r * (1 + 2.2 * glow)
        gm = Image.new("L", img.size, 0)
        ImageDraw.Draw(gm).ellipse((cx - g, cy - g, cx + g, cy + g), fill=int(90 * (1 - glow)))
        img.paste(Image.new("RGBA", img.size, (255, 255, 255, 255)), (0, 0), gm)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 255))
    for rad, a in ((12, arc_alpha[0]), (22, arc_alpha[1])):
        rr = rad * scale * k          # jari-jari garis tengah busur
        bw = max(1, int(5 * scale * k))
        # Gambar busur + ujung bulat pada mask opak, baru diberi transparansi sekali
        # (menghindari bercak akibat tumpang tindih alfa).
        m = Image.new("L", img.size, 0)
        md = ImageDraw.Draw(m)
        outer = rr + bw / 2           # PIL menggambar lebar busur ke arah dalam dari bbox
        md.arc((cx - outer, cy - outer, cx + outer, cy + outer), start=-90, end=0, fill=255, width=bw)
        for ang in (-90, 0):
            ex = cx + rr * math.cos(math.radians(ang))
            ey = cy + rr * math.sin(math.radians(ang))
            md.ellipse((ex - bw / 2, ey - bw / 2, ex + bw / 2, ey + bw / 2), fill=255)
        m = m.point(lambda v, a=a: int(v * a / 255))
        img.paste(Image.new("RGBA", img.size, (255, 255, 255, 255)), (0, 0), m)
    return img


def icon(size, shape="square", scale=1.3, arc_alpha=(230, 153), glow=0.0):
    big = size * SS
    bg = gradient(big)
    draw_glyph(bg, scale=scale, arc_alpha=arc_alpha, glow=glow)
    mask = Image.new("L", (big, big), 0)
    md = ImageDraw.Draw(mask)
    if shape == "round":
        md.ellipse((0, 0, big - 1, big - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def write_mipmaps():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, s in sizes.items():
        folder = os.path.join(RES, f"mipmap-{d}")
        os.makedirs(folder, exist_ok=True)
        icon(s, "square").save(os.path.join(folder, "ic_launcher.png"))
        icon(s, "round").save(os.path.join(folder, "ic_launcher_round.png"))
    os.makedirs(DOCS, exist_ok=True)
    icon(512, "square").save(os.path.join(DOCS, "icon.png"))


def font(size, bold=True):
    pats = [
        "/usr/share/fonts/opentype/inter/Inter-Bold.otf" if bold else "/usr/share/fonts/opentype/inter/Inter-Regular.otf",
        "/usr/share/fonts/**/Inter*Bold*" if bold else "/usr/share/fonts/**/Inter*Regular*",
        "/usr/share/fonts/**/OpenSans*",
        "/usr/share/fonts/**/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/**/DejaVuSans.ttf",
    ]
    for p in pats:
        for f in glob.glob(p, recursive=True):
            try:
                return ImageFont.truetype(f, size)
            except Exception:
                pass
    return ImageFont.load_default()


def banner_frames(n=36, W=720, H=240):
    # latar gelap bergradien (dibuat sekali)
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    t = (xs / W * 0.6 + ys / H * 0.4)
    a = np.array([13, 10, 38], np.float32)
    b = np.array([27, 20, 78], np.float32)
    arr = np.zeros((H, W, 3), np.uint8)
    for i in range(3):
        arr[..., i] = (a[i] + (b[i] - a[i]) * t).astype(np.uint8)
    base = Image.fromarray(arr, "RGB").convert("RGBA")
    f_title, f_sub = font(64), font(22, bold=False)
    frames = []
    isz = 168
    ix, iy = 44, (H - isz) // 2
    for i in range(n):
        ph = i / n
        fr = base.copy()
        # cincin sinyal memancar dari ikon
        glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        cx, cy = ix + isz / 2, iy + isz / 2
        for k in range(2):
            p = (ph + k * 0.5) % 1.0
            r = isz * 0.55 + p * 70
            gd.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(20, 184, 212, int(110 * (1 - p))), width=3)
        fr = Image.alpha_composite(fr, glow)
        # busur ikon berdenyut berurutan
        pulse = lambda off: 0.45 + 0.55 * max(0.0, math.sin((ph - off) * 2 * math.pi))
        aa = (int(255 * pulse(0.0)), int(255 * pulse(0.12)))
        ic = icon(isz, "square", scale=1.3, arc_alpha=aa, glow=(ph * 2) % 1.0 * 0.6)
        fr.alpha_composite(ic, (ix, iy))
        d = ImageDraw.Draw(fr)
        tx = ix + isz + 40
        d.text((tx, iy + 8), "Lin Vpn", font=f_title, fill=(255, 255, 255, 255))
        d.text((tx + 2, iy + 92), "SSH  ·  SlowDNS  ·  V2Ray", font=f_sub, fill=(190, 196, 230, 255))
        # paket data bergerak di garis bawah (lapisan terpisah agar transparansi benar-benar blending)
        ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        od = ImageDraw.Draw(ov)
        ly = H - 34
        od.line((tx, ly, W - 40, ly), fill=(255, 255, 255, 40), width=2)
        for k in range(4):
            p = (ph + k / 4.0) % 1.0
            x = tx + p * (W - 40 - tx)
            al = int(255 * math.sin(p * math.pi))
            od.ellipse((x - 5, ly - 5, x + 5, ly + 5), fill=(20, 184, 212, al))
        fr = Image.alpha_composite(fr, ov)
        frames.append(fr.convert("RGB"))
    return frames


def write_gif():
    frames = banner_frames()
    # Satu palet global + dithering supaya gradien tidak berpita dan ukuran file tetap kecil
    sheet = Image.new("RGB", (frames[0].width, frames[0].height * 3))
    for i, y in enumerate((0, 12, 24)):
        sheet.paste(frames[y], (0, i * frames[0].height))
    master = sheet.quantize(colors=200, method=Image.MEDIANCUT)
    pal = [f.quantize(palette=master, dither=Image.FLOYDSTEINBERG) for f in frames]
    os.makedirs(DOCS, exist_ok=True)
    out = os.path.join(DOCS, "lin-vpn.gif")
    pal[0].save(out, save_all=True, append_images=pal[1:], duration=60, loop=0, optimize=True, disposal=1)
    print("GIF:", out, os.path.getsize(out) // 1024, "KB")


def write_banner(W=1600, H=480):
    """Banner statis profesional untuk README."""
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    t = (xs / (W - 1)) * 0.75 + (ys / (H - 1)) * 0.25
    a, b = (0x1E, 0x1B, 0x4B), (0x0E, 0x74, 0x90)
    arr = np.full((H, W, 4), 255, np.uint8)
    for i in range(3):
        arr[..., i] = (a[i] + (b[i] - a[i]) * t).astype(np.uint8)
    bg = Image.fromarray(arr, "RGBA")
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for i, r in enumerate(range(260, 900, 120)):  # lingkaran dekoratif
        d.ellipse((W - 260 - r, H // 2 - r, W - 260 + r, H // 2 + r), outline=(255, 255, 255, 22 - i * 3), width=3)
    bg = Image.alpha_composite(bg, ov)
    ic = icon(280, "round", scale=1.0).convert("RGBA")
    bg.alpha_composite(ic, (150, (H - 280) // 2))
    d = ImageDraw.Draw(bg)
    d.text((470, 130), "Lin Vpn", font=font(130), fill="white")
    d.text((474, 285), "Koneksi aman, cepat, dan stabil.", font=font(44, False), fill=(224, 242, 254, 255))
    d.text((474, 350), "SSH  ·  SlowDNS  ·  V2Ray", font=font(34, False), fill=(165, 214, 232, 255))
    bg.convert("RGB").save(os.path.join(DOCS, "banner.png"), optimize=True)


if __name__ == "__main__":
    write_mipmaps()
    write_gif()
    write_banner()
    print("selesai")
