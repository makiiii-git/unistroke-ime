#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mozc の OSS 辞書から、オンデバイス変換用のバイナリ辞書を作る。

    python3 tools/build_dictionary.py --fetch
    python3 tools/build_dictionary.py --src <mozc の dictionary_oss ディレクトリ>

生成物は app/src/main/assets/ondevice.dic（既定）。
フォーマットの詳細は tools/README.md と OnDeviceDictionary.kt を参照。

元データ（すべて google/mozc・BSD-3-Clause）:
  src/data/dictionary_oss/dictionary0[0-9].txt
      読み <TAB> 左文脈ID <TAB> 右文脈ID <TAB> コスト <TAB> 表記
  src/data/dictionary_oss/id.def                    文脈ID -> 品詞文字列
  src/data/dictionary_oss/connection_single_column.txt
      2672 x 2672 の接続コスト行列（36 MB のテキスト）

接続行列は生のままだと int16 でも 14 MB あって端末に載せられない。
そこで id.def の品詞文字列（活用型を除く 5 フィールド）で文脈IDを
約 190 の「品詞グループ」へまとめ、グループ対ごとに代表値を取って
190x190 程度（70 KB 弱）へ畳んでから同梱する。
これで手書きの接続ヒューリスティクスをほぼ実データで置き換えられる。
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "ondevice.dic")

MOZC_RAW = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/"
DICT_FILES = ["dictionary%02d.txt" % i for i in range(10)]
AUX_FILES = ["id.def", "connection_single_column.txt"]

MAGIC = b"UNIDIC2\x00"
HEADER_SIZE = 80
FORMAT_VERSION = 2

# ---------------------------------------------------------------- 読みの字種

def _reading_alphabet() -> list[str]:
    """読みに使える文字。コードポイント昇順（バイト値の大小＝文字の大小にする）。"""
    chars = [chr(c) for c in range(0x3041, 0x3097)]  # ぁ..ゖ
    chars += ["ゝ", "ゞ"]                    # ゝ ゞ
    chars += ["ー"]                              # ー
    return sorted(set(chars))


ALPHABET = _reading_alphabet()
# 文字 -> バイト値（1 始まり。0 は終端・番兵に予約）
ENCODE = {ch: i + 1 for i, ch in enumerate(ALPHABET)}
assert len(ALPHABET) < 250, "1 バイト符号に収まらない"

MAX_READING_CHARS = 24

# ------------------------------------------------------------- 品詞クラス

# OnDeviceConverter.Pos と一対一。値を変えるときは Kotlin 側も直すこと。
POS_OTHER = 0
POS_NOUN = 1
POS_PROPER = 2
POS_VERB = 3
POS_ADJ = 4
POS_ADVERB = 5
POS_PARTICLE = 6
POS_AUX = 7
POS_PREFIX = 8
POS_SUFFIX = 9
POS_ADNOMINAL = 10
POS_CONJUNCTION = 11
POS_INTERJECTION = 12
POS_NUMBER = 13
POS_SYMBOL = 14
POS_COUNT = 15

POS_NAMES = [
    "OTHER", "NOUN", "PROPER", "VERB", "ADJ", "ADVERB", "PARTICLE", "AUX",
    "PREFIX", "SUFFIX", "ADNOMINAL", "CONJUNCTION", "INTERJECTION", "NUMBER",
    "SYMBOL",
]

# flags
FLAG_NONFINAL = 1 << 0   # 未然形・連用形など、単独で文を終えられない活用形
FLAG_FINAL = 1 << 1      # 基本形・終止形など、文を終えられる
FLAG_INDEPENDENT = 1 << 2  # 自立語（文節の先頭になれる）


