#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
K / X の「∝」字形をパラメトリックに生成する（Kotlin SampleStrokes.loopGlyph と同一の式）。

構成（すべて G1 連続 = 折れ角ゼロ）:

    1. 右上から進入する直線 …… 進行方向 135°（画面座標・y 下向き = 左下がり）
    2. 半径 r の円弧を **時計回りに 270°** …… 直線の接点 T1 から入り、T2 で抜ける
    3. T2 から進行方向 45°（右下がり）の直線で右下へ抜ける

円は進入直線・退出直線の双方に接するので、円弧と直線の継ぎ目に折れ角が生まれない。
進入直線と退出直線だけが 1 点で交差し、そこが「ループの根元」になる。

    局所座標（T1 を原点）
        C  = T1 + r * (cos225, sin225)          … 円の中心（T1 の左上）
        T1 = C + r * (cos45,  sin45 )           … 位置角 45°、接線方向 135°
        T2 = C + r * (cos315, sin315)           … 位置角 315°、接線方向 45°
        進入始点 = T1 + arm * (cos(-45), sin(-45))
        退出終点 = T2 + arm * (cos45,  sin45 )

X は K の左右反転（x -> 1 - x）。反転で回り方も反転するので X のループは反時計回りになる。
"""

from __future__ import annotations

import math
import re
from typing import List, Tuple

from unistroke_model import SRC

Pt = Tuple[float, float]

SQ = math.sqrt(0.5)  # cos45


def loop_glyph(r: float, arm: float, arc_steps: int = 24) -> List[Pt]:
    """時計回りループ付きの「∝」字形を、単位正方形へ収めて返す（K の向き）。"""
    t1 = (0.0, 0.0)
    c = (t1[0] - r * SQ, t1[1] - r * SQ)
    pts: List[Pt] = [(t1[0] + arm * SQ, t1[1] - arm * SQ)]  # 進入始点（右上）
    # 円弧: 位置角 45° -> 315°（時計回り = 画面角の増加方向）に 270°
    for i in range(arc_steps + 1):
        th = math.radians(45.0 + 270.0 * i / arc_steps)
        pts.append((c[0] + r * math.cos(th), c[1] + r * math.sin(th)))
    t2 = pts[-1]
    pts.append((t2[0] + arm * SQ, t2[1] + arm * SQ))  # 退出終点（右下）
    return normalize(pts)


def normalize(pts: List[Pt]) -> List[Pt]:
    """縦横比を保ったまま [0,1]^2 へ収め、中央へ寄せる。"""
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w = max(xs) - min(xs)
    h = max(ys) - min(ys)
    s = 1.0 / max(w, h)
    ox = (1.0 - w * s) / 2.0
    oy = (1.0 - h * s) / 2.0
    return [((p[0] - min(xs)) * s + ox, (p[1] - min(ys)) * s + oy) for p in pts]


def mirror(pts: List[Pt]) -> List[Pt]:
    return [(1.0 - p[0], p[1]) for p in pts]


def kotlin_const(src: str, name: str) -> float:
    m = re.search(r"const\s+val\s+" + name + r"\s*(?::\s*\w+\s*)?=\s*([-\d.]+)f", src)
    if not m:
        raise KeyError(name)
    return float(m.group(1))


def sample_params() -> Tuple[float, float]:
    """SampleStrokes.kt が使っている見本用パラメータ。"""
    s = SRC["SampleStrokes"]
    return kotlin_const(s, "SAMPLE_LOOP_R"), kotlin_const(s, "SAMPLE_ARM")


def sample_steps() -> int:
    """SampleStrokes.kt の円弧分割数。"""
    m = re.search(r"const\s+val\s+SAMPLE_ARC_STEPS\s*(?::\s*Int\s*)?=\s*(\d+)", SRC["SampleStrokes"])
    return int(m.group(1))


def turn_sequence(pts: List[Pt]) -> List[float]:
    """連続する線分の向きの変化（度、正 = 画面上で時計回り）。"""
    out = []
    for i in range(1, len(pts) - 1):
        a = math.atan2(pts[i][1] - pts[i - 1][1], pts[i][0] - pts[i - 1][0])
        b = math.atan2(pts[i + 1][1] - pts[i][1], pts[i + 1][0] - pts[i][0])
        d = math.degrees(b - a)
        while d > 180:
            d -= 360
        while d < -180:
            d += 360
        out.append(d)
    return out


def total_turn(pts: List[Pt]) -> float:
    return sum(turn_sequence(pts))


def max_kink(pts: List[Pt]) -> float:
    """最大の折れ角（度）。滑らかな曲線なら小さい。"""
    return max(abs(d) for d in turn_sequence(pts))


def loop_orientation(pts: List[Pt]) -> str:
    """正味の回転量から回り方を判定（画面座標・y 下向き）。"""
    t = total_turn(pts)
    return "cw" if t > 0 else "ccw"
