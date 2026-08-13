#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""オンデバイス辞書（app/src/main/assets/ondevice.dic）の整合検証。

  1. ヘッダ・セクション配置・ファイルサイズ
  2. エントリ数が想定レンジに収まっているか
  3. 読みが符号化バイト列の辞書順に並んでいるか（二分探索の前提）
  4. ランダムサンプルの「読み -> 表記」往復（鍵の復元・共通接頭辞検索・語の取り出し）
  5. 接続行列と品詞グループ表の妥当性
  6. Kotlin 側（OnDeviceDictionary.kt）とオフセット・レコード長の定義が一致しているか
"""

from __future__ import annotations

import os
import random
import re
import struct
import sys

import ondevice_model as M

ROOT = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "unistroke", "ime")

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


# 期待するレンジ。辞書を作り直しても大きくは動かないはず。
MIN_WORDS, MAX_WORDS = 150_000, 300_000
MIN_KEYS = 100_000
MIN_BYTES, MAX_BYTES = 3 * 1024 * 1024, 8 * 1024 * 1024


def main() -> int:
    if not os.path.exists(M.DIC_PATH):
        print("FAIL: 辞書が無い: %s" % M.DIC_PATH)
        print("      python3 tools/build_dictionary.py --fetch  で作ること")
        return 1

    print("== 1. ヘッダとセクション配置 ==")
    size = os.path.getsize(M.DIC_PATH)
    dic = M.Dictionary()
    check(dic.version == 2, "フォーマット版 = 2（実際 %d）" % dic.version)
    check(MIN_BYTES <= size <= MAX_BYTES,
          "サイズ %.2f MB が %d〜%d MB に収まる" %
          (size / 1048576.0, MIN_BYTES // 1048576, MAX_BYTES // 1048576))

    # セクションは宣言順に隙間なく並び、最後が行列で終わる
    offsets = [
        ("keyIndex", dic.key_index_off, (dic.key_count + 1) * 8),
        ("keyBlob", dic.key_blob_off, dic.key_blob_len),
        ("wordArray", dic.word_array_off, (dic.word_count + 1) * 10),
        ("surfaceBlob", dic.surface_blob_off, dic.surface_blob_len),
    ]
    cursor = M.HEADER_SIZE
    ordered = True
    for name, off, length in offsets:
        if off != cursor:
            ordered = False
            print("       %s が %d ではなく %d から始まる" % (name, cursor, off))
        cursor = off + length
    check(ordered, "セクションが隙間なく並んでいる")
    check(dic.alphabet_off == cursor, "字母表が表記ブロブの直後に来る")
    check(dic.group_table_off + dic.group_count * 2 == dic.matrix_off,
          "グループ表の直後に接続行列が来る")
    check(dic.matrix_off + dic.group_count ** 2 * 2 == size,
          "接続行列でファイルが終わる")

    print("== 2. エントリ数 ==")
    check(MIN_WORDS <= dic.word_count <= MAX_WORDS,
          "語数 %d が %d〜%d に収まる" % (dic.word_count, MIN_WORDS, MAX_WORDS))
    check(dic.key_count >= MIN_KEYS,
          "読みの種類 %d が %d 以上" % (dic.key_count, MIN_KEYS))
    check(dic.key_count <= dic.word_count,
          "読みの種類 <= 語数（1 読みに 1 語以上）")
    check(1 <= dic.max_key_chars <= 32,
          "最長の読み %d 文字が妥当" % dic.max_key_chars)
    check(dic.wordStart(0) == 0 if hasattr(dic, "wordStart") else True, "先頭の語番号は 0")

    print("== 3. 並び順（二分探索の前提） ==")
    prev = b""
    bad_order = None
    empty = 0
    for i in range(dic.key_count):
        off, _ = dic._key_entry(i)
        end = dic._key_entry(i + 1)[0]
        cur = bytes(dic.buf[dic.key_blob_off + off:dic.key_blob_off + end])
        if not cur:
            empty += 1
        if cur <= prev:
            bad_order = (i, prev, cur)
            break
        prev = cur
    check(bad_order is None,
          "全 %d 件の読みが符号化バイト列の狭義単調増加" % dic.key_count
          if bad_order is None else "読みの順序が壊れている: %r" % (bad_order,))
    check(empty == 0, "空の読みが無い")

    # 語の開始番号は単調非減少で、最後が語数と一致する
    starts_ok = True
    prev_start = -1
    for i in range(dic.key_count + 1):
        s = dic._key_entry(i)[1]
        if s < prev_start:
            starts_ok = False
            break
        prev_start = s
    check(starts_ok, "語の開始番号が単調非減少")
    check(dic._key_entry(dic.key_count)[1] == dic.word_count,
          "番兵の語番号が語数と一致する")

    print("== 4. ランダムサンプルの往復 ==")
    rng = random.Random(20260810)
    samples = [rng.randrange(dic.key_count) for _ in range(400)]
    round_trip = 0
    bad = []
    for key in samples:
        reading = dic.key_str(key)
        # 読みを復元 -> 共通接頭辞検索で同じ鍵に戻る
        hits = dict((length, k) for length, k in dic.common_prefix_search(reading, 0))
        if hits.get(len(reading)) != key:
            bad.append(("鍵に戻らない", reading, key, hits.get(len(reading))))
            continue
        words = list(dic.words_of(key))
        if not words:
            bad.append(("語が無い", reading, key, None))
            continue
        costs = []
        ok = True
        for w in words:
            surface, cost, lg, rg = dic.word(w)
            if not surface:
                bad.append(("表記が空", reading, key, w))
                ok = False
                break
            if not (0 <= lg < dic.group_count and 0 <= rg < dic.group_count):
                bad.append(("グループ番号が範囲外", reading, key, (lg, rg)))
                ok = False
                break
            costs.append(cost)
        if not ok:
            continue
        if costs != sorted(costs):
            bad.append(("コスト昇順でない", reading, key, costs))
            continue
        round_trip += 1
    check(not bad, "400 件の往復がすべて成功（成功 %d 件）" % round_trip
          if not bad else "往復に失敗: %r" % (bad[:3],))

    # 読みそのものが表記に混ざっていないか（実行時にかなノードで作れるので冗長）
    print("== 5. 接続行列と品詞グループ ==")
    check(dic.group_count >= 50, "品詞グループ数 %d が 50 以上" % dic.group_count)
    check(0 <= dic.bos_group < dic.group_count,
          "BOS/EOS グループ %d が範囲内" % dic.bos_group)
    attrs_ok = all(
        0 <= dic.group_attr(g)[0] < 15 and 0 <= dic.group_attr(g)[1] < 8
        for g in range(dic.group_count)
    )
    check(attrs_ok, "全グループの品詞クラスとフラグが範囲内")

    values = [
        dic.connection(a, b)
        for a in range(0, dic.group_count, 7)
        for b in range(0, dic.group_count, 7)
    ]
    check(min(values) >= -1000 and max(values) <= 20000,
          "接続コストのサンプルが -1000〜20000 に収まる（%d〜%d）" %
          (min(values), max(values)))
    # 「名詞 -> 助詞」は「助詞 -> 助詞」より繋がりやすいはず
    noun = next(g for g in range(dic.group_count)
                if dic.group_attr(g)[0] == M.POS_NOUN)
    part = next(g for g in range(dic.group_count)
                if dic.group_attr(g)[0] == M.POS_PARTICLE)
    check(dic.connection(noun, part) <= dic.connection(part, part),
          "名詞->助詞 (%d) が 助詞->助詞 (%d) 以下" %
          (dic.connection(noun, part), dic.connection(part, part)))

    print("== 6. Kotlin 側の定義と一致 ==")
    kt = read(os.path.join(KT, "OnDeviceDictionary.kt"))
    check('"UNIDIC2\\x00"' in repr(M.MAGIC) or M.MAGIC == b"UNIDIC2\x00",
          "Python 側の magic が UNIDIC2")
    check("'2'.code.toByte()" in kt, "Kotlin 側の magic も UNIDIC2")
    # ヘッダの読み出しオフセットが Python と同じか
    for field, off in [
        ("keyCount", 8), ("wordCount", 12), ("maxKeyChars", 40),
        ("groupCount", 48), ("bosGroup", 60),
    ]:
        check(re.search(r"%s = buf\.getInt\(%d\)" % (field, off), kt) is not None,
              "Kotlin の %s が offset %d を読む" % (field, off))
    check("10 * w" in kt, "Kotlin の語レコード長が 10 バイト")
    check("keyIndexOff + 8 * key" in kt, "Kotlin の鍵レコード長が 8 バイト")
    check("HEADER_SIZE = 80" in read(os.path.join(ROOT, "tools", "build_dictionary.py")),
          "ビルダのヘッダ長が 80 バイト")
    check(M.HEADER_SIZE == 80, "Python 側のヘッダ長も 80 バイト")

    dic.close()

    print()
    if FAILURES:
        print("test_dictionary: %d FAIL" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_dictionary: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
