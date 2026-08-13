#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin ソースから定数・テンプレート・ローマ字表を読み取り、
Python 側に「同じ挙動のモデル」を組み立てる共通モジュール。

Kotlin を書き換えたらこのモジュール経由で全テストが自動的に追随する
（Python 側に字形やテーブルを二重に持たない）。

    from unistroke_model import SRC, Templates, Recognizer, Romaji
"""

from __future__ import annotations

import math
import os
import re
from typing import Dict, List, Optional, Sequence, Set, Tuple

ROOT = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.join(ROOT, "app", "src", "main", "java", "com", "unistroke", "ime")


def read(name: str) -> str:
    with open(os.path.join(PKG, name), encoding="utf-8") as f:
        return f.read()


SRC: Dict[str, str] = {
    n: read(n + ".kt")
    for n in (
        "StrokeTemplates",
        "StrokeRecognizer",
        "RomajiConverter",
        "SampleStrokes",
        "UniStrokeView",
        "UniStrokeIME",
        "GoogleConvertClient",
        "TrainingSession",
    )
}

# --------------------------------------------------------------- Kotlin 字句


def _kotlin_string(src: str, i: int) -> Tuple[str, int]:
    """src[i] == '"' の位置から Kotlin の文字列リテラルを 1 つ読む。"""
    assert src[i] == '"'
    i += 1
    out = []
    while i < len(src):
        c = src[i]
        if c == "\\":
            nxt = src[i + 1]
            out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"', "'": "'"}.get(nxt, nxt))
            i += 2
            continue
        if c == '"':
            return "".join(out), i + 1
        out.append(c)
        i += 1
    raise ValueError("unterminated string literal")


def _match_paren(src: str, i: int) -> int:
    """src[i] == '(' に対応する ')' の位置を返す（文字列リテラルを飛ばす）。"""
    assert src[i] == "("
    depth = 0
    while i < len(src):
        c = src[i]
        if c == '"':
            _, i = _kotlin_string(src, i)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced parentheses")


FLOAT_RE = re.compile(r"[-+]?\d*\.\d+f|[-+]?\d+\.?\d*f")


def consts(src: str) -> Dict[str, str]:
    """`const val NAME = "value"` を集める。"""
    return dict(re.findall(r'const\s+val\s+(\w+)\s*(?::\s*\w+\s*)?=\s*"((?:[^"\\]|\\.)*)"', src))


def float_const(src: str, name: str) -> float:
    m = re.search(r"const\s+val\s+" + name + r"\s*(?::\s*\w+\s*)?=\s*([-\d.]+)f?", src)
    if not m:
        raise KeyError(name)
    return float(m.group(1))


def int_const(src: str, name: str) -> int:
    m = re.search(r"const\s+val\s+" + name + r"\s*(?::\s*\w+\s*)?=\s*(\d+)", src)
    if not m:
        raise KeyError(name)
    return int(m.group(1))


# ----------------------------------------------------------- StrokeTemplates

Pt = Tuple[float, float]


class Templates:
    """StrokeTemplates.kt をそのまま写したもの。"""

    BLOCKS = ("commands", "letters", "digits", "punctuation", "extended")

    def __init__(self, src: str) -> None:
        self.symbols = consts(src)  # SPACE -> "#space" など
        self.blocks: Dict[str, List[Tuple[str, List[Pt]]]] = {b: [] for b in self.BLOCKS}

        starts = []
        for b in self.BLOCKS:
            m = re.search(r"val\s+" + b + r"\s*:\s*List<StrokeTemplate>\s*=\s*listOf\(", src)
            if not m:
                raise KeyError("template block not found: " + b)
            starts.append((m.start(), b))
        starts.sort()

        def block_of(pos: int) -> str:
            cur = starts[0][1]
            for s, b in starts:
                if s <= pos:
                    cur = b
            return cur

        first_block = starts[0][0]
        for m in re.finditer(r"\bt\(", src):
            if m.start() < first_block:
                continue  # `private fun t(...)` の定義そのもの
            open_i = m.end() - 1
            close_i = _match_paren(src, open_i)
            body = src[open_i + 1 : close_i]
            j = 0
            while body[j] in " \t\r\n":
                j += 1
            if body[j] == '"':
                sym, j = _kotlin_string(body, j)
            else:
                k = j
                while body[k].isalnum() or body[k] == "_":
                    k += 1
                name = body[j:k]
                sym = self.symbols[name]
                j = k
            nums = [float(v[:-1]) for v in FLOAT_RE.findall(body[j:])]
            pts = list(zip(nums[0::2], nums[1::2]))
            self.blocks[block_of(m.start())].append((sym, pts))

    # --- Kotlin の templatesFor() と同じ束ね方
    @property
    def alpha_zone(self):
        return self.blocks["letters"] + self.blocks["commands"]

    @property
    def number_zone(self):
        return self.blocks["digits"] + self.blocks["commands"]

    def zone_set(self, name: str):
        return {
            "alpha": self.alpha_zone,
            "number": self.number_zone,
            "punctuation": self.blocks["punctuation"],
            "extended": self.blocks["extended"],
        }[name]


TPL = Templates(SRC["StrokeTemplates"])

# ---------------------------------------------------------- StrokeRecognizer

_R = SRC["StrokeRecognizer"]
RESAMPLE_COUNT = int_const(_R, "RESAMPLE_COUNT")
MIN_POINTS = int_const(_R, "MIN_POINTS")
SPARSE_POINTS = int_const(_R, "SPARSE_POINTS")
STRAIGHT_GATE = float_const(_R, "STRAIGHT_GATE")
SWING_DEADBAND = float_const(_R, "SWING_DEADBAND")
SWING_GATE = int_const(_R, "SWING_GATE")
RETURN_MIN_SLANT = float_const(_R, "RETURN_MIN_SLANT")
VERTICAL_MAX_SLANT = float_const(_R, "VERTICAL_MAX_SLANT")
AMBIGUOUS_RETURN_PENALTY = float_const(_R, "AMBIGUOUS_RETURN_PENALTY")
VERTICAL_SYMBOLS = set(re.findall(r'"([^"]+)"', re.search(
    r"val VERTICAL_SYMBOLS: Set<String> = setOf\(([^)]*)\)", _R).group(1))) | {"#shift"}
ASPECT_FLOOR = float_const(_R, "ASPECT_FLOOR")
REVERSAL_PROMINENCE = float_const(_R, "REVERSAL_PROMINENCE")
REVERSAL_SLACK = int_const(_R, "REVERSAL_SLACK")
REVERSAL_SLACK_TIGHT = int_const(_R, "REVERSAL_SLACK_TIGHT")
# 折り返しゲートの対象と許容差（StrokeRecognizer.REVERSAL_GATE）
REVERSAL_GATE = {}
for _sym, _slack in re.findall(
        r'"([^"]+)" to (REVERSAL_SLACK_TIGHT|REVERSAL_SLACK)',
        re.search(r"val REVERSAL_GATE: Map<String, Int> = mapOf\(([^)]*)\)", _R).group(1)):
    REVERSAL_GATE[_sym] = REVERSAL_SLACK_TIGHT if _slack.endswith("TIGHT") else REVERSAL_SLACK
SIMPLE_SYMBOLS = set(REVERSAL_GATE)
# 直線 1 本の字形（StrokeRecognizer.LINE_SYMBOLS）
LINE_SYMBOLS = set(re.findall(r'"([^"]+)"', re.search(
    r"val LINE_SYMBOLS: Set<String> = setOf\(([^)]*)\)", _R).group(1))) | {
    "#space", "#backspace", "#return", "#shift", "#ext", "#ext_slash"} - {
    "SPACE_SYMBOL"}
ASPECT_LIMIT = float_const(_R, "ASPECT_LIMIT")
SCORE_THRESHOLD = float_const(_R, "SCORE_THRESHOLD")
PERSONAL_BONUS = float_const(_R, "PERSONAL_BONUS")
_DEG = math.pi / 180.0
ROTATIONS = [
    float(v) * _DEG
    for v in re.search(r"ROTATIONS\s*=\s*floatArrayOf\(([^)]*)\)", _R)
    .group(1)
    .replace("f * DEG", "")
    .replace("* DEG", "")
    .replace("0f,", "0,")
    .split(",")
    if v.strip() not in ("", "0f")
] or []
# 上の式は取りこぼしやすいので明示的に組み立て直す
_rot = re.search(r"ROTATIONS\s*=\s*floatArrayOf\(([^)]*)\)", _R).group(1)
ROTATIONS = []
for tok in _rot.split(","):
    tok = tok.strip()
    if not tok:
        continue
    tok = tok.replace("* DEG", "").replace("*DEG", "").replace("f", "").strip()
    ROTATIONS.append(float(tok) * _DEG)


def path_length(pts: Sequence[Pt]) -> float:
    return sum(math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]) for i in range(1, len(pts)))


def resample(pts: Sequence[Pt], n: int, total: float) -> List[Pt]:
    interval = total / (n - 1)
    if interval <= 0:
        return [pts[0]] * n
    out = [pts[0]]
    acc = 0.0
    prev = pts[0]
    i = 1
    while i < len(pts) and len(out) < n:
        cur = pts[i]
        seg = math.hypot(cur[0] - prev[0], cur[1] - prev[1])
        if seg <= 0:
            prev = cur
            i += 1
            continue
        if acc + seg >= interval:
            t = (interval - acc) / seg
            np = (prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1]))
            out.append(np)
            prev = np
            acc = 0.0
        else:
            acc += seg
            prev = cur
            i += 1
    while len(out) < n:
        out.append(pts[-1])
    return out


def _centroid(pts):
    return (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))


def _rotate(pts, rad):
    cx, cy = _centroid(pts)
    cs, sn = math.cos(rad), math.sin(rad)
    return [((p[0] - cx) * cs - (p[1] - cy) * sn + cx, (p[0] - cx) * sn + (p[1] - cy) * cs + cy) for p in pts]


def straightness(pts) -> float:
    """始点と終点の距離 / 軌跡長。直線なら 1.0、閉じた o は 0。"""
    L = path_length(pts)
    if L <= 0:
        return 1.0
    a, b = pts[0], pts[-1]
    return math.hypot(b[0] - a[0], b[1] - a[1]) / L


def reversals(pts) -> int:
    """横方向の折り返し回数（StrokeRecognizer.reversals の移植）。"""
    if len(pts) < 3:
        return 0
    xs = [p[0] for p in pts]
    extent = max(xs) - min(xs)
    if extent <= 0:
        return 0
    thr = REVERSAL_PROMINENCE * extent
    count = 0
    direction = 0
    anchor = xs[0]
    for x in xs[1:]:
        d = x - anchor
        if direction == 0:
            if abs(d) >= thr:
                direction = 1 if d > 0 else -1
                anchor = x
        elif d * direction > 0:
            anchor = x
        elif abs(d) >= thr:
            count += 1
            direction = -direction
            anchor = x
    return count


def aspect_of(pts) -> float:
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    long_side = max(w, h)
    return 1.0 if long_side <= 0 else min(w, h) / long_side


def lateral_swings(pts) -> int:
    """弦からの符号付き垂直偏差の、不感帯つき符号反転回数（StrokeRecognizer.lateralSwings）。"""
    if len(pts) < 3:
        return 0
    a, b = pts[0], pts[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    chord = math.hypot(dx, dy)
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    scale = max(chord, math.hypot(max(xs) - min(xs), max(ys) - min(ys)))
    if scale <= 0:
        return 0
    deadband = SWING_DEADBAND * scale
    ux, uy = (1.0, 0.0) if chord <= 1e-6 else (dx / chord, dy / chord)
    count = 0
    sign = 0
    for p in pts:
        deviation = (p[0] - a[0]) * -uy + (p[1] - a[1]) * ux
        if abs(deviation) < deadband:
            continue
        s = 1 if deviation > 0 else -1
        if sign == 0:
            sign = s
        elif s != sign:
            count += 1
            sign = s
    return count


def vertical_slant(pts) -> float:
    """始点->終点の弦が垂直軸となす角（度・0〜90）。正規化前の生座標で測る。"""
    a, b = pts[0], pts[-1]
    dx, dy = abs(b[0] - a[0]), abs(b[1] - a[1])
    if dx == 0 and dy == 0:
        return 0.0
    return math.degrees(math.atan2(dx, dy))


def angle_gated(symbol: str, slant: float) -> bool:
    """生の角度による直線系の絞り込み（StrokeRecognizer.angleGated）。"""
    if symbol == "#return":
        return slant <= RETURN_MIN_SLANT
    if symbol in VERTICAL_SYMBOLS:
        return slant >= VERTICAL_MAX_SLANT
    return False


def is_curved(pts) -> bool:
    return straightness(pts) < STRAIGHT_GATE or lateral_swings(pts) >= SWING_GATE


def _normalize_scale(pts, curved=False):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    long_side = max(w, h)
    if long_side <= 0:
        return pts
    a = min(w, h) / long_side
    if a < ASPECT_LIMIT and not (curved and a >= ASPECT_FLOOR):
        return pts
    return [((p[0] - min(xs)) / w, (p[1] - min(ys)) / h) for p in pts]


def _catmull_rom(p0, p1, p2, p3, t):
    t2 = t * t
    t3 = t2 * t
    x = 0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t +
               (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 +
               (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
    y = 0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t +
               (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 +
               (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
    return (x, y)


def interpolate_if_sparse(src: Sequence[Pt]) -> List[Pt]:
    """StrokeRecognizer.interpolateIfSparse の移植（速書きで点が少ないときの補間）。"""
    n = len(src)
    if n >= SPARSE_POINTS or n < 3:
        return list(src)
    per_segment = min(16, max(2, (SPARSE_POINTS * 2) // n))
    out: List[Pt] = []
    for i in range(n - 1):
        p0 = src[0 if i == 0 else i - 1]
        p1 = src[i]
        p2 = src[i + 1]
        p3 = src[n - 1 if i + 2 >= n else i + 2]
        for k in range(per_segment):
            out.append(_catmull_rom(p0, p1, p2, p3, k / per_segment))
    out.append(tuple(src[n - 1]))
    return out


def vectorize(pts: Sequence[Pt], rotation: float) -> Optional[List[float]]:
    if len(pts) < 2:
        return None
    if path_length(pts) <= 0:
        return None
    curved = is_curved(pts)
    pts = interpolate_if_sparse(pts)
    p = resample(pts, RESAMPLE_COUNT, path_length(pts))
    if rotation != 0.0:
        p = _rotate(p, rotation)
    p = _normalize_scale(p, curved)
    cx, cy = _centroid(p)
    v = []
    for q in p:
        v.append(q[0] - cx)
        v.append(q[1] - cy)
    norm = math.sqrt(sum(f * f for f in v))
    if norm < 1e-6:
        return None
    return [f / norm for f in v]


class Recognizer:
    """StrokeRecognizer.kt の移植（文脈バイアス付き）。"""

    def __init__(self, templates, personal=()):
        self.entries = []
        for sym, pts in templates:
            v = vectorize(pts, 0.0)
            if v:
                self.entries.append((sym, v, 0.0, reversals(pts)))
        for sym, pts in personal:
            v = vectorize(pts, 0.0)
            if v:
                self.entries.append((sym, v, PERSONAL_BONUS, reversals(pts)))

    def scores(self, pts, expected: Set[str] = frozenset(), bonus: float = 0.0):
        """symbol -> 最良スコア（バイアス込み）。"""
        best: Dict[str, float] = {}
        # 曲がりの多いストロークは直線 1 本の字形と張り合わせない
        curved = is_curved(pts)
        rev = reversals(pts)
        asp = aspect_of(pts)
        straight = straightness(pts) >= STRAIGHT_GATE
        slant = vertical_slant(pts)
        ambiguous = straight and RETURN_MIN_SLANT < slant < VERTICAL_MAX_SLANT
        for rot in ROTATIONS:
            v = vectorize(pts, rot)
            if v is None:
                continue
            for sym, tv, eb, tpl_rev in self.entries:
                if curved and sym in LINE_SYMBOLS:
                    continue
                _slack = REVERSAL_GATE.get(sym, 0)
                if _slack > 0 and curved and asp >= ASPECT_FLOOR \
                        and rev - tpl_rev >= _slack:
                    continue
                if straight and angle_gated(sym, slant):
                    continue
                dot = sum(a * b for a, b in zip(v, tv))
                s = dot + eb + (bonus if sym in expected else 0.0)
                if ambiguous and sym == "#return":
                    s -= AMBIGUOUS_RETURN_PENALTY
                if sym not in best or s > best[sym]:
                    best[sym] = s
        return best

    def raw_scores(self, pts):
        return self.scores(pts)

    def recognize(self, pts, min_size=0.0, expected: Set[str] = frozenset(), bonus: float = 0.0):
        if len(pts) < MIN_POINTS:
            return None
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        if max(max(xs) - min(xs), max(ys) - min(ys)) < min_size:
            return None
        if path_length(pts) < min_size:
            return None
        sc = self.scores(pts, expected, bonus)
        if not sc:
            return None
        sym = max(sc, key=lambda k: sc[k])
        if expected and bonus:
            # コマンド保護: バイアス無しでコマンドが勝っているなら覆さない
            plain = self.scores(pts)
            psym = max(plain, key=lambda k: plain[k])
            if psym != sym and plain[psym] >= SCORE_THRESHOLD and psym.startswith("#"):
                return (psym, plain[psym])
        if sc[sym] < SCORE_THRESHOLD:
            return None
        return (sym, sc[sym])


# ----------------------------------------------------------- RomajiConverter

_RO = SRC["RomajiConverter"]


class Romaji:
    """RomajiConverter.kt の移植（テーブルは Kotlin から読む）。"""

    TABLE: Dict[str, str] = {}
    for k, v in re.findall(r'put\("((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\)', _RO):
        TABLE[k] = v
    MAX_KEY = max(len(k) for k in TABLE)
    PREFIXES: Set[str] = set()
    for k in TABLE:
        for i in range(1, len(k)):
            PREFIXES.add(k[:i])
    PREFIXES.add("n")
    PREFIXES.add("nn")
    VOWELS = "aiueo"

    @classmethod
    def convert(cls, buffer: str) -> Tuple[str, str]:
        out = []
        rest = buffer
        while rest:
            if rest.startswith("nn"):
                if len(rest) == 2:
                    break
                out.append("ん")
                rest = rest[1:] if rest[2] in cls.VOWELS else rest[2:]
                continue
            matched = False
            ln = min(cls.MAX_KEY, len(rest))
            while ln >= 1:
                kana = cls.TABLE.get(rest[:ln])
                if kana is not None:
                    out.append(kana)
                    rest = rest[ln:]
                    matched = True
                    break
                ln -= 1
            if matched:
                continue
            c = rest[0]
            if c == "n" and len(rest) >= 2:
                nxt = rest[1]
                if nxt not in cls.VOWELS and nxt not in ("y", "n", "'"):
                    out.append("ん")
                    rest = rest[1:]
                    continue
            if len(rest) >= 2 and c == rest[1] and c not in cls.VOWELS and c != "n" and c in cls.PREFIXES:
                out.append("っ")
                rest = rest[1:]
                continue
            if len(rest) < cls.MAX_KEY and rest in cls.PREFIXES:
                break
            out.append(c)
            rest = rest[1:]
        return "".join(out), rest

    # 「ん」を明示入力する綴り（Kotlin: RomajiConverter.SOKUON_N）
    SOKUON_N = re.search(r'SOKUON_N\s*=\s*"([^"]+)"', _RO).group(1)

    # --- 自動アルファベット化（Kotlin: RomajiConverter.latinFallbackCount / looksNonJapanese）
    NON_JAPANESE_FALLBACKS = int(
        re.search(r"NON_JAPANESE_FALLBACKS\s*=\s*(\d+)", _RO).group(1))

    @classmethod
    def latin_fallback_count(cls, raw: str) -> int:
        """convert の回復処理（Latin パススルー）が何回起きるか。"""
        rest = raw
        n = 0
        while rest:
            if rest.startswith("nn"):
                if len(rest) == 2:
                    break
                rest = rest[1:] if rest[2] in cls.VOWELS else rest[2:]
                continue
            matched = False
            ln = min(cls.MAX_KEY, len(rest))
            while ln >= 1:
                if cls.TABLE.get(rest[:ln]) is not None:
                    rest = rest[ln:]
                    matched = True
                    break
                ln -= 1
            if matched:
                continue
            c = rest[0]
            if c == "n" and len(rest) >= 2 and rest[1] not in cls.VOWELS \
                    and rest[1] not in ("y", "n", "'"):
                rest = rest[1:]
                continue
            if len(rest) >= 2 and c == rest[1] and c not in cls.VOWELS and c != "n" \
                    and c in cls.PREFIXES:
                rest = rest[1:]
                continue
            if len(rest) < cls.MAX_KEY and rest in cls.PREFIXES:
                break
            n += 1
            rest = rest[1:]
        return n

    @classmethod
    def looks_non_japanese(cls, raw: str) -> bool:
        return cls.latin_fallback_count(raw) >= cls.NON_JAPANESE_FALLBACKS

    @classmethod
    def flush(cls, buffer: str) -> str:
        if not buffer:
            return ""
        kana, pending = cls.convert(buffer)
        if not pending:
            return kana
        return kana + ("ん" if pending == cls.SOKUON_N else pending)

    @classmethod
    def preview(cls, pending: str) -> str:
        """合成中の未確定バッファの表示。単独の "n" は "n" のまま。"""
        return "ん" if pending == cls.SOKUON_N else pending

    @classmethod
    def settled_pending(cls, pending: str) -> str:
        """未確定のうち、すでにかなとして見えている部分（"nn" -> 「ん」）。"""
        return "ん" if pending == cls.SOKUON_N else ""

    # --- 文脈バイアス（Kotlin: RomajiConverter.expectedNext）
    @classmethod
    def expected_next(cls, pending: str) -> Set[str]:
        if not pending or len(pending) >= cls.MAX_KEY:
            return set()
        # "n" の直後は撥音の先読みでほぼ全子音が正当。絞り込めないのでバイアスしない。
        if pending == "n":
            return set()
        out: Set[str] = set()
        for c in "abcdefghijklmnopqrstuvwxyz":
            cand = pending + c
            if cand in cls.TABLE or cand in cls.PREFIXES:
                out.add(c)
        if len(pending) == 1:
            head = pending[0]
            # 促音（kk -> っか）
            if head not in cls.VOWELS and head in cls.PREFIXES:
                out.add(head)
        # 「あまりに広い」期待集合はバイアスの意味が無いので捨てる
        if len(out) > MAX_EXPECTED:
            return set()
        return out


MAX_EXPECTED = int_const(_RO, "MAX_EXPECTED")
CONTEXT_BONUS = float_const(SRC["StrokeRecognizer"], "CONTEXT_BONUS")


def expected_for(pending: str) -> Set[str]:
    return Romaji.expected_next(pending)
