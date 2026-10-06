#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""日付候補（「きょう」-> 今日の日付）のテスト。

  1. 書式の一覧（DateCandidates.formats の Python 版と、Kotlin 側のテンプレート文字列の一致）
  2. 和暦の境目（令和元年・平成 31 年・平成元年・それ以前は出さない）
  3. 文節編集モードへの差し込み（先頭候補は動かさない・重複は足さない・他の文節は触らない）
  4. 履歴に積まない判定（日付を含む確定は覚えない）
  5. Kotlin 側の配線（候補バー・文節変換・履歴記録の 3 か所）
"""

from __future__ import annotations

import os
import re
import sys

from unistroke_model import PKG, read

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def eq(got, want, msg):
    check(got == want, "%s  (got %r, want %r)" % (msg, got, want) if got != want else msg)


DATE_SRC = read("DateCandidates.kt")
IME_SRC = read("UniStrokeIME.kt")
PRED_SRC = read("PredictionEngine.kt")

# ----------------------------------------------------------------- Python 版

WEEKDAYS = "日月火水木金土"


def era(y, m, d):
    ymd = y * 10000 + m * 100 + d
    if ymd >= 20190501:
        name, first = "令和", 2019
    elif ymd >= 19890108:
        name, first = "平成", 1989
    else:
        return None
    n = y - first + 1
    return "%s%s年%d月%d日" % (name, "元" if n == 1 else str(n), m, d)


def formats(y, m, d, dow):
    """dow は java.util.Calendar.DAY_OF_WEEK（日曜 = 1 … 土曜 = 7）。"""
    out = [
        "%04d/%02d/%02d" % (y, m, d),
        "%d年%d月%d日" % (y, m, d),
        "%d月%d日" % (m, d),
    ]
    if 1 <= dow <= 7:
        out.append("%d月%d日(%s)" % (m, d, WEEKDAYS[dow - 1]))
    out.append("%04d-%02d-%02d" % (y, m, d))
    e = era(y, m, d)
    if e:
        out.append(e)
    return out


def insert_into(segments, dates_of):
    """segments は (読み, [候補]) のリスト。dates_of(読み) が空なら触らない。"""
    if not any(dates_of(r) for r, _ in segments):
        return segments
    out = []
    for reading, cands in segments:
        dates = dates_of(reading)
        if not dates:
            out.append((reading, cands))
            continue
        merged = cands[:1]
        for d in dates:
            if d not in merged:
                merged.append(d)
        for c in cands[1:]:
            if c not in merged:
                merged.append(c)
        out.append((reading, merged))
    return out


def main() -> int:
    print("\n=== 1. 書式の一覧 ===")
    # 2026-10-06 は火曜（Calendar.TUESDAY = 3）
    today = formats(2026, 10, 6, 3)
    eq(today, ["2026/10/06", "2026年10月6日", "10月6日", "10月6日(火)", "2026-10-06", "令和8年10月6日"],
       "きょう -> 6 書式（スラッシュ・年月日・月日・曜日付き・ISO・和暦）の順")
    eq(today[0], "2026/10/06", "スラッシュ形は月日をゼロ詰め")
    eq(today[1], "2026年10月6日", "年月日形はゼロ詰めしない")
    eq(formats(2026, 1, 5, 2)[:2], ["2026/01/05", "2026年1月5日"], "1 桁の月日: 01/05 と 1月5日")
    eq(formats(2026, 1, 4, 1)[3], "1月4日(日)", "Calendar.SUNDAY = 1 は「日」")
    eq(formats(2026, 1, 10, 7)[3], "1月10日(土)", "Calendar.SATURDAY = 7 は「土」")
    check(len(set(today)) == len(today), "書式に重複が無い")

    # Kotlin 側のテンプレートが Python 版と同じ並びで書かれているか
    body = DATE_SRC[DATE_SRC.find("fun formats("):]
    body = body[:body.find("return out")]
    templates = [
        '"$year/$mm/$dd"',
        '"${year}年${month}月${day}日"',
        '"${month}月${day}日"',
        '"${month}月${day}日(${WEEKDAYS[dayOfWeek - 1]})"',
        '"$year-$mm-$dd"',
        "era(year, month, day)?.let { out.add(it) }",
    ]
    pos = [body.find(t) for t in templates]
    check(all(p >= 0 for p in pos), "Kotlin の formats に 6 書式のテンプレートがすべてある")
    check(pos == sorted(pos), "Kotlin の formats の並びが Python 版と同じ")
    check('WEEKDAYS = "日月火水木金土"' in DATE_SRC, "曜日の一字は日曜始まり")
    check(".format(" not in DATE_SRC, "String.format を使わない（端末ロケールで数字が変わる）")
    check('"きょう" to 0' in DATE_SRC, "読み「きょう」は今日（日数差 0）")

    print("\n=== 2. 和暦の境目 ===")
    eq(era(2019, 5, 1), "令和元年5月1日", "2019-05-01 は令和元年")
    eq(era(2019, 4, 30), "平成31年4月30日", "2019-04-30 は平成 31 年")
    eq(era(2020, 1, 1), "令和2年1月1日", "2020 年は令和 2 年")
    eq(era(2026, 10, 6), "令和8年10月6日", "2026 年は令和 8 年")
    eq(era(1989, 1, 8), "平成元年1月8日", "1989-01-08 は平成元年")
    eq(era(1989, 1, 7), None, "1989-01-07 以前は和暦を出さない")
    eq(len(formats(1980, 1, 1, 3)), 5, "和暦が無い日付は 5 書式")
    check("20190501" in DATE_SRC and "19890108" in DATE_SRC, "Kotlin 側も同じ境目の定数")
    check('if (n == 1) "元"' in DATE_SRC, "Kotlin 側も 1 年目は「元年」")

    print("\n=== 3. 文節編集モードへの差し込み ===")
    dates_of = lambda r: today if r == "きょう" else []
    segs = [("きょう", ["今日", "京", "教"]), ("は", ["は", "葉"])]
    got = insert_into(segs, dates_of)
    eq(got[0][1], ["今日"] + today + ["京", "教"], "「きょう」の文節: 先頭はそのまま、直後に日付、残りは後ろへ")
    eq(got[1], ("は", ["は", "葉"]), "他の文節は触らない")
    eq(insert_into([("あした", ["明日"])], dates_of), [("あした", ["明日"])], "該当しない読みだけならそのまま")
    dup = insert_into([("きょう", ["今日", "10月6日", "京"])], dates_of)
    eq(dup[0][1].count("10月6日"), 1, "辞書側に同じ表記があっても重複させない")
    eq(insert_into([("きょう", [])], dates_of)[0][1], today, "候補が空の文節には日付だけが入る")
    k = DATE_SRC[DATE_SRC.find("fun insertInto("):]
    check("if (seg.candidates.isNotEmpty()) merged.add(seg.candidates[0])" in k
          and "for (i in 1 until seg.candidates.size)" in k,
          "Kotlin 側も先頭候補を動かさずに差し込む")

    print("\n=== 4. 履歴に積まない判定 ===")
    def contains_generated(surface):
        return bool(surface) and any(d in surface for d in today)
    check(contains_generated("2026/10/06"), "日付そのものは覚えない")
    check(contains_generated("2026/10/06は"), "「きょうは」->「2026/10/06は」も覚えない（包含で判定）")
    check(not contains_generated("今日"), "「今日」は覚える")
    check(not contains_generated(""), "空文字は対象外")
    check("if (surface.contains(d)) return true" in DATE_SRC, "Kotlin 側も包含で判定")

    print("\n=== 5. Kotlin 側の配線 ===")
    check("enum class Source { HISTORY, DATE, DICTIONARY, ONDEVICE, CONVERSION, SUGGEST, RAW }" in PRED_SRC,
          "Source に DATE がある（履歴の直後）")
    rec = IME_SRC[IME_SRC.find("private fun recordHistory("):][:400]
    guard = rec.find("if (DateCandidates.containsGenerated(surface, now)) return")
    record = rec.find("prediction.record(reading, surface, now)")
    check(0 <= guard < record, "recordHistory は日付を含む確定を record の前で弾く")
    rebuild = IME_SRC[IME_SRC.find("private fun rebuildCandidates()"):]
    rebuild = rebuild[:rebuild.find("// 2') 端末内辞書")]
    p_local0 = rebuild.find("if (local.isNotEmpty()) add(local[0])")
    p_dates = rebuild.find("for (d in dates) add(PredictionEngine.Candidate(reading, d, PredictionEngine.Source.DATE))")
    p_rest = rebuild.find("for (i in 1 until local.size) add(local[i])")
    check("val dates = DateCandidates.forReading(reading, now)" in rebuild, "候補バーは DateCandidates.forReading を使う")
    check(0 <= p_local0 < p_dates < p_rest, "候補バーの並び: 先頭の予測 -> 日付 -> 残りの予測")
    check("segments = DateCandidates.insertInto(result)" in IME_SRC, "文節変換の入口（startConversion）で差し込む")
    check(re.search(r"val OFFSETS: Map<String, Int> = mapOf\(", DATE_SRC) is not None,
          "読みの表はひとつ（あした・きのう を足す場所）")

    print()
    if FAILURES:
        print("FAIL: %d 件" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("PASS: 日付候補")
    return 0


if __name__ == "__main__":
    sys.exit(main())
