#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打った綴りの英字候補（「かいしゃ」-> kaisha / Kaisha / KAISHA）のテスト。

  1. 表記の一覧（LatinCandidates.forRaw の Python 版と、Kotlin 側の並びの一致）
  2. 文節編集モードへの差し込み（1 文節のときだけ・末尾へ・先頭候補は動かさない）
  3. Kotlin 側の配線（候補バー・履歴・文節変換の 3 か所）
"""

from __future__ import annotations

import sys

from unistroke_model import Romaji, read

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def eq(got, want, msg):
    check(got == want, "%s  (got %r, want %r)" % (msg, got, want) if got != want else msg)


LATIN_SRC = read("LatinCandidates.kt")
IME_SRC = read("UniStrokeIME.kt")

# ----------------------------------------------------------------- Python 版


def is_letter(c):
    return "a" <= c <= "z" or "A" <= c <= "Z"


def is_spelling(s):
    return any(is_letter(c) for c in s) and all(is_letter(c) or c == "-" for c in s)


def for_raw(raw):
    if not is_spelling(raw):
        return []
    lower = raw.lower()
    out = []
    for f in (raw, lower[:1].upper() + lower[1:], raw.upper()):
        if f not in out:
            out.append(f)
    return out


def insert_into(segments, raw, raw_reading):
    if len(segments) != 1:
        return segments
    reading, cands = segments[0]
    if reading != raw_reading:
        return segments
    merged = list(cands)
    for f in for_raw(raw):
        if f not in merged:
            merged.append(f)
    return [(reading, merged)]


def main() -> int:
    print("=== 1. 表記の一覧 ===")
    eq(for_raw("kaisha"), ["kaisha", "Kaisha", "KAISHA"], "打ったまま -> 先頭だけ大文字 -> 全部大文字")
    eq(for_raw("a"), ["a", "A"], "1 文字は重複を除いて 2 つ")
    eq(for_raw("ra-menn"), ["ra-menn", "Ra-menn", "RA-MENN"], "長音の - は綴りに含める")
    eq(for_raw(""), [], "空の綴りからは作らない")
    eq(for_raw("ka、"), [], "記号が混じった合成は対象にしない")
    eq(for_raw("-"), [], "英字を含まないものは対象にしない")
    forms = LATIN_SRC[LATIN_SRC.find("val forms = listOf("):]
    forms = forms[:forms.find(")\n        val out")]
    p_raw = forms.find("raw,")
    p_cap = forms.find("lower.replaceFirstChar { it.uppercaseChar() }")
    p_up = forms.find("raw.uppercase(Locale.ROOT)")
    check(0 <= p_raw < p_cap < p_up, "Kotlin 側も同じ並び")
    check("lowercase(Locale.ROOT)" in LATIN_SRC,
          "大文字・小文字は端末の言語に左右されない（トルコ語の i 問題）")

    print("\n=== 2. 文節編集モードへの差し込み ===")
    raw = "kaisha"
    one = insert_into([("かいしゃ", ["会社", "解消"])], raw, Romaji.flush(raw))
    eq(one[0][1], ["会社", "解消", "kaisha", "Kaisha", "KAISHA"], "1 文節なら末尾に足す（先頭候補は動かさない）")
    two = [("きょう", ["今日"]), ("は", ["は"])]
    check(insert_into(two, "kyouha", Romaji.flush("kyouha")) is two, "2 文節以上に切れたら足さない")
    stale = [("かいしゃ", ["会社"])]
    check(insert_into(stale, "ka", Romaji.flush("ka")) is stale, "綴りと読みが対応しないときは足さない")
    dup = insert_into([("かいしゃ", ["会社", "kaisha"])], raw, Romaji.flush(raw))
    eq(dup[0][1].count("kaisha"), 1, "辞書側に同じ表記があっても重複させない")
    k = LATIN_SRC[LATIN_SRC.find("fun insertInto("):]
    check("if (segments.size != 1) return segments" in k and "if (seg.reading != rawReading) return segments" in k,
          "Kotlin 側も 1 文節・読み一致のときだけ足す")
    check(k.find("merged.addAll(seg.candidates)") < k.find("for (f in forms) if (f !in merged) merged.add(f)"),
          "Kotlin 側も末尾へ足す")

    print("\n=== 3. Kotlin 側の配線 ===")
    rebuild = IME_SRC[IME_SRC.find("private fun rebuildCandidates()"):]
    rebuild = rebuild[:rebuild.find("private fun readingOf(")]
    p_hira = rebuild.find("addRaw(reading)")
    p_kata = rebuild.find("addRaw(RomajiConverter.toKatakana(reading))")
    p_latin = rebuild.find("for (latin in LatinCandidates.forRaw(wordRaw.toString())) addRaw(latin)")
    check(0 <= p_hira < p_kata < p_latin, "候補バーの末尾: ひらがな -> カタカナ -> 英字")
    guard = rebuild.find("if (rawMatchesComposing()) {")
    check(0 <= guard < p_latin, "綴りが今の合成と対応しているときだけ英字を出す")
    add_raw = rebuild[rebuild.find("fun addRaw("):p_hira]
    check("MAX_LIVE_CANDIDATES" not in add_raw, "かな・カナ・英字は候補数の上限で押し出されない")
    auto = rebuild[:rebuild.find("val reading = readingForConversion()")]
    check("LatinCandidates" not in auto, "自動英字化中の候補（綴り / かな解釈）は変えていない")

    commit = IME_SRC[IME_SRC.find("private fun commitLiveCandidate("):]
    commit = commit[:commit.find("private fun requestConversion()")]
    p_guard = commit.find("if (!spelling || romaji.isEmpty()) {")
    p_rec = commit.find("recordHistory(candidate.reading, text, System.currentTimeMillis())")
    p_clear = commit.find("romaji.setLength(0)", p_rec)
    check(0 <= p_guard < p_rec < p_clear,
          "未確定の子音が残る綴り（kaik）は履歴に覚えない（未確定を捨てる前に判定する）")

    start = IME_SRC[IME_SRC.find("private fun startConversion("):][:700]
    p_date = start.find("segments = DateCandidates.insertInto(result)")
    p_ins = start.find("segments = LatinCandidates.insertInto(segments, raw, RomajiConverter.flush(raw))")
    check(0 <= p_date < p_ins, "文節変換の入口で、日付のあとに英字を差し込む")

    print()
    if FAILURES:
        print("FAIL: %d 件" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("PASS: 英字候補")
    return 0


if __name__ == "__main__":
    sys.exit(main())
