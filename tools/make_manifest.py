#!/usr/bin/env python3
"""拡張辞書の配布用マニフェストを作る。

    python3 tools/make_manifest.py --dict dist/ondevice-ext.dic --version 1

生成物は dictionary/manifest.json。**これはリポジトリにコミットする**
（アプリは raw.githubusercontent.com から読む）。辞書本体は大きいので
GitHub Releases のアセットとして上げ、その URL をここに書く。

サイズ・SHA-256・語数・形式版はすべて実物から読むので、手で書き写さない。
アプリ側は落としたものが宣言どおりか（サイズ・ハッシュ・辞書として開けるか）を
必ず確かめてから入れ替える。
"""

import argparse
import hashlib
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DICT = os.path.join(ROOT, "dist", "ondevice-ext.dic")
DEFAULT_OUT = os.path.join(ROOT, "dictionary", "manifest.json")

MAGIC = b"UNIDIC2\x00"
HEADER_SIZE = 80

# DictionaryUpdater.SUPPORTED_SCHEMA と合わせること
SCHEMA = 1

DEFAULT_URL_TEMPLATE = (
    "https://github.com/makiiii-git/unistroke-ime/releases/download/"
    "dict-v{version}/ondevice-ext.dic"
)


def read_header(path):
    """辞書の先頭から語数と形式版を読む。"""
    with open(path, "rb") as f:
        head = f.read(HEADER_SIZE)
    if len(head) < HEADER_SIZE or head[:8] != MAGIC:
        raise SystemExit("辞書ではない（マジックが違う）: %s" % path)
    # 0:magic(8) 8:keyCount 12:wordCount ... 64:formatVersion
    word_count = struct.unpack_from("<I", head, 12)[0]
    format_version = struct.unpack_from("<I", head, 64)[0]
    return word_count, format_version


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict", default=DEFAULT_DICT, help="配布する辞書ファイル")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--version", type=int, required=True,
                    help="辞書の版。中身を変えたら必ず上げる")
    ap.add_argument("--url", default=None,
                    help="辞書本体の URL（既定は Releases の dict-v<版>）")
    ap.add_argument("--min-app", type=int, default=1,
                    help="この辞書を扱える最小の versionCode")
    args = ap.parse_args()

    if not os.path.exists(args.dict):
        raise SystemExit(
            "辞書が無い: %s\n  python3 tools/build_dictionary.py --limit 220000 "
            "--out dist/ondevice-ext.dic で作る" % args.dict)

    words, format_version = read_header(args.dict)
    size = os.path.getsize(args.dict)
    url = args.url or DEFAULT_URL_TEMPLATE.format(version=args.version)

    if not url.startswith("https://"):
        raise SystemExit("URL は https でなければならない: %s" % url)

    manifest = {
        "schema": SCHEMA,
        "dictVersion": args.version,
        "formatVersion": format_version,
        "words": words,
        "size": size,
        "sha256": sha256(args.dict),
        "url": url,
        "minAppVersionCode": args.min_app,
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("書き出した: %s" % args.out)
    for k in ("dictVersion", "formatVersion", "words", "size", "sha256", "url"):
        print("  %-15s %s" % (k, manifest[k]))
    print()
    print("次の手順:")
    print("  1. GitHub Releases に tag dict-v%d を作り、%s をアセットとして上げる"
          % (args.version, os.path.basename(args.dict)))
    print("  2. dictionary/manifest.json をコミットして push する")
    return 0


if __name__ == "__main__":
    sys.exit(main())
