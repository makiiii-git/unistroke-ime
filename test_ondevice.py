#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""オンデバイス変換エンジンの検証。

  1. コスト定数が Kotlin（OnDeviceConverter.kt）と Python（ondevice_model.py）で一致
  2. 品詞クラスの番号が Kotlin / Python / ビルダの 3 者で一致
  3. 変換品質のスモークテスト（20 文）
  4. 文節分割・候補リストの形（候補バーが期待する構造）
  5. 前方一致予測
  6. 変換にかかる時間の目安
  7. IME 側の統合（フォールバック・チップ・設定）が結線されているか

ondevice_model.py は OnDeviceConverter.kt / OnDeviceDictionary.kt の Python 移植。
この 2 つが同じロジックであることを 1〜2 で担保したうえで、
実際の変換品質を Python 側で測る。
"""

from __future__ import annotations

import os
import re
import sys
import time

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


# ------------------------------------------------------------------ スモーク

# (読み, 期待する変換). 完全一致しなくても FAIL にはせず、
# 「完全一致の数」と「文字単位の一致率」が下限を割ったら FAIL にする。
# 端末内変換はネット変換が使えないときのバックオフなので、
# 100 点ではなく「実用に耐えるか」を見る。
SMOKE = [
    ("きょうはいいてんきですね", "今日はいい天気ですね"),
    ("へんかんえんじんをつくる", "変換エンジンを作る"),
    ("わたしのなまえはたなかです", "私の名前は田中です"),
    ("あしたのかいぎはじゅうじからです", "明日の会議は十時からです"),
    ("にほんごにゅうりょくをためす", "日本語入力を試す"),
    ("でんしゃがおくれています", "電車が遅れています"),
    ("ありがとうございます", "ありがとうございます"),
    ("よろしくおねがいします", "よろしくお願いします"),
    ("このもんだいはむずかしい", "この問題は難しい"),
    ("かんじへんかんのせいどをあげたい", "漢字変換の精度を上げたい"),
    ("あたらしいけいたいをかいました", "新しい携帯を買いました"),
    ("かいしゃにいってきます", "会社に行ってきます"),
    ("ごはんをたべにいきましょう", "ご飯を食べに行きましょう"),
    ("にほんのぶんかがすきです", "日本の文化が好きです"),
    ("めーるをおくりました", "メールを送りました"),
    ("じかんがないのでいそぎます", "時間がないので急ぎます"),
    ("らいしゅうのよていをきめる", "来週の予定を決める"),
    ("こどもたちがこうえんであそぶ", "子供たちが公園で遊ぶ"),
    ("せんせいにしつもんをしました", "先生に質問をしました"),
    ("でんわばんごうをおしえてください", "電話番号を教えてください"),
]

MIN_EXACT = 6          # 20 文中これだけは完全一致してほしい
MIN_CHAR_RATIO = 0.78  # 文字単位の一致率の下限

# 「これだけは絶対に外さない」語。助詞と基本的な語彙。
MUST_CONTAIN = [
    ("きょうはいいてんきですね", ["今日", "天気", "です"]),
    ("にほんごにゅうりょくをためす", ["日本語", "入力"]),
    ("でんわばんごうをおしえてください", ["電話番号", "教え"]),
    ("じかんがないのでいそぎます", ["時間", "急"]),
    ("へんかんえんじんをつくる", ["変換", "エンジン"]),
]


def char_ratio(got: str, want: str) -> float:
    import difflib
    sm = difflib.SequenceMatcher(None, got, want)
    return sum(b.size for b in sm.get_matching_blocks()) / max(1, len(want))


# ------------------------------------------------------------------ 定数一致


def kotlin_int_consts(src: str) -> dict[str, int]:
    out = {}
    for m in re.finditer(r"const val ([A-Z_]+) = (-?\d+)", src):
        out[m.group(1)] = int(m.group(2))
    return out


def python_int_consts(mod, names) -> dict[str, int]:
    return {n: getattr(mod, n) for n in names if isinstance(getattr(mod, n, None), int)}


SHARED_COST_NAMES = [
    "WORD_PENALTY", "UNKNOWN_BASE", "UNKNOWN_PER_CHAR", "UNKNOWN_MAX_LEN",
    "SHORT_CONTENT_PENALTY", "PROPER_PENALTY", "UNKNOWN_CONNECTION",
    "MAX_ALTERNATIVES",
    # 前方一致予測のスコア
    "PREDICT_LENGTH_COST", "PREDICT_CONTEXT_PERCENT", "PREDICT_PROPER_PENALTY",
    "PREDICT_BOUND_PENALTY", "PREDICT_NONFINAL_PENALTY", "PREDICT_NUMBER_PENALTY",
    "PREDICT_PREFIX_PENALTY", "PREDICT_MAX_SCORE", "PREDICT_SCAN_WORDS",
    "MIN_PREDICT_PREFIX", "MIN_PREDICT_READING",
]

POS_NAMES = [
    "POS_OTHER", "POS_NOUN", "POS_PROPER", "POS_VERB", "POS_ADJ", "POS_ADVERB",
    "POS_PARTICLE", "POS_AUX", "POS_PREFIX", "POS_SUFFIX", "POS_ADNOMINAL",
    "POS_CONJUNCTION", "POS_INTERJECTION", "POS_NUMBER", "POS_SYMBOL",
]

FLAG_NAMES = ["FLAG_NONFINAL", "FLAG_FINAL", "FLAG_INDEPENDENT"]


def main() -> int:
    if not os.path.exists(M.DIC_PATH):
        print("FAIL: 辞書が無い: %s" % M.DIC_PATH)
        print("      python3 tools/build_dictionary.py --fetch  で作ること")
        return 1

    kt = read(os.path.join(KT, "OnDeviceConverter.kt"))
    kt_consts = kotlin_int_consts(kt)
    builder = read(os.path.join(ROOT, "tools", "build_dictionary.py"))

    print("== 1. コスト定数が Kotlin と Python で一致 ==")
    for name in SHARED_COST_NAMES:
        kv = kt_consts.get(name)
        pv = getattr(M, name, None)
        check(kv is not None and kv == pv,
              "%s: Kotlin %s == Python %s" % (name, kv, pv))
    m = re.search(r'const val PREDICT_U_ROW = "([^"]+)"', kt)
    check(m is not None and m.group(1) == M.PREDICT_U_ROW,
          "PREDICT_U_ROW（動詞基本形の末尾）が Kotlin と Python で一致")
    check("PREDICT_SCAN_KEYS" not in kt,
          "辞書順の先頭だけをなめる旧実装（PREDICT_SCAN_KEYS）が残っていない")

    print("== 2. 品詞クラスが 3 者で一致 ==")
    for name in POS_NAMES + FLAG_NAMES:
        kv = kt_consts.get(name)
        pv = getattr(M, name, None)
        bm = re.search(r"^%s = (.+)$" % name, builder, re.M)
        bv = None
        if bm:
            bv = eval(bm.group(1), {"__builtins__": {}}, {})
        check(kv is not None and kv == pv == bv,
              "%s: Kotlin %s / Python %s / builder %s" % (name, kv, pv, bv))

    conv = M.shared()

    print("== 3. 変換品質のスモークテスト ==")
    exact = 0
    ratios = []
    for reading, want in SMOKE:
        got = conv.convert_text(reading)
        ok = got == want
        exact += ok
        ratios.append(char_ratio(got, want))
        print("       %s %s -> %s%s" %
              ("OK" if ok else "--", reading, got, "" if ok else "   期待: " + want))
    avg = sum(ratios) / len(ratios)
    check(exact >= MIN_EXACT,
          "完全一致 %d / %d 文（下限 %d）" % (exact, len(SMOKE), MIN_EXACT))
    check(avg >= MIN_CHAR_RATIO,
          "文字単位の一致率 %.3f（下限 %.2f）" % (avg, MIN_CHAR_RATIO))

    for reading, parts in MUST_CONTAIN:
        got = conv.convert_text(reading)
        missing = [p for p in parts if p not in got]
        check(not missing,
              "%s に %s が出る%s" %
              (reading, "・".join(parts),
               "" if not missing else "（欠け: %s / 実際: %s）" % (missing, got)))

    print("== 4. 文節分割と候補の形 ==")
    segs = conv.convert("きょうはいいてんきですね")
    check(len(segs) >= 3, "「きょうはいいてんきですね」が %d 文節に切れる" % len(segs))
    check("".join(s.reading for s in segs) == "きょうはいいてんきですね",
          "文節の読みを繋ぐと元の読みに戻る")
    check(all(s.candidates for s in segs), "全文節に候補が 1 つ以上ある")
    check(all(s.reading in s.candidates for s in segs),
          "全文節の候補にひらがな（読みそのもの）が入っている")
    check(all(M.to_katakana(s.reading) in s.candidates for s in segs),
          "全文節の候補にカタカナが入っている")
    check(all(len(set(s.candidates)) == len(s.candidates) for s in segs),
          "候補に重複が無い")
    check(all(len(s.candidates) <= M.MAX_ALTERNATIVES + 2 for s in segs),
          "候補数が MAX_ALTERNATIVES + 2 以内")
    # 空の読みは空のリスト（IME 側が length 0 で呼んでも落ちない）
    check(conv.convert("") == [], "空の読みは空のリストを返す")
    # 辞書に無いかな列でも必ず何か返す
    odd = conv.convert("ぬぬぬぬぬぬぬ")
    check(odd and "".join(s.reading for s in odd) == "ぬぬぬぬぬぬぬ",
          "辞書に無いかな列でも読みを保ったまま返す")

    print("== 5. 前方一致予測 ==")
    for prefix in ("か", "おは", "あり", "よろ", "でんわ"):
        got = conv.predict(prefix, 10)
        check(len(got) > 0, "「%s」に予測が出る（%d 件）" % (prefix, len(got)))
        check(all(r.startswith(prefix) for r, _ in got),
              "「%s」の予測がすべて前方一致" % prefix)
        check(all(len(r) >= M.MIN_PREDICT_READING for r, _ in got),
              "「%s」の予測に 1 文字の読みが混ざらない" % prefix)
        check(len({s for _, s in got}) == len(got),
              "「%s」の予測に重複が無い" % prefix)
    check(conv.predict("", 8) == [], "空の接頭辞では予測を出さない")
    check(len(conv.predict("か", 10)) >= 5, "1 文字目から予測が出る（「か」）")

    def surfaces(prefix, n=10):
        return [s for _, s in conv.predict(prefix, n)]

    # 完全一致の語も含める（変換の往復を待たずに出る）
    check(("でんわ", "電話") in conv.predict("でんわ", 10),
          "「でんわ」に完全一致の「電話」が出る")
    check("電話番号" in surfaces("でんわ"), "「でんわ」に補完した「電話番号」も出る")
    # 接頭辞の範囲を全部なめる（辞書順の後ろにある「かいしゃ」が出る）
    check("会社" in surfaces("かい", 10)[:6], "「かい」の上位に「会社」が出る")
    # 並びの質。挨拶・言い切りの形が先に来て、活用途中の形と命令形が沈む
    check(surfaces("おは")[0] == "おはよう", "「おは」の先頭は「おはよう」")
    check(surfaces("すみ")[0] == "すみません", "「すみ」の先頭は「すみません」")
    check(surfaces("ごめ")[0] == "ごめん", "「ごめ」の先頭は「ごめん」")
    check("ありがとう" in surfaces("あり", 5), "「あり」の上位に「ありがとう」（連用ゴザイ接続の例外）")
    check(surfaces("たべ")[0] == "食べる", "「たべ」の先頭は基本形の「食べる」")
    check("食べろ" not in surfaces("たべ", 3) and "食べよ" not in surfaces("たべ", 3),
          "「たべ」の上位 3 件に命令形が来ない")
    check(surfaces("でき")[0] in ("できる", "出来る"), "「でき」の先頭は「できる」")
    check(any(s in ("ください", "下さい") for s in surfaces("くだ", 5)),
          "「くだ」の上位に「ください」（イで終わる命令形の例外）")
    check("ございます" in surfaces("ござ", 10), "「ござ」に「ございます」が出る")
    # 語固有の文脈 ID のコストが語コストへ繰り込まれている
    check("抵抗" not in surfaces("て", 3), "「て」の上位に「抵抗」が来ない")
    check("顔" not in surfaces("か", 3), "「か」の上位に「顔」（かお）が来ない")
    check("澗" not in surfaces("か", 10), "「か」に大数の「澗」が出ない")
    check(all(sc <= M.PREDICT_MAX_SCORE for sc, _, _ in conv.predict_scored("に", 20)),
          "スコアが PREDICT_MAX_SCORE を超える候補は出さない")

    print("== 6. 速度の目安 ==")
    long_reading = "でんわばんごうをおしえてくださいませんか"
    t0 = time.time()
    for _ in range(20):
        conv.convert(long_reading)
    ms = (time.time() - t0) * 1000 / 20
    # Python 版は Kotlin より 1 桁遅い。ここは「暴走していない」ことの確認。
    check(ms < 200, "%d 文字の変換が Python で %.1f ms（200 ms 未満）" %
          (len(long_reading), ms))
    print("       ※ Kotlin 実装は IntArray ベースでこれより大幅に速い")

    t0 = time.time()
    for _ in range(5):
        conv.predict("お", 10)
    ms = (time.time() - t0) * 1000 / 5
    check(ms < 300, "最大の 1 文字接頭辞「お」の予測が Python で %.1f ms（300 ms 未満）" % ms)

    print("== 7. IME 側の結線 ==")
    ime = read(os.path.join(KT, "UniStrokeIME.kt"))
    check("onDevice?.predict(reading, ON_DEVICE_PREDICTIONS)" in ime,
          "候補バーの組み立てで端末内辞書の予測を引いている")
    check("if (!networkConvertOk) {\n            onDevice?.predict" not in ime,
          "端末内辞書の予測をネット変換の有無で止めていない")
    check("if (segs.size > 1) {" in ime and
          ime.index("if (segs.size > 1) {") < ime.index("onDevice?.predict(reading, ON_DEVICE_PREDICTIONS)"),
          "2 文節以上に切れた読み（句）では変換の最良経路を予測より先に出す")
    ime_consts = kotlin_int_consts(ime)
    check(ime_consts.get("ON_DEVICE_PREDICTIONS", 0) >= 8,
          "端末内辞書の予測を 8 件以上取る（実際 %s）" % ime_consts.get("ON_DEVICE_PREDICTIONS"))
    check(ime_consts.get("MAX_LIVE_CANDIDATES", 0) >= ime_consts.get("ON_DEVICE_PREDICTIONS", 0) + 5,
          "候補バーの上限に予測と変換候補の両方が収まる")
    check("OnDeviceConverter.get(this)" in ime, "IME が端末内エンジンを取得する")
    check("requestOnDeviceSuggest" in ime, "通信できないとき端末内で予測変換する")
    check(ime.count("requestOnDeviceSuggest") >= 3,
          "オフライン時と通信失敗時の両方から呼ばれる")
    check("view.onDeviceChip = candidatesFromOnDevice" in ime,
          "候補バーの「端末内」チップを結線している")
    check("端末内" in ime, "文節編集中の「端末内」表示がある")
    check("!onDeviceOnly" in ime, "「端末内のみ」設定が通信の可否に効く")
    check("onDevice?.shutdown()" in ime, "IME 終了時にエンジンを片付ける")
    check("prediction.record(reading, surface, now)" in ime,
          "recordHistory が PredictionEngine へ委譲している（自己再帰でない）")

    view = read(os.path.join(KT, "UniStrokeView.kt"))
    check("var onDeviceChip" in view, "候補バーにチップのプロパティがある")
    check('LABEL_ON_DEVICE = "端末内"' in view, "チップの文字が定義されている")
    check("chipWidth()" in view, "チップぶんの幅を候補の折り返しに反映している")

    prefs = read(os.path.join(KT, "Prefs.kt"))
    check("KEY_CONVERT_ENGINE" in prefs and "ENGINE_ONDEVICE" in prefs,
          "変換エンジンの設定キーがある")
    settings = read(os.path.join(KT, "SettingsActivity.kt"))
    check("R.id.engine_ondevice" in settings, "設定画面にエンジン選択がある")
    check("LicenseActivity::class.java" in settings, "設定画面からライセンスを開ける")
    lic = read(os.path.join(KT, "LicenseActivity.kt"))
    check("Copyright 2010-2018, Google Inc." in lic, "Mozc の著作権表示がある")
    check("THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS" in lic,
          "BSD-3-Clause の免責事項がある")
    check("Neither the name of Google Inc." in lic, "BSD-3 の第 3 条がある")

    gradle = read(os.path.join(ROOT, "app", "build.gradle.kts"))
    check('noCompress += "dic"' in gradle,
          "辞書を無圧縮にして mmap できるようにしている")

    print()
    if FAILURES:
        print("test_ondevice: %d FAIL" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_ondevice: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
