#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「人が指で書いたストローク」を合成するモデル。

理想の字形（正規化ポリライン）から
  1. 弧長等間隔でのリサンプル（= 一定速度で書いた場合の点列）
  2. 移動平均によるコーナーの丸まり（指はきっちり折れない）
  3. 全体の傾き・縦横のスケールばらつき・平行移動
  4. 各点へのガウスジッター（手ぶれ）
  5. 速書き時のサンプル間引き（MotionEvent が間引かれる状況の再現）
を適用して、認識器へ渡すピクセル座標のストロークを作る。
"""

from __future__ import annotations

import math
import random
from typing import List, Sequence, Tuple

from unistroke_model import path_length, resample

Pt = Tuple[float, float]


def smooth(pts: Sequence[Pt], window: int) -> List[Pt]:
    if window <= 1 or len(pts) < window:
        return list(pts)
    out = []
    half = window // 2
    for i in range(len(pts)):
        lo = max(0, i - half)
        hi = min(len(pts), i + half + 1)
        seg = pts[lo:hi]
        out.append((sum(p[0] for p in seg) / len(seg), sum(p[1] for p in seg) / len(seg)))
    # 端点は動かさない（書き始め・書き終わりの位置は人でもぶれにくい）
    out[0] = tuple(pts[0])
    out[-1] = tuple(pts[-1])
    return out


def _correlated_jitter(n: int, rng: random.Random, amp: float, knots: int = 6):
    """低周波の手ぶれ。各点に独立なノイズを載せるのは非現実的なので、
    少数の制御点でずれを作り、点間は線形補間して滑らかに繋ぐ。

    独立ノイズだと軌跡長が実際の 2〜3 倍に膨らみ、
    「直線らしさ」「曲率」といった軌跡長ベースの統計が完全に壊れる
    （実測: 直線の i でも straightness が 0.15 まで落ちた）。
    実際の指の震えは相関を持つので、こちらのほうが実機に近い。
    """
    knots = max(2, knots)
    kx = [rng.gauss(0, amp) for _ in range(knots)]
    ky = [rng.gauss(0, amp) for _ in range(knots)]
    out = []
    for i in range(n):
        t = i * (knots - 1) / max(1, n - 1)
        j = min(knots - 2, int(t))
        f = t - j
        out.append((kx[j] * (1 - f) + kx[j + 1] * f,
                    ky[j] * (1 - f) + ky[j + 1] * f))
    return out


def human_stroke(
    ideal: Sequence[Pt],
    rng: random.Random,
    size: float = 200.0,
    jitter: float = 0.012,
    rot_deg: float = 8.0,
    scale_var: float = 0.16,
    smooth_window: int = 5,
    samples: int = 90,
) -> List[Pt]:
    """理想字形 [ideal] を人が書いたときのピクセル座標ストロークにする。"""
    n = max(8, samples + rng.randint(-15, 15))
    pts = resample(list(ideal), n, path_length(list(ideal)))
    pts = smooth(pts, smooth_window)

    sx = size * (1.0 + rng.uniform(-scale_var, scale_var))
    sy = size * (1.0 + rng.uniform(-scale_var, scale_var))
    th = math.radians(rng.uniform(-rot_deg, rot_deg))
    cs, sn = math.cos(th), math.sin(th)
    ox = rng.uniform(-30, 30)
    oy = rng.uniform(-30, 30)
    j = jitter * size

    noise = _correlated_jitter(len(pts), rng, j)
    out: List[Pt] = []
    for (x, y), (nx, ny) in zip(pts, noise):
        px = (x - 0.5) * sx
        py = (y - 0.5) * sy
        rx = px * cs - py * sn
        ry = px * sn + py * cs
        out.append((rx + ox + nx, ry + oy + ny))
    return out


def fast_stroke(
    ideal: Sequence[Pt],
    rng: random.Random,
    size: float = 200.0,
    samples: int = 12,
    corner_radius: float = 0.06,
    jitter: float = 0.020,
    rot_deg: float = 12.0,
    scale_var: float = 0.22,
    overshoot: float = 0.0,
) -> List[Pt]:
    """速書きストロークのモデル（サンプル数とコーナーの丸まりを分離したもの）。

    [human_stroke] は移動平均の窓を「点数」で指定するため、サンプル数を減らすと
    同じ窓でも空間的な平滑化の強さが変わってしまい、2 つの要因が混ざる。
    こちらは

      1. 高解像度（200 点）へリサンプル
      2. **弧長に対する割合**で決めた窓で平滑化（= 速く書いたときの角の丸まり）
      3. そこから [samples] 点へ間引き（= MotionEvent のサンプリングレート）

    の順に適用するので、[corner_radius] と [samples] を独立に振れる。
    """
    hi = 200
    pts = resample(list(ideal), hi, path_length(list(ideal)))
    window = max(1, int(corner_radius * hi) | 1)
    pts = smooth(pts, window)

    # 速書きの行き過ぎ（終端が理想より少し伸びる）
    if overshoot > 0.0:
        ax, ay = pts[-1]
        bx, by = pts[-2]
        d = math.hypot(ax - bx, ay - by)
        if d > 0:
            k = overshoot / d * 0.1
            pts.append((ax + (ax - bx) * k, ay + (ay - by) * k))

    # サンプリングレートを落とす（弧長等間隔で samples 点だけ拾う）
    n = max(2, samples + rng.randint(-2, 2))
    pts = resample(pts, n, path_length(pts))

    sx = size * (1.0 + rng.uniform(-scale_var, scale_var))
    sy = size * (1.0 + rng.uniform(-scale_var, scale_var))
    th = math.radians(rng.uniform(-rot_deg, rot_deg))
    cs, sn = math.cos(th), math.sin(th)
    ox = rng.uniform(-30, 30)
    oy = rng.uniform(-30, 30)
    j = jitter * size

    noise = _correlated_jitter(len(pts), rng, j)
    out: List[Pt] = []
    for (x, y), (nx, ny) in zip(pts, noise):
        px = (x - 0.5) * sx
        py = (y - 0.5) * sy
        rx = px * cs - py * sn
        ry = px * sn + py * cs
        out.append((rx + ox + nx, ry + oy + ny))
    return out


def decimate(pts: Sequence[Pt], keep_every: int) -> List[Pt]:
    """速書きで MotionEvent が間引かれた状況（履歴点を捨てた場合）の再現。"""
    if keep_every <= 1:
        return list(pts)
    out = [pts[i] for i in range(0, len(pts), keep_every)]
    if out[-1] != tuple(pts[-1]):
        out.append(tuple(pts[-1]))
    return out
