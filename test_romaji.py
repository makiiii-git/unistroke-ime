#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RomajiConverter.kt のテーブルをそのまま使ったローマ字 -> かな変換のテスト。"""

from __future__ import annotations

import sys

from unistroke_model import Romaji

FAILURES = []


def eq(got, want, msg):
    if got == want:
        print("  ok   %s" % msg)
    else:
        print("  FAIL %s : got %r want %r" % (msg, got, want))
        FAILURES.append(msg)


CONVERT_CASES = [
    # (入力, 確定かな, 未確定)
    ("a", "あ", ""),
    ("ka", "か", ""),
    ("k", "", "k"),
    ("ky", "", "ky"),
    ("kya", "きゃ", ""),
    ("shi", "し", ""),
    ("si", "し", ""),
    ("chi", "ち", ""),
    ("ti", "ち", ""),
    ("tsu", "つ", ""),
    ("tu", "つ", ""),
    ("kka", "っか", ""),
    ("kk", "っ", "k"),
    ("nn", "", "nn"),
    ("nna", "んな", ""),
    ("konnichiha", "こんにちは", ""),
    ("kinnyoubi", "きんようび", ""),
    ("zennbu", "ぜんぶ", ""),
    ("kanji", "かんじ", ""),
    ("annai", "あんない", ""),
    # --- 「ん」は nn。単独 n は未確定のまま持ち越す ---
    ("n", "", "n"),
    ("na", "な", ""),
    ("kon", "こ", "n"),
    ("kond", "こん", "d"),
    ("kondo", "こんど", ""),
    ("minna", "みんな", ""),
    ("shinnbun", "しんぶ", "n"),
    ("nnn", "ん", "n"),
    ("nnna", "んな", ""),
    ("xtsu", "っ", ""),
    ("ltu", "っ", ""),
    ("-", "ー", ""),
    ("wo", "を", ""),
    ("n'", "ん", ""),
    # 誤認識で不正な子音列が来ても詰まらない（先頭 1 文字を英字のまま確定して前進）
    ("kta", "kた", ""),
    ("qwer", "qうぇ", "r"),
]

# 末尾に単独 n が残ったら Latin の n として確定する（「ん」は nn だけ）
FLUSH_CASES = [
    ("n", "n"),
    ("nn", "ん"),
    ("nnn", "んn"),
    ("nnnn", "んん"),
    ("k", "k"),
    ("kan", "かn"),
    ("kann", "かん"),
    ("kanji", "かんじ"),
    ("kondo", "こんど"),
    ("", ""),
]

# 合成中の表示（未確定バッファ -> 画面に出す文字）
PREVIEW_CASES = [
    ("n", "n"),
    ("nn", "ん"),
    ("k", "k"),
    ("ky", "ky"),
    ("", ""),
]

# 1 文字ずつ書いていったときの composing 表示（かな確定分 + preview）
TYPING_CASES = [
    ("kondo", ["k", "こ", "こn", "こんd", "こんど"]),
    ("kanji", ["k", "か", "かn", "かんj", "かんじ"]),
    ("konnichiha", ["k", "こ", "こn", "こん", "こんに", "こんにc",
                    "こんにch", "こんにち", "こんにちh", "こんにちは"]),
    ("minna", ["m", "み", "みn", "みん", "みんな"]),
    ("nan", ["n", "な", "なn"]),
]