def classify(pos: str) -> tuple[int, int]:
    """id.def の品詞文字列 -> (クラス, フラグ)。"""
    f = pos.split(",")
    major = f[0] if f else "*"
    sub1 = f[1] if len(f) > 1 else "*"
    sub2 = f[2] if len(f) > 2 else "*"
    conj = f[5] if len(f) > 5 else "*"

    flags = 0
    if conj != "*":
        # 「基本形」「体言接続」「命令ｒｏ」等。文を終えられるのは基本形/終止形系。
        if conj.startswith("基本形") or conj.startswith("終止") or conj.startswith("命令"):
            flags |= FLAG_FINAL
        else:
            flags |= FLAG_NONFINAL

    if major == "助詞":
        return POS_PARTICLE, flags
    if major == "助動詞":
        return POS_AUX, flags
    if major == "接頭詞":
        return POS_PREFIX, flags
    if major == "連体詞":
        return POS_ADNOMINAL, flags | FLAG_INDEPENDENT
    if major == "接続詞":
        return POS_CONJUNCTION, flags | FLAG_INDEPENDENT
    if major in ("感動詞", "フィラー", "その他"):
        return POS_INTERJECTION, flags | FLAG_INDEPENDENT
    if major == "記号":
        return POS_SYMBOL, flags
    if major == "副詞":
        return POS_ADVERB, flags | FLAG_INDEPENDENT
    if major == "動詞":
        if sub1 == "接尾":
            return POS_SUFFIX, flags
        return POS_VERB, flags | (FLAG_INDEPENDENT if sub1 == "自立" else 0)
    if major == "形容詞":
        if sub1 == "接尾":
            return POS_SUFFIX, flags
        return POS_ADJ, flags | (FLAG_INDEPENDENT if sub1 == "自立" else 0)
    if major == "名詞":
        if sub1 == "接尾" or sub1 == "接尾可能":
            return POS_SUFFIX, flags
        if sub1 == "固有名詞":
            return POS_PROPER, flags | FLAG_INDEPENDENT
        if sub1 == "数":
            return POS_NUMBER, flags | FLAG_INDEPENDENT
        if sub1 == "非自立":
            return POS_NOUN, flags
        return POS_NOUN, flags | FLAG_INDEPENDENT
    return POS_OTHER, flags


# 語彙を絞るときの下駄（大きいほど落ちやすい）。
# 固有名詞は数が多いわりに日常の入力では出番が少ないので重く、
# 機能語（助詞・助動詞）は数が少なく必須なので必ず残す。
POS_TRIM_BIAS = {
    POS_PROPER: 5200,
    POS_NOUN: 0,
    POS_VERB: -300,
    POS_ADJ: -300,
    POS_ADVERB: -600,
    POS_PARTICLE: -20000,
    POS_AUX: -20000,
    POS_ADNOMINAL: -6000,
    POS_CONJUNCTION: -6000,
    POS_INTERJECTION: -2000,
    POS_PREFIX: -1000,
    POS_SUFFIX: -1000,
    POS_NUMBER: 1500,
    POS_SYMBOL: -2000,
    POS_OTHER: 2000,
}

# 1 文字の読みは数が多いうえにラティスを膨らませるので、自立語は特に厳しく削る。
SHORT_READING_BIAS = {1: 2500, 2: 400}

# ひらがな読みに対するカタカナ表記。Mozc には「です -> デス」「いい -> イイ」のように
# コスト 0 の写像が入っていて、そのままだと平文がカタカナだらけになる。
# 接続行列を持たない簡易エンジンでは分が悪いので、保存コストごと押し下げる。
KATAKANA_BIAS = 900

# 読みと表記が同じ（＝ひらがなのまま）の語。実行時にかなノードとして必ず作れるが、
# 「ございます」のような高頻度語は辞書に有るほうがラティスが素直になる。
# ただし Mozc には「わたし」がコスト 0 で入っていて、放っておくと「私」に勝ってしまう。
# 残す（TRIM）ときも、実行時のコスト（COST）でも、ひらがな表記は一段下げる。
SAME_AS_READING_BIAS = 1500
SAME_AS_READING_COST_BIAS = 1200

MAX_PER_READING = 6

# 接続行列に一度も現れなかったグループ対のコスト。
# 「まず繋がらないが絶対禁止でもない」くらいの値。
MISSING_CONNECTION_COST = 6000

# グループ対の代表値の取り方。0 で最小値、1 で平均。
#   min  … そのグループ対が成立する最良のケース。楽観的すぎて 1 文字漢字の誤変換を止められない
#   mean … グループ内の外れ値に引きずられて、正しい接続まで高くなる
# 20 文のスモークテストではこのあたりが最良（tools/README.md 参照）。
CONNECTION_BLEND = 0.25

