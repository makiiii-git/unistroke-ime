#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OnDeviceConverter.kt / OnDeviceDictionary.kt の Python 移植。

Kotlin 側と同じバイナリ辞書を読み、同じラティス構築と Viterbi を行う。
コスト定数は OnDeviceConverter.kt の companion object と 1 対 1 で対応し、
test_ondevice.py が両者の一致を検査する（片方だけ直すと FAIL する）。
"""

from __future__ import annotations

import mmap
import os
import struct

ROOT = os.path.dirname(os.path.abspath(__file__))

# 既定は同梱するコア辞書。UNISTROKE_DIC を指すと別の辞書で検証できる
# （コア / 拡張の語数を変えて品質を比べるときに使う）。
DIC_PATH = os.environ.get(
    "UNISTROKE_DIC",
    os.path.join(ROOT, "app", "src", "main", "assets", "ondevice.dic"),
)

MAGIC = b"UNIDIC2\x00"
HEADER_SIZE = 80

# ------------------------------------------------------------- 品詞クラス

(POS_OTHER, POS_NOUN, POS_PROPER, POS_VERB, POS_ADJ, POS_ADVERB, POS_PARTICLE,
 POS_AUX, POS_PREFIX, POS_SUFFIX, POS_ADNOMINAL, POS_CONJUNCTION,
 POS_INTERJECTION, POS_NUMBER, POS_SYMBOL) = range(15)
POS_COUNT = 15

FLAG_NONFINAL = 1
FLAG_FINAL = 2
FLAG_INDEPENDENT = 4

# --------------------------------------------------------------- コスト定数
# ※ OnDeviceConverter.kt の同名定数と必ず一致させること。

WORD_PENALTY = 1200          # 文節が増えることへの一律ペナルティ（過分割を抑える）
UNKNOWN_BASE = 8000          # 辞書に無いかな列のノード基本コスト
UNKNOWN_PER_CHAR = 3000      # 同・1 文字あたり
UNKNOWN_MAX_LEN = 6          # かな素通しノードの最大長
SHORT_CONTENT_PENALTY = 1500  # 1 文字の自立語（名詞・動詞など）へのペナルティ
PROPER_PENALTY = 700         # 固有名詞へのペナルティ
UNKNOWN_CONNECTION = 5000    # かなノードの接続コスト（品詞が分からないので固定）
MAX_ALTERNATIVES = 5         # 1 文節あたりに返す代替候補の数
PREDICT_LENGTH_COST = 300    # 予測変換で 1 文字よけいに補完するたびに足すコスト
PREDICT_SCAN_KEYS = 600      # 予測変換でなめる鍵の上限
MIN_PREDICT_PREFIX = 2       # これより短い読みでは予測を出さない


def node_penalty(pos: int, flags: int, length: int) -> int:
    c = WORD_PENALTY
    if pos == POS_PROPER:
        c += PROPER_PENALTY
    if length == 1 and (flags & FLAG_INDEPENDENT) and pos in (
            POS_NOUN, POS_PROPER, POS_VERB, POS_ADJ, POS_NUMBER):
        c += SHORT_CONTENT_PENALTY
    return c


# ------------------------------------------------------------------ 辞書


class Dictionary:
    """バイナリ辞書のリーダ（Kotlin 側は ByteBuffer で同じことをする）。"""

    def __init__(self, path: str = DIC_PATH):
        self.f = open(path, "rb")
        self.buf = mmap.mmap(self.f.fileno(), 0, access=mmap.ACCESS_READ)
        if self.buf[0:8] != MAGIC:
            raise ValueError("magic が違う: %r" % self.buf[0:8])
        (self.key_count, self.word_count, self.key_index_off, self.key_blob_off,
         self.key_blob_len, self.word_array_off, self.surface_blob_off,
         self.surface_blob_len, self.max_key_chars, self.alphabet_off,
         self.group_count, self.group_table_off, self.matrix_off, self.bos_group,
         self.version) = struct.unpack_from("<IIIIIIIIIIIIIII", self.buf, 8)

        n, = struct.unpack_from("<H", self.buf, self.alphabet_off)
        self.alphabet = [
            struct.unpack_from("<H", self.buf, self.alphabet_off + 2 + 2 * i)[0]
            for i in range(n)
        ]
        self.encode_map = {chr(cp): i + 1 for i, cp in enumerate(self.alphabet)}

    def close(self):
        self.buf.close()
        self.f.close()

    # -------------------------------------------------------------- 低レベル

    def _key_entry(self, i: int) -> tuple[int, int]:
        return struct.unpack_from("<II", self.buf, self.key_index_off + 8 * i)

    def key_len(self, i: int) -> int:
        return self._key_entry(i + 1)[0] - self._key_entry(i)[0]

    def key_byte(self, i: int, pos: int) -> int:
        return self.buf[self.key_blob_off + self._key_entry(i)[0] + pos]

    def key_str(self, i: int) -> str:
        off, _ = self._key_entry(i)
        end = self._key_entry(i + 1)[0]
        return "".join(chr(self.alphabet[b - 1])
                       for b in self.buf[self.key_blob_off + off:self.key_blob_off + end])

    def word(self, w: int) -> tuple[str, int, int, int]:
        """(表記, コスト, 左グループ, 右グループ)"""
        s_off, cost, lgroup, rgroup = struct.unpack_from(
            "<IHHH", self.buf, self.word_array_off + 10 * w)
        s_end, = struct.unpack_from("<I", self.buf, self.word_array_off + 10 * (w + 1))
        raw = self.buf[self.surface_blob_off + s_off:self.surface_blob_off + s_end]
        return raw.decode("utf-8"), cost, lgroup, rgroup

    def group_attr(self, g: int) -> tuple[int, int]:
        """グループ -> (品詞クラス, フラグ)"""
        return struct.unpack_from("<BB", self.buf, self.group_table_off + 2 * g)

    def connection(self, right_group_of_left: int, left_group_of_right: int) -> int:
        """Mozc の接続コスト（左語の rightId 側 x 右語の leftId 側）。"""
        i = right_group_of_left * self.group_count + left_group_of_right
        return struct.unpack_from("<h", self.buf, self.matrix_off + 2 * i)[0]

    def words_of(self, i: int) -> range:
        return range(self._key_entry(i)[1], self._key_entry(i + 1)[1])

    def encode(self, s: str) -> bytes | None:
        out = bytearray()
        for ch in s:
            v = self.encode_map.get(ch)
            if v is None:
                return None
            out.append(v)
        return bytes(out)

    # ----------------------------------------------------- 共通接頭辞検索

    def _narrow(self, lo: int, hi: int, depth: int, b: int) -> tuple[int, int]:
        """[lo,hi) 内で depth バイト目が b の範囲へ絞る（短い鍵は「小さい」とみなす）。"""
        def cmp(i: int) -> int:
            if self.key_len(i) <= depth:
                return -1
            v = self.key_byte(i, depth)
            return -1 if v < b else (0 if v == b else 1)

        a, z = lo, hi
        while a < z:
            m = (a + z) // 2
            if cmp(m) < 0:
                a = m + 1
            else:
                z = m
        start = a
        a, z = start, hi
        while a < z:
            m = (a + z) // 2
            if cmp(m) <= 0:
                a = m + 1
            else:
                z = m
        return start, a

    def common_prefix_search(self, reading: str, start: int):
        """reading[start:] の接頭辞になっている読みを (長さ, 鍵インデックス) で返す。"""
        q = self.encode(reading[start:start + self.max_key_chars])
        if q is None:
            q = b""
            for ch in reading[start:start + self.max_key_chars]:
                v = self.encode_map.get(ch)
                if v is None:
                    break
                q += bytes([v])
        out = []
        lo, hi = 0, self.key_count
        for depth in range(len(q)):
            lo, hi = self._narrow(lo, hi, depth, q[depth])
            if lo >= hi:
                break
            if self.key_len(lo) == depth + 1:
                out.append((depth + 1, lo))
        return out


# ------------------------------------------------------------------ 変換


class Node:
    __slots__ = ("start", "end", "surface", "reading", "lgroup", "rgroup",
                 "cost", "total", "prev", "key", "unknown")

    def __init__(self, start, end, surface, reading, lgroup, rgroup, cost,
                 key=-1, unknown=False):
        self.start = start
        self.end = end
        self.surface = surface
        self.reading = reading
        self.lgroup = lgroup      # 左側（前の語と繋ぐときに見る）
        self.rgroup = rgroup      # 右側（次の語と繋ぐときに見る）
        self.cost = cost
        self.key = key
        self.unknown = unknown    # 辞書に無いかな素通しノード
        self.total = 1 << 60
        self.prev = None


KATAKANA_SHIFT = 0x30A1 - 0x3041


def to_katakana(s: str) -> str:
    out = []
    for ch in s:
        c = ord(ch)
        out.append(chr(c + KATAKANA_SHIFT) if 0x3041 <= c <= 0x3096 else ch)
    return "".join(out)


class Segment:
    def __init__(self, reading: str, candidates: list[str]):
        self.reading = reading
        self.candidates = candidates


class Converter:
    def __init__(self, dic: Dictionary):
        self.dic = dic

    # ------------------------------------------------------------ ラティス

    def build_lattice(self, reading: str) -> list[list[Node]]:
        n = len(reading)
        dic = self.dic
        ends: list[list[Node]] = [[] for _ in range(n + 1)]
        for i in range(n):
            hits = dic.common_prefix_search(reading, i)
            for length, key in hits:
                for w in dic.words_of(key):
                    surface, cost, lgroup, rgroup = dic.word(w)
                    pos, flags = dic.group_attr(lgroup)
                    ends[i + length].append(
                        Node(i, i + length, surface, reading[i:i + length],
                             lgroup, rgroup,
                             cost + node_penalty(pos, flags, length), key))
            # 辞書に無い区間を埋めるためのかな素通しノード
            covered = {length for length, _ in hits}
            for length in range(1, min(UNKNOWN_MAX_LEN, n - i) + 1):
                if length in covered:
                    continue
                ends[i + length].append(
                    Node(i, i + length, reading[i:i + length], reading[i:i + length],
                         -1, -1,
                         UNKNOWN_BASE + UNKNOWN_PER_CHAR * length + WORD_PENALTY,
                         unknown=True))
        return ends

    def conn(self, prev: Node | None, cur: Node | None) -> int:
        """接続コスト。片方が None なら BOS / EOS。かなノードは品詞が無いので固定値。"""
        dic = self.dic
        if (prev is not None and prev.unknown) or (cur is not None and cur.unknown):
            return UNKNOWN_CONNECTION
        left = dic.bos_group if prev is None else prev.rgroup
        right = dic.bos_group if cur is None else cur.lgroup
        return dic.connection(left, right)

    def best_path(self, reading: str) -> list[Node]:
        n = len(reading)
        ends = self.build_lattice(reading)
        starts: list[list[Node]] = [[] for _ in range(n + 1)]
        for lst in ends:
            for nd in lst:
                starts[nd.start].append(nd)

        for nd in starts[0]:
            nd.total = nd.cost + self.conn(None, nd)
        for i in range(1, n + 1):
            for nd in starts[i]:
                best = None
                bestc = 1 << 60
                for pv in ends[i]:
                    if pv.total >= (1 << 60):
                        continue
                    c = pv.total + self.conn(pv, nd)
                    if c < bestc:
                        bestc = c
                        best = pv
                if best is not None:
                    nd.total = bestc + nd.cost
                    nd.prev = best

        best = None
        bestc = 1 << 60
        for pv in ends[n]:
            if pv.total >= (1 << 60):
                continue
            c = pv.total + self.conn(pv, None)
            if c < bestc:
                bestc = c
                best = pv
        path = []
        while best is not None:
            path.append(best)
            best = best.prev
        path.reverse()
        return path

    # ------------------------------------------------------------ 候補生成

    def convert(self, reading: str) -> list[Segment]:
        if not reading:
            return []
        path = self.best_path(reading)
        if not path:
            return [Segment(reading, [reading, to_katakana(reading)])]
        out = []
        for nd in path:
            cands = [nd.surface]
            if nd.key >= 0:
                for w in self.dic.words_of(nd.key):
                    s, _c, _lg, _rg = self.dic.word(w)
                    if s not in cands:
                        cands.append(s)
                    if len(cands) >= MAX_ALTERNATIVES:
                        break
            if nd.reading not in cands:
                cands.append(nd.reading)
            kata = to_katakana(nd.reading)
            if kata not in cands:
                cands.append(kata)
            out.append(Segment(nd.reading, cands))
        return out

    def convert_text(self, reading: str) -> str:
        return "".join(s.candidates[0] for s in self.convert(reading))

    # ------------------------------------------------------------ 前方一致予測

    def predict(self, prefix: str, limit: int = 8) -> list[tuple[str, str]]:
        """prefix を接頭辞に持つ辞書語を (読み, 表記) で返す。"""
        if len(prefix) < MIN_PREDICT_PREFIX or limit <= 0:
            return []
        q = self.dic.encode(prefix)
        if not q:
            return []
        lo, hi = 0, self.dic.key_count
        for depth in range(len(q)):
            lo, hi = self.dic._narrow(lo, hi, depth, q[depth])
            if lo >= hi:
                return []
        hi = min(hi, lo + PREDICT_SCAN_KEYS)
        scored = []
        for i in range(lo, hi):
            r = self.dic.key_str(i)
            if len(r) <= len(prefix):
                continue
            for w in list(self.dic.words_of(i))[:MAX_ALTERNATIVES]:
                s, cost, _lg, _rg = self.dic.word(w)
                if s == r:
                    continue
                # 補完量が多いほど後ろへ回す（「おは」->「おはよう」を先に出す）
                scored.append((cost + PREDICT_LENGTH_COST * (len(r) - len(prefix)), r, s))
        scored.sort()
        out = []
        seen = set()
        for _score, r, s in scored:
            if s in seen:
                continue
            seen.add(s)
            out.append((r, s))
            if len(out) >= limit:
                break
        return out


_shared = None


def shared() -> Converter:
    global _shared
    if _shared is None:
        _shared = Converter(Dictionary())
    return _shared
