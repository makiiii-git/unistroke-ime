#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""頻度付きの英単語リストから、英語版の予測に使うバイナリ辞書を作る。

    python3 tools/build_english_dictionary.py --fetch
    python3 tools/build_english_dictionary.py --src <en_wordlist.combined.gz>

生成物は app/src/main/assets/english.dic（既定）。
フォーマットの詳細は tools/README.md と EnglishDictionary.kt を参照。

元データ（AOSP LatinIME・Apache-2.0）:
  dictionaries/en_wordlist.combined.gz
      1 行 1 語の「combined」形式。
          word=the,f=222,flags=,originalFreq=222
      f は 0〜255 の頻度（対数スケール。大きいほどよく使う）。
      possibly_offensive=true / not_a_word=true の語は予測に出さないので落とす。

鍵（辞書を引く綴り）は英小文字だけにする。アポストロフィは落とし、アクセント記号は
外す（don't -> dont / café -> cafe）。一筆書きでは記号が 2 ストロークかかるので、
英字だけ書けば記号つきの語が候補に出るようにするため。数字・ハイフン・ピリオドを
含む語は英字だけでは辿れないので載せない。
"""

from __future__ import annotations

import argparse
import base64
import gzip
import os
import struct
import sys
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "english.dic")
DEFAULT_SRC = os.path.join(ROOT, "tools", "aosp-src", "en_wordlist.combined.gz")

# gitiles は ?format=TEXT を付けると中身を base64 で返す
AOSP_BASE = ("https://android.googlesource.com/platform/packages/inputmethods/LatinIME/"
             "+/refs/heads/main/")
WORDLIST_URL = AOSP_BASE + "dictionaries/en_wordlist.combined.gz?format=TEXT"
NOTICE_URL = AOSP_BASE + "NOTICE?format=TEXT"

MAGIC = b"UNIENG1\x00"
HEADER_SIZE = 32
FORMAT_VERSION = 1

# 鍵の最大長。EnglishDictionary.MAX_KEY_LENGTH と一致させること。
MAX_KEY_LENGTH = 48

TAB = b"\t"
APOSTROPHES = "'’"


# ---------------------------------------------------------------- 鍵と表記

def fold(word: str) -> str:
    """アクセント記号を外す（é -> e）。分解できない字はそのまま残る。"""
    decomposed = unicodedata.normalize("NFD", word)
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch))


def key_of(word: str) -> str | None:
    """表記 -> 鍵。英字とアポストロフィ以外を含む語は None（載せない）。"""
    out = []
    for ch in fold(word):
        if "a" <= ch <= "z":
            out.append(ch)
        elif "A" <= ch <= "Z":
            out.append(ch.lower())
        elif ch in APOSTROPHES:
            continue
        else:
            return None
    key = "".join(out)
    if not key or len(key) > MAX_KEY_LENGTH:
        return None
    return key


# ------------------------------------------------------------------ 読み込み

def parse_combined(lines):
    """combined 形式の行から (表記, 頻度) を取り出す。"""
    for raw in lines:
        line = raw.strip()
        if not line.startswith("word="):
            continue  # ヘッダ行・bigram・shortcut
        fields = {}
        for part in line.split(","):
            if "=" in part:
                name, value = part.split("=", 1)
                fields[name] = value
        word = fields.get("word", "")
        if not word:
            continue
        if fields.get("possibly_offensive") == "true" or fields.get("not_a_word") == "true":
            continue
        try:
            freq = int(fields.get("f", "0"))
        except ValueError:
            continue
        yield word, freq


def load_entries(src: str, min_freq: int):
    """元データを読み、(鍵, 表記) ごとに最も高い頻度を残す。"""
    opener = gzip.open if src.endswith(".gz") else open
    best = {}
    total = 0
    with opener(src, "rt", encoding="utf-8") as f:
        for word, freq in parse_combined(f):
            total += 1
            if freq < min_freq:
                continue
            key = key_of(word)
            if key is None:
                continue
            freq = max(0, min(255, freq))
            pair = (key, word)
            if freq > best.get(pair, -1):
                best[pair] = freq
    return best, total


def trim(best: dict, limit: int):
    """頻度の高い順に limit 件まで残す。"""
    items = sorted(best.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1]))
    return items[:limit]


# -------------------------------------------------------------------- 書き出し

def build(items, out_path: str) -> dict:
    """[((鍵, 表記), 頻度), ...] をバイナリへ書く。

    並びは鍵の昇順、同じ鍵の中では頻度の高い順（EnglishDictionary.complete の前提）。
    """
    entries = sorted(items, key=lambda kv: (kv[0][0], -kv[1], kv[0][1]))
    blob = bytearray()
    offsets = []
    freqs = bytearray()
    for (key, word), freq in entries:
        offsets.append(len(blob))
        blob += key.encode("ascii")
        if word != key:
            blob += TAB + word.encode("utf-8")
        freqs.append(freq)
    offsets.append(len(blob))

    count = len(entries)
    offset_table_off = HEADER_SIZE
    freq_table_off = offset_table_off + 4 * (count + 1)
    blob_off = freq_table_off + count

    header = MAGIC + struct.pack(
        "<6I", FORMAT_VERSION, count, offset_table_off, freq_table_off, blob_off, len(blob))
    assert len(header) == HEADER_SIZE

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(header)
        f.write(struct.pack("<%dI" % (count + 1), *offsets))
        f.write(bytes(freqs))
        f.write(bytes(blob))
    return {
        "words": count,
        "bytes": blob_off + len(blob),
        "with_display": sum(1 for (key, word), _ in entries if word != key),
    }


# ---------------------------------------------------------------------- 取得

def _fetch_base64(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return base64.b64decode(r.read())


def fetch(dest: str) -> None:
    """AOSP から単語リストとライセンス表示（NOTICE）を落とす。あるものは落とし直さない。"""
    folder = os.path.dirname(dest)
    os.makedirs(folder, exist_ok=True)
    if not os.path.exists(dest):
        print("fetch  %s" % WORDLIST_URL)
        data = _fetch_base64(WORDLIST_URL)
        with open(dest, "wb") as f:
            f.write(data)
    notice = os.path.join(folder, "NOTICE")
    if not os.path.exists(notice):
        print("fetch  %s" % NOTICE_URL)
        with open(notice, "wb") as f:
            f.write(_fetch_base64(NOTICE_URL))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC,
                    help="combined 形式の単語リスト（.gz 可）")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fetch", action="store_true", help="単語リストが無ければ AOSP から落とす")
    ap.add_argument("--min-freq", type=int, default=50,
                    help="これ未満の頻度の語は落とす。サイズはほぼこれで決まる")
    ap.add_argument("--limit", type=int, default=200000,
                    help="残す語数の上限（頻度の足切りのあとに効く安全弁）")
    ap.add_argument("-q", "--quiet", action="store_true")
    args = ap.parse_args()

    if args.fetch:
        fetch(args.src)
    if not os.path.exists(args.src):
        print("単語リストが無い: %s（--fetch で取得できる）" % args.src, file=sys.stderr)
        return 1

    best, total = load_entries(args.src, args.min_freq)
    items = trim(best, args.limit)
    stats = build(items, args.out)
    if not args.quiet:
        print("元データ %d 語 -> 採用 %d 語（うち表記つき %d 語）"
              % (total, stats["words"], stats["with_display"]))
        print("%s  %.2f MB" % (os.path.relpath(args.out, ROOT), stats["bytes"] / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
