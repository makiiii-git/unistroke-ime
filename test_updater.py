#!/usr/bin/env python3
"""拡張辞書の配布マニフェストと、Kotlin 側の受け入れ条件が食い違っていないか。

辞書の差し替えは「落とす -> 検証 -> リネーム」で行うが、検証の基準
（schema / formatVersion / サイズ / SHA-256）はマニフェストと Kotlin の
両方に書かれている。片方だけ直すと、正しい辞書を拒否したり、逆に
読めない辞書を受け入れたりする。ここで突き合わせる。
"""

import hashlib
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "unistroke", "ime")
MANIFEST = os.path.join(ROOT, "dictionary", "manifest.json")
HEADER_SIZE = 80
MAGIC = b"UNIDIC2\x00"

FAILURES = []


def check(cond, msg):
    print("  %s %s" % ("ok  " if cond else "FAIL", msg))
    if not cond:
        FAILURES.append(msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def kt_int(src, name):
    m = re.search(r"const val %s = ([0-9_]+)" % name, src)
    return int(m.group(1).replace("_", "")) if m else None


def kt_str(src, name):
    m = re.search(r'const val %s =\s*\n?\s*"([^"]+)"' % name, src)
    return m.group(1) if m else None


def main() -> int:
    if not os.path.exists(MANIFEST):
        print("FAIL: マニフェストが無い: %s" % MANIFEST)
        print("      python3 tools/make_manifest.py --version 1 で作ること")
        return 1

    updater = read(os.path.join(KT, "DictionaryUpdater.kt"))
    manifest = json.loads(read(MANIFEST))

    print("== 1. マニフェストの必須項目 ==")
    for key in ("schema", "dictVersion", "formatVersion", "words",
                "size", "sha256", "url", "minAppVersionCode"):
        check(key in manifest, "%s がある" % key)

    print("== 2. Kotlin の受け入れ条件と一致 ==")
    check(manifest["schema"] == kt_int(updater, "SUPPORTED_SCHEMA"),
          "schema %s == SUPPORTED_SCHEMA %s"
          % (manifest["schema"], kt_int(updater, "SUPPORTED_SCHEMA")))
    check(manifest["formatVersion"] == kt_int(updater, "SUPPORTED_FORMAT"),
          "formatVersion %s == SUPPORTED_FORMAT %s"
          % (manifest["formatVersion"], kt_int(updater, "SUPPORTED_FORMAT")))

    print("== 3. 平文 HTTP へ誘導していない ==")
    check(manifest["url"].startswith("https://"), "url が https")
    check(kt_str(updater, "MANIFEST_URL", ).startswith("https://")
          if kt_str(updater, "MANIFEST_URL") else False,
          "MANIFEST_URL が https")

    print("== 4. サイズ上限に収まる ==")
    max_bytes = 32 * 1024 * 1024
    check(0 < manifest["size"] <= max_bytes,
          "size %d が上限 %d 以内" % (manifest["size"], max_bytes))

    print("== 5. 実物との突き合わせ ==")
    # dist/ は生成物なので CI には無い。あるときだけ照合する。
    dic = os.path.join(ROOT, "dist", "ondevice-ext.dic")
    if not os.path.exists(dic):
        print("  --   dist/ondevice-ext.dic が無いので実物照合は省略")
    else:
        size = os.path.getsize(dic)
        check(size == manifest["size"],
              "実サイズ %d == マニフェスト %d" % (size, manifest["size"]))
        h = hashlib.sha256()
        with open(dic, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                h.update(chunk)
        check(h.hexdigest() == manifest["sha256"], "SHA-256 が一致")
        with open(dic, "rb") as f:
            head = f.read(HEADER_SIZE)
        check(head[:8] == MAGIC, "マジックが UNIDIC2")
        fmt = struct.unpack_from("<I", head, 64)[0]
        check(fmt == manifest["formatVersion"],
              "辞書の形式版 %d == マニフェスト %d" % (fmt, manifest["formatVersion"]))
        words = struct.unpack_from("<I", head, 12)[0]
        check(words == manifest["words"],
              "語数 %d == マニフェスト %d" % (words, manifest["words"]))

    print("== 6. 拡張辞書はコア辞書より語彙が多い ==")
    core = os.path.join(ROOT, "app", "src", "main", "assets", "ondevice.dic")
    if os.path.exists(core):
        with open(core, "rb") as f:
            core_words = struct.unpack_from("<I", f.read(HEADER_SIZE), 12)[0]
        check(manifest["words"] > core_words,
              "拡張 %d > コア %d" % (manifest["words"], core_words))

    print("== 7. 差し替えは一時ファイル経由（本番を直接書かない） ==")
    check(".part" in updater, "一時ファイル（.part）へ落としている")
    check("renameTo" in updater, "renameTo で入れ替えている")
    check("MessageDigest" in updater, "ハッシュを計算している")
    check("isReadableDictionary" in updater, "辞書として開けるか確かめている")

    if FAILURES:
        print("\nFAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - %s" % f)
        return 1
    print("\ntest_updater: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