_KATAKANA = set(chr(c) for c in range(0x30A1, 0x30FD))


# ひらがな表記を下げてよい品詞。助詞・助動詞・感動詞などの機能語は
# 「は」「を」「です」「ありがとう」のようにひらがなが正解なので触らない。
_SAME_AS_READING_POS = (POS_NOUN, POS_PROPER, POS_VERB, POS_ADJ, POS_ADVERB,
                        POS_NUMBER, POS_SUFFIX)


def stored_cost(reading: str, surface: str, cost: int, klass: int) -> int:
    if surface and all(ch in _KATAKANA for ch in surface):
        cost += KATAKANA_BIAS
    if reading == surface and klass in _SAME_AS_READING_POS:
        cost += SAME_AS_READING_COST_BIAS
    return max(0, min(cost, 0xFFFF))

# --------------------------------------------------------------------- 取得


def fetch(dest: str) -> None:
    os.makedirs(dest, exist_ok=True)
    for name in DICT_FILES + AUX_FILES:
        path = os.path.join(dest, name)
        if os.path.exists(path) and os.path.getsize(path) > 0:
            print("  skip (exists)  %s" % name)
            continue
        url = MOZC_RAW + name
        print("  GET  %s" % url)
        urllib.request.urlretrieve(url, path)
    lic = os.path.join(dest, "LICENSE")
    if not os.path.exists(lic):
        urllib.request.urlretrieve(
            "https://raw.githubusercontent.com/google/mozc/master/LICENSE", lic)
        print("  GET  LICENSE")


# --------------------------------------------------------------------- 読み込み


# 品詞グループを作るときに見る id.def のフィールド。
# 4 番目（活用型: 五段・カ行 など）は接続の可否をほとんど左右しないので落とし、
# 6 番目（語そのもの）は細かすぎるので落とす。残りで約 190 グループになる。
GROUP_FIELDS = (0, 1, 2, 3, 5)


