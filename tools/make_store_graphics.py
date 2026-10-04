#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Google Play 掲載用のアイコン（512x512）とフィーチャー画像（1024x500）を作る。

    python3 tools/make_store_graphics.py

図柄はアプリのランチャーアイコン（res/drawable/ic_launcher_*.xml）と同じ式で描く。
アイコンを変えたら、ここの座標も合わせること。Pillow と macOS のヒラギノが要る。
"""

import glob
import os
import unicodedata

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "store-assets")

BEZEL = (0x23, 0x2B, 0x25)
ZONE = (0x3A, 0x4A, 0x3D)
INK = (0x7D, 0xF7, 0xA0)
TEXT = (0xD3, 0xE9, 0xD6)
HINT = (0x8F, 0xA8, 0x94)
SS = 4  # 縁をなめらかにするための拡大率


def cubic(p0, p1, p2, p3, n=48):
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        yield (u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
               u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1])


def u_glyph():
    """ic_launcher_foreground.xml の "U"（108x108 の座標系）。"""
    pts = [(38, 32), (38, 58)]
    pts += list(cubic((38, 58), (38, 70), (45, 77), (54, 77)))
    pts += list(cubic((54, 77), (63, 77), (70, 70), (70, 58)))
    pts += [(70, 32)]
    return pts


def draw_glyph(draw, ox, oy, scale):
    """108 座標系の図柄を (ox, oy) から scale 倍で描く。"""
    def tr(p):
        return (ox + p[0] * scale, oy + p[1] * scale)

    pts = [tr(p) for p in u_glyph()]
    w = 7 * scale
    # 線は「細かく刻んだ点に円を押していく」形で描く。ImageDraw.line の継ぎ目は
    # 曲線で筋が出るため。端も自然に丸くなる（strokeLineCap="round" と同じ見た目）。
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps = max(1, int(max(abs(x1 - x0), abs(y1 - y0)) / (w / 8)))
        for i in range(steps + 1):
            x = x0 + (x1 - x0) * i / steps
            y = y0 + (y1 - y0) * i / steps
            draw.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=INK)
    r = 6 * scale  # 書き始めの ●
    draw.ellipse((pts[0][0] - r, pts[0][1] - r, pts[0][0] + r, pts[0][1] + r), fill=INK)


def font(weight, size):
    for path in glob.glob("/System/Library/Fonts/*.ttc"):
        name = unicodedata.normalize("NFC", os.path.basename(path))
        if name == "ヒラギノ角ゴシック W%d.ttc" % weight:
            return ImageFont.truetype(path, size)
    raise SystemExit("ヒラギノ角ゴシック W%d が見つからない" % weight)


def make_icon():
    size = 512 * SS
    img = Image.new("RGB", (size, size), BEZEL)
    d = ImageDraw.Draw(img)
    # 108 座標系のうち 12..96 を全面に使う（縁に筐体色を少し残す）
    lo, span = 12, 84
    scale = size / span
    off = -lo * scale
    d.rectangle((off + 18 * scale, off + 18 * scale, off + 90 * scale, off + 90 * scale), fill=ZONE)
    draw_glyph(d, off, off, scale)
    img = img.resize((512, 512), Image.LANCZOS)
    path = os.path.join(OUT, "play-icon-512.png")
    img.save(path)
    return path


def make_feature():
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), BEZEL)
    d = ImageDraw.Draw(img)
    # 左に手書きゾーンと "U" の一筆書き
    pad = 60 * SS
    side = h - 2 * pad
    zone = (pad, pad, pad + side, h - pad)
    d.rounded_rectangle(zone, radius=28 * SS, fill=ZONE)
    scale = side / 108
    draw_glyph(d, zone[0], zone[1] - 0.5 * scale, scale)
    # 右に名前と説明（右端に余白を残す。Play は端を切り落とすことがある）
    x = zone[2] + 50 * SS
    d.text((x, 122 * SS), "Uni-Stroke IME", font=font(7, 58 * SS), fill=TEXT)
    d.text((x, 212 * SS), "一筆書きの日本語入力", font=font(6, 42 * SS), fill=INK)
    d.text((x, 296 * SS), "変換も予測も端末内で完結", font=font(4, 27 * SS), fill=HINT)
    d.text((x, 338 * SS), "入力した内容を外へ送りません", font=font(4, 27 * SS), fill=HINT)
    img = img.resize((1024, 500), Image.LANCZOS)
    path = os.path.join(OUT, "feature-graphic-1024x500.png")
    img.save(path)
    return path


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for p in (make_icon(), make_feature()):
        print(os.path.relpath(p, ROOT), Image.open(p).size)