def main():
    print("=== convert() ===")
    for buf, kana, pending in CONVERT_CASES:
        eq(Romaji.convert(buf), (kana, pending), "convert(%r)" % buf)

    print("\n=== flush()（末尾の単独 n は Latin の n） ===")
    for buf, want in FLUSH_CASES:
        eq(Romaji.flush(buf), want, "flush(%r)" % buf)

    print("\n=== preview()（合成中の表示） ===")
    for pending, want in PREVIEW_CASES:
        eq(Romaji.preview(pending), want, "preview(%r)" % pending)

    print("\n=== 1 文字ずつ書いたときの composing 表示 ===")
    for word, want in TYPING_CASES:
        kana = ""
        pending = ""
        got = []
        for ch in word:
            k, pending = Romaji.convert(pending + ch)
            kana += k
            got.append(kana + Romaji.preview(pending))
        eq(got, want, "%r を 1 文字ずつ -> %s" % (word, " / ".join(got)))

    print("\n=== 自動アルファベット化の判定（日本語らしさ）===")
    # 日本語のローマ字。ここで発動したら実害が大きいので 1 件も許さない。
    JAPANESE = [
        "kyou", "konnichiha", "gakkou", "kansha", "issho", "shinbun", "tsukue",
        "nihongo", "watashi", "ryokou", "jyugyou", "happyou", "chotto", "matte",
        "hyaku", "kippu", "zenbu", "sensei", "denwa", "arigatou", "ohayou",
        "ganbatte", "nn", "kan", "fujisan", "sakura", "toukyou", "shashin",
        "gakkousei", "kyouju", "tsutsuji", "hikkoshi", "annnai", "shinnkansenn",
        # ストローク 1 個ぶんの誤認識。回復処理 1 回では発動しない（従来どおり）
        "kta", "ktaa", "kaki", "sute",
    ]
    fired = [w for w in JAPANESE if Romaji.looks_non_japanese(w)]
    eq(fired, [], "日本語 %d 語で誤発動しない" % len(JAPANESE))

    # 明確に英単語。多くで発動してほしい（全部でなくてよい ―― 控えめ側に倒す設計）
    ENGLISH = [
        "strike", "night", "through", "script", "strength", "world", "project",
        "chrome", "screen", "string", "graph", "light", "thought", "sprint",
        "scratch", "twelfth", "str", "ght", "spl", "browser", "stack",
    ]
    hit = [w for w in ENGLISH if Romaji.looks_non_japanese(w)]
    eq(len(hit) >= len(ENGLISH) * 3 // 4, True,
       "英単語 %d 語中 %d 語で発動する（3/4 以上）" % (len(ENGLISH), len(hit)))

    print("\n=== 発動は綴りが伸びるにつれて起きる（途中で戻らない）===")
    # "strike": st では発動せず、str で発動する
    eq(Romaji.looks_non_japanese("s"), False, "s では発動しない")
    eq(Romaji.looks_non_japanese("st"), False, "st では発動しない（回復 1 回）")
    eq(Romaji.looks_non_japanese("str"), True, "str で発動する（回復 2 回）")
    # 単調性: 一度発動した綴りは、文字を足しても発動したまま
    bad_mono = []
    for w in ENGLISH:
        seen = False
        for i in range(1, len(w) + 1):
            f = Romaji.looks_non_japanese(w[:i])
            if seen and not f:
                bad_mono.append((w, w[:i]))
                break
            seen = seen or f
    eq(bad_mono, [], "発動後に文字を足しても解除されない（%d 語で確認）" % len(ENGLISH))

    print("\n=== かな解釈へ戻せる（巻き戻し）===")
    # 自動英字化しても、生の綴りからかな解釈をいつでも作り直せる
    for w in ("strike", "night", "str"):
        kana = Romaji.flush(w)
        eq(len(kana) > 0, True, "%r のかな解釈が作れる -> %s" % (w, kana))

    print("\n=== 未確定バッファが無限に伸びない ===")
    worst = 0
    for buf in ("kkkkkkkk", "xtsxtsxts", "zzzzzz", "nnnnnn", "abcdefghij"):
        _, pending = Romaji.convert(buf)
        worst = max(worst, len(pending))
    eq(worst < Romaji.MAX_KEY, True, "未確定は常に MAX_KEY(%d) 未満（最大 %d）" % (Romaji.MAX_KEY, worst))

    print("\n=== 全テーブルキーが往復する ===")
    bad = []
    for key, kana in sorted(Romaji.TABLE.items()):
        got, pending = Romaji.convert(key)
        if pending or got != kana:
            bad.append((key, kana, got, pending))
    eq(bad, [], "TABLE の %d キーがすべてそのまま確定する" % len(Romaji.TABLE))

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_romaji: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