class PosTable:
    """文脈ID -> 品詞グループ / 品詞クラス / フラグ。"""

    def __init__(self, src: str):
        raw: dict[int, str] = {}
        with open(os.path.join(src, "id.def"), encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split(" ", 1)
                if len(parts) != 2:
                    continue
                raw[int(parts[0])] = parts[1]
        self.max_id = max(raw) + 1

        def key_of(pos: str) -> tuple:
            f = (pos.split(",") + ["*"] * 7)[:7]
            return tuple(f[i] for i in GROUP_FIELDS)

        keys = sorted({key_of(p) for p in raw.values()})
        index = {k: i for i, k in enumerate(keys)}
        # 文脈ID -> グループ。id.def に無い ID は 0（BOS/EOS 相当）へ落とす。
        self.group_of = [0] * self.max_id
        # グループ -> (品詞クラス, フラグ)
        self.group_attr = [(POS_OTHER, 0)] * len(keys)
        for i, pos in raw.items():
            g = index[key_of(pos)]
            self.group_of[i] = g
            self.group_attr[g] = classify(pos)
        self.group_count = len(keys)
        self.group_keys = keys
        # BOS/EOS は id 0
        self.bos_group = self.group_of[0]

    def group(self, ctx_id: int) -> int:
        return self.group_of[ctx_id] if 0 <= ctx_id < self.max_id else 0


def load_connection_matrix(src: str, pos: PosTable, verbose: bool) -> bytes:
    """2672x2672 の接続コストを、品詞グループ対の代表値へ畳む。

    Mozc の接続コストは matrix[左語の rightId][右語の leftId]。
    グループへ潰すときは最小値と平均を [CONNECTION_BLEND] で混ぜる
    （最小だけだと楽観的すぎ、平均だけだと外れ値に引きずられる）。
    """
    path = os.path.join(src, "connection_single_column.txt")
    if not os.path.exists(path):
        raise SystemExit("接続行列が見つからない: %s（--fetch を先に実行）" % path)
    g = pos.group_of
    n = pos.group_count
    INF = 1 << 30
    lowest = [INF] * (n * n)
    total = [0] * (n * n)
    count = [0] * (n * n)
    with open(path, encoding="utf-8") as f:
        size = int(f.readline().strip())
        if size != pos.max_id:
            raise SystemExit("接続行列の次数 %d が id.def の %d と合わない" % (size, pos.max_id))
        for left in range(size):
            gl = g[left] * n
            for right in range(size):
                v = int(f.readline())
                k = gl + g[right]
                if v < lowest[k]:
                    lowest[k] = v
                total[k] += v
                count[k] += 1
    out = bytearray()
    filled = 0
    for k in range(n * n):
        c = count[k]
        if c == 0:
            # 未出現の組は「かなり繋がりにくい」既定値にする
            v = MISSING_CONNECTION_COST
        else:
            lo = lowest[k]
            v = int(lo + CONNECTION_BLEND * (total[k] / c - lo))
            filled += 1
        out += struct.pack("<h", max(-32768, min(32767, v)))
    if verbose:
        print("  接続行列: %d 文脈ID -> %d グループ（%d KB, 実測 %.1f%%）" %
              (pos.max_id, n, len(out) // 1024, 100.0 * filled / (n * n)))
    return bytes(out)


def encode_reading(reading: str) -> bytes | None:
    out = bytearray()
    for ch in reading:
        v = ENCODE.get(ch)
        if v is None:
            return None
        out.append(v)
    return bytes(out)


def load_entries(src: str, pos: PosTable):
    """(読み, 表記) -> (コスト, 左グループ, 右グループ)。同じ組は最小コストへ畳む。"""
    best: dict[tuple[str, str], tuple[int, int, int]] = {}
    scanned = 0
    for name in DICT_FILES:
        path = os.path.join(src, name)
        if not os.path.exists(path):
            raise SystemExit("辞書が見つからない: %s（--fetch を先に実行）" % path)
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 5:
                    continue
                reading, lid, rid, cost, surface = parts
                scanned += 1
                if not reading or not surface:
                    continue
                if len(reading) > MAX_READING_CHARS:
                    continue
                if encode_reading(reading) is None:
                    continue
                c = int(cost)
                key = (reading, surface)
                cur = best.get(key)
                if cur is None or c < cur[0]:
                    best[key] = (c, pos.group(int(lid)), pos.group(int(rid)))
    return best, scanned


def trim(best: dict, pos: PosTable, limit: int, verbose: bool):
    """コスト＋品詞バイアスの小さい順に limit 件へ削る。"""
    scored = []
    for (reading, surface), (cost, lgroup, rgroup) in best.items():
        klass, _flags = pos.group_attr[lgroup]
        c = stored_cost(reading, surface, cost, klass)
        score = c + POS_TRIM_BIAS.get(klass, 0)
        if klass in (POS_NOUN, POS_PROPER, POS_VERB, POS_ADJ, POS_NUMBER):
            score += SHORT_READING_BIAS.get(len(reading), 0)
        if reading == surface:
            score += SAME_AS_READING_BIAS
        scored.append((score, c, reading, surface, lgroup, rgroup))
    scored.sort(key=lambda t: (t[0], t[2], t[3]))

    per_reading: dict[str, int] = {}
    kept = []
    for score, cost, reading, surface, lgroup, rgroup in scored:
        n = per_reading.get(reading, 0)
        if n >= MAX_PER_READING:
            continue
        per_reading[reading] = n + 1
        kept.append((reading, surface, cost, lgroup, rgroup))
        if len(kept) >= limit:
            break
    if verbose:
        print("  トリミング: %d -> %d 語 / 読み %d 種" %
              (len(scored), len(kept), len(per_reading)))
    return kept


# --------------------------------------------------------------------- 書き出し


def build(kept, pos: PosTable, matrix: bytes, out_path: str, verbose: bool) -> dict:
    # 読みごとにまとめ、コスト昇順（＝候補の並び順）にする
    by_reading: dict[str, list] = {}
    for reading, surface, cost, lgroup, rgroup in kept:
        by_reading.setdefault(reading, []).append((cost, surface, lgroup, rgroup))

    # 読みは符号化バイト列の辞書順＝コードポイント順に並べる
    readings = sorted(by_reading.keys(), key=encode_reading)

    key_index = bytearray()
    key_blob = bytearray()
    word_array = bytearray()
    surface_blob = bytearray()

    word_start = 0
    max_key_chars = 0
    for reading in readings:
        enc = encode_reading(reading)
        key_index += struct.pack("<II", len(key_blob), word_start)
        key_blob += enc
        max_key_chars = max(max_key_chars, len(enc))
        words = sorted(by_reading[reading], key=lambda t: (t[0], t[1]))
        for cost, surface, lgroup, rgroup in words:
            word_array += struct.pack(
                "<IHHH", len(surface_blob), min(cost, 0xFFFF), lgroup, rgroup)
            surface_blob += surface.encode("utf-8")
        word_start += len(words)
    # 番兵（表記の長さを差分で求めるため）
    key_index += struct.pack("<II", len(key_blob), word_start)
    word_array += struct.pack("<IHHH", len(surface_blob), 0, 0, 0)

    alphabet = bytearray(struct.pack("<H", len(ALPHABET)))
    for ch in ALPHABET:
        alphabet += struct.pack("<H", ord(ch))

    # グループ -> (品詞クラス, フラグ)
    group_table = bytearray()
    for klass, flags in pos.group_attr:
        group_table += struct.pack("<BB", klass, flags)

    key_count = len(readings)
    word_count = word_start

    off = HEADER_SIZE
    key_index_off = off
    off += len(key_index)
    key_blob_off = off
    off += len(key_blob)
    word_array_off = off
    off += len(word_array)
    surface_blob_off = off
    off += len(surface_blob)
    alphabet_off = off
    off += len(alphabet)
    group_table_off = off
    off += len(group_table)
    matrix_off = off
    off += len(matrix)

    header = bytearray(HEADER_SIZE)
    header[0:8] = MAGIC
    struct.pack_into(
        "<IIIIIIIIIIIIIII", header, 8,
        key_count, word_count,
        key_index_off, key_blob_off, len(key_blob),
        word_array_off, surface_blob_off, len(surface_blob),
        max_key_chars, alphabet_off,
        pos.group_count, group_table_off, matrix_off, pos.bos_group,
        FORMAT_VERSION,
    )

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        for chunk in (header, key_index, key_blob, word_array, surface_blob,
                      alphabet, group_table, matrix):
            f.write(chunk)

    size = os.path.getsize(out_path)
    if verbose:
        print("  書き出し: %s" % out_path)
        print("    読み %d 種 / 語 %d 件 / %.2f MB" % (key_count, word_count, size / 1048576.0))
        print("    key_index %d / key_blob %d / words %d / surfaces %d / matrix %d" %
              (len(key_index), len(key_blob), len(word_array), len(surface_blob),
               len(matrix)))
    return {
        "key_count": key_count,
        "word_count": word_count,
        "bytes": size,
        "max_key_chars": max_key_chars,
        "group_count": pos.group_count,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.path.join(ROOT, "tools", "mozc-src"),
                    help="mozc の dictionary_oss を置いたディレクトリ")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fetch", action="store_true", help="足りないファイルを GitHub から落とす")
    ap.add_argument("--limit", type=int, default=220000, help="残す語数の上限")
    ap.add_argument("-q", "--quiet", action="store_true")
    args = ap.parse_args()
    verbose = not args.quiet

    if args.fetch:
        print("Mozc OSS 辞書を取得（BSD-3-Clause）")
        fetch(args.src)

    if verbose:
        print("読み込み: %s" % args.src)
    pos = PosTable(args.src)
    best, scanned = load_entries(args.src, pos)
    if verbose:
        print("  原本 %d 行 -> ひらがな読み %d 組" % (scanned, len(best)))
    kept = trim(best, pos, args.limit, verbose)
    matrix = load_connection_matrix(args.src, pos, verbose)
    build(kept, pos, matrix, args.out, verbose)
    return 0


if __name__ == "__main__":
    sys.exit(main())
