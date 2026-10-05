#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""英語版（言語の切り替え・英単語の予測・IME の英語分岐）のテスト。

  1. 言語の決定（設定と端末の言語から 日本語版 / 英語版 を選ぶ）
  2. 文字列の規則（鍵・大文字小文字・単語削除・音声の空白）
  3. 辞書バイナリの読み書き（ビルドスクリプトで小さな辞書を作って引く）
  4. 予測（別表記 -> 履歴 -> 辞書の並び、履歴の覚え方）
  5. IME のシーケンス（合成・候補の確定・記号・自動大文字・単語削除）
  6. Kotlin 側の配線
  7. 同梱の英語辞書（app/src/main/assets/english.dic）の整合と予測の品質
  8. 英語のボイスコマンド
"""

from __future__ import annotations

import importlib.util
import os
import re
import sys
import tempfile

import english_model as M
from english_model import (BACKSPACE, RETURN, SHIFT, SPACE, TAP, Dictionary, EnglishIME,
                           Predictor, Text)
from unistroke_model import ROOT, VoiceCmd

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def eq(got, want, msg):
    check(got == want, "%s  (got %r, want %r)" % (msg, got, want) if got != want else msg)


def load_builder():
    path = os.path.join(ROOT, "tools", "build_english_dictionary.py")
    spec = importlib.util.spec_from_file_location("build_english_dictionary", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


B = load_builder()

# 検証用の小さな辞書。頻度は AOSP の単語リストと同じ 0〜255 の目盛り。
FIXTURE = [
    ("a", 220), ("an", 200), ("and", 219), ("the", 222), ("they", 180), ("them", 170),
    ("then", 168), ("there", 165), ("their", 160), ("these", 150), ("theory", 95),
    ("theater", 90), ("to", 215), ("I", 210), ("I'm", 150), ("important", 140),
    ("image", 120), ("don't", 150), ("done", 140), ("donut", 60), ("its", 160),
    ("it's", 165), ("itself", 110), ("London", 100), ("long", 150), ("lonely", 80),
    ("hello", 120), ("help", 140), ("held", 100), ("hell", 90), ("he'll", 95),
    ("US", 100), ("us", 150), ("use", 170), ("used", 160), ("café", 50), ("NASA", 70),
    ("we", 200), ("well", 150), ("we'll", 120), ("will", 190), ("Will", 60),
    ("ok", 130), ("see", 150), ("word", 150), ("world", 160), ("work", 170),
    ("PR", 100), ("present", 150), ("press", 120), ("price", 90),
]


def build_fixture(folder):
    items = [((B.key_of(w), w), f) for w, f in FIXTURE]
    path = os.path.join(folder, "fixture.dic")
    B.build(items, path)
    return Dictionary(path)


def words(hits):
    return [h[0] for h in hits]


def main() -> int:
    print("=== 1. 言語の決定 ===")
    eq(M.resolve_language("auto", "ja"), "ja", "端末が日本語なら日本語版")
    eq(M.resolve_language("auto", "en"), "en", "端末が英語なら英語版")
    eq(M.resolve_language("auto", "fr"), "en", "それ以外の言語の端末は英語版")
    eq(M.resolve_language("en", "ja"), "en", "日本語の端末でも英語版を選べる")
    eq(M.resolve_language("ja", "en"), "ja", "英語の端末でも日本語版を選べる")
    lang = M.SRC["AppLanguage"]
    check("Prefs.LANG_JA -> Prefs.LANG_JA" in lang and "Prefs.LANG_EN -> Prefs.LANG_EN" in lang
          and "if (systemLanguage == Prefs.LANG_JA) Prefs.LANG_JA else Prefs.LANG_EN" in lang,
          "AppLanguage.resolve が同じ規則")
    check("Resources.getSystem()" in lang,
          "端末の言語はシステムのリソースから読む（アプリ側の差し替えに影響されない）")
    check(re.search(r"val config = Configuration\(\)\s+config\.setLocale", lang) is not None,
          "差し替えるのは言語だけ（画面サイズなどの構成を固定しない）")
    prefs = M.SRC["Prefs"]
    check("getString(KEY_LANGUAGE, LANG_AUTO)" in prefs, "言語の既定は「端末に合わせる」")
    check("getBoolean(KEY_EN_AUTO_CAP, true)" in prefs, "自動大文字の既定はオン")
    check("getBoolean(KEY_EN_PREDICT, true)" in prefs, "単語の予測の既定はオン")

    print("\n=== 2. 文字列の規則 ===")
    eq(Text.key("dont"), "dont", "鍵: 小文字はそのまま")
    eq(Text.key("Don't"), "dont", "鍵: 大文字は小文字へ、アポストロフィは落とす")
    eq(Text.key("I’m"), "im", "鍵: 曲がったアポストロフィも落とす")
    eq(Text.key("mp3"), "", "鍵: 数字が混じったら引かない")
    eq(Text.key("café"), "", "鍵: アクセント付きの字が混じったら引かない")
    eq(Text.apply_case("hello", "he"), "hello", "小文字で書けば辞書の表記のまま")
    eq(Text.apply_case("London", "lon"), "London", "固有名詞は辞書の大文字を保つ")
    eq(Text.apply_case("hello", "He"), "Hello", "先頭が大文字なら候補も先頭を大文字に")
    eq(Text.apply_case("hello", "HE"), "HELLO", "CapsLock で書けば候補もすべて大文字")
    eq(Text.apply_case("I'm", "Im"), "I'm", "記号つきの語にも当てはまる")
    eq(Text.apply_case("important", "I"), "Important", "大文字 1 文字は「先頭だけ大文字」")
    check(Text.is_learnable("hello") and Text.is_learnable("don't"), "英字とアポストロフィは覚える")
    check(not Text.is_learnable("a") and not Text.is_learnable("mp3")
          and not Text.is_learnable("x" * 40), "1 文字・数字入り・長すぎる綴りは覚えない")
    eq(Text.word_delete_length("hello world"), 5, "単語削除: 直前の単語")
    eq(Text.word_delete_length("hello world  "), 7, "単語削除: 末尾の空白も一緒に")
    eq(Text.word_delete_length("hello, "), 2, "単語削除: 記号は記号の並びごと")
    eq(Text.word_delete_length("don't"), 5, "単語削除: アポストロフィは単語の一部")
    eq(Text.word_delete_length("line\n"), 1, "単語削除: 改行は 1 つだけ")
    eq(Text.word_delete_length("line\nnext"), 4, "単語削除: 行をまたがない")
    eq(Text.word_delete_length("   "), 3, "単語削除: 空白だけならすべて")
    eq(Text.word_delete_length(""), 0, "単語削除: 何も無ければ何もしない")
    check(Text.needs_space_before("d") and Text.needs_space_before("."),
          "音声: 文字や句読点の直後は空白を置く")
    check(not Text.needs_space_before(None) and not Text.needs_space_before(" ")
          and not Text.needs_space_before("\n") and not Text.needs_space_before("("),
          "音声: 行頭・空白・開き括弧の直後には置かない")

    with tempfile.TemporaryDirectory() as tmp:
        d = build_fixture(tmp)

        print("\n=== 3. 辞書バイナリ ===")
        eq(d.word_count, len(FIXTURE), "語数")
        eq(d.version, M.FORMAT_VERSION, "フォーマットの版が Kotlin と同じ")
        eq((B.HEADER_SIZE, B.FORMAT_VERSION, B.MAX_KEY_LENGTH, B.MAGIC),
           (M.HEADER_BYTES, M.FORMAT_VERSION, M.MAX_KEY_LENGTH, M.MAGIC),
           "ビルドスクリプトと EnglishDictionary.kt の定数が一致")
        kt_magic = re.search(r"private val MAGIC = byteArrayOf\((.*?)\n        \)",
                             M.SRC["EnglishDictionary"], re.S)
        eq("".join(re.findall(r"'(.)'\.code", kt_magic.group(1))), "UNIENG1", "Kotlin 側の magic")
        eq(d.complete("the", 5),
           [("the", 222, True), ("they", 180, False), ("them", 170, False),
            ("then", 168, False), ("there", 165, False)],
           "完全一致を先頭に、残りは頻度の高い順")
        eq(words(d.complete("its", 5)), ["it's", "its", "itself"], "同じ鍵の別表記は頻度順に並ぶ")
        eq(words(d.complete("im", 3)), ["I'm", "important", "image"], "im から I'm")
        eq(words(d.complete("dont", 3)), ["don't"], "dont から don't")
        eq(words(d.complete("cafe", 3)), ["café"], "アクセント付きの語も英字だけで引ける")
        eq(d.complete("zz", 5), [], "無い接頭辞は空")
        eq(d.complete("Th", 5), [], "鍵は小文字だけ（大文字は呼び出し側で鍵にしてから）")
        check(d.has_word("London") and not d.has_word("london"), "hasWord は大文字・小文字まで見る")
        check(d.has_word("don't") and not d.has_word("dont"), "hasWord は記号まで見る")
        eq(len(d.complete("t", 3)), 3, "件数の上限を守る")
        eq(words(d.complete("wor", 9)), ["work", "world", "word"], "頻度順（辞書順ではない）")

        print("\n=== 4. 予測 ===")
        p = Predictor(d)
        now = 10 * M.WEEK
        eq(p.predict("th", now)[:4], ["the", "they", "them", "then"], "th の補完")
        check("the" not in p.predict("the", now), "打ったままの綴りは候補にしない")
        eq(p.predict("im", now), ["I'm", "important", "image"], "im -> I'm が先頭")
        eq(p.predict("Im", now), ["I'm", "Important", "Image"], "文頭の Im でも I'm")
        eq(p.predict("its", now), ["it's", "itself"], "its には it's を先に出す")
        eq(p.predict("dont", now), ["don't"], "dont -> don't")
        eq(p.predict("TH", now)[:2], ["THE", "THEY"], "CapsLock なら候補も大文字")
        eq(p.predict("lon", now), ["long", "London", "lonely"], "固有名詞は大文字で出る")
        eq(p.predict("i", now)[0], "I", "i には I を先頭に出す")
        eq(p.predict("pr", now), ["present", "PR", "press", "price"],
           "大文字だけが違う別表記は先頭に固定しない（頻度に下駄 %d を足して並べる）" % M.EXACT_BONUS)
        eq(p.predict("well", now)[0], "we'll", "アポストロフィを補った別表記は必ず先頭")
        eq(p.predict("mp3", now), [], "数字入りの綴りは予測しない")
        eq(p.predict("", now), [], "空なら何も出さない")
        check(len(p.predict("t", now)) <= M.MAX_CANDIDATES, "候補数の上限")

        p.record("theater", now)
        eq(p.predict("th", now + 1)[0], "theater", "使った語は辞書の候補より先に出る")
        p.record("Makitouch", now)
        check("Makitouch" not in p.predict("mak", now + 1),
              "辞書に無い語は 1 回使っただけでは出さない（書き損じかもしれない）")
        p.record("Makitouch", now + 2)
        eq(p.predict("mak", now + 3), ["Makitouch"], "2 回使えば出る（名前など）")
        q = Predictor(d)
        q.record("Hello", now)
        q.record("HELLO", now)
        q.record("London", now)
        q.record("NASA", now)
        q.record("a", now)
        q.record("mp3", now)
        eq(sorted(q.history), ["London", "NASA", "hello"],
           "文頭・CapsLock の語は小文字で、固有名詞と略語はそのまま覚える")
        eq(q.history["hello"]["n"], 2, "同じ語は回数が増える")
        r = Predictor(d)
        for w in ("these", "theory", "theater", "them"):
            r.record(w, now)
        got = r.predict("the", now + 1)
        eq(len([w for w in got[:M.MAX_FROM_HISTORY + 1] if w in r.history]), M.MAX_FROM_HISTORY,
           "履歴から出すのは %d 件まで" % M.MAX_FROM_HISTORY)
        none = Predictor(None)
        none.record("hello", now)
        eq(none.predict("he", now + 1), ["hello"], "辞書が開けなくても履歴だけで予測する")

        print("\n=== 5. IME のシーケンス ===")

        def ime(**kw):
            return EnglishIME(Predictor(d), **kw)

        e = ime()
        eq(e.status(), "⇧", "文頭では自動でシフトが立つ")
        e.write("hello")
        eq((e.out, e.composing()), ("", "Hello"), "書いた文字は確定せず合成に溜まる（先頭は大文字）")
        e.stroke(SPACE)
        eq((e.out, e.composing()), ("Hello ", ""), "スペースで綴りのまま確定する")
        e.write("he")
        eq(e.candidates[:3], ["hello", "help", "held"],
           "合成中は候補が出る（さっき使った hello が先頭、続いて辞書の頻度順）")
        e.pick(1)
        eq(e.out, "Hello help ", "候補のタップで単語と空白が入る")
        e.punct(".")
        eq(e.out, "Hello help. ", "候補の直後の「.」は単語へ詰め、空白はうしろへ回る")
        eq(e.status(), "⇧", "文末のあとは次の文頭として大文字になる")
        e.write("its")
        eq(e.composing(), "Its", "文頭の 1 文字だけ大文字")
        eq(e.candidates[0], "It's", "候補も文頭の大文字に合わせる")

        e = ime(text="see ")
        e.write("wor").pick(0)
        eq(e.out, "see work ", "候補を確定")
        e.stroke(BACKSPACE)
        eq((e.out, e.composing()), ("see ", "wor"), "直後のバックスペースで打っていた綴りへ戻る")
        eq(e.candidates[:2], ["work", "world"], "戻したら候補も出し直す")
        e.stroke(BACKSPACE)
        eq(e.composing(), "wo", "2 回目からは 1 文字ずつ消える")

        e = ime(text="see ")
        e.write("wor").pick(1).stroke(SPACE).write("ok")
        eq(e.text(), "see world ok", "候補のあとのスペースは二重にならない")
        e = ime(text="see ")
        e.write("wor").pick(1).punct(",").write("ok")
        eq(e.text(), "see world, ok", "候補のあとの「,」も詰める")
        e = ime(text="see ")
        e.write("word ").punct(".")
        eq(e.out, "see word .", "自分で書いた空白は詰めない（確定で入れた空白だけ）")
        e = ime(text="see ")
        e.write("wor").pick(1).punct("(")
        eq(e.out, "see world (", "詰めるのは . , ! ? : ; だけ")

        e = ime(text="i ")
        e.write("don").punct("'").write("t")
        eq(e.composing(), "don't", "単語の途中のアポストロフィは綴りの一部")
        e.stroke(SPACE)
        eq(e.out, "i don't ", "そのまま確定できる")
        e = ime(text="i ")
        e.write("dont")
        eq(e.candidates, ["don't"], "記号を書かなくても候補に出る")
        e = ime(text="say ")
        e.punct("'")
        eq((e.out, e.composing()), ("say '", ""), "単語の外のアポストロフィはそのまま入る")

        e = ime()
        e.stroke(SHIFT)
        eq(e.status(), "", "自動で立った大文字はシフト 1 回で取り消せる")
        e.write("i")
        eq(e.composing(), "i", "取り消したら小文字で始まる")
        e = ime(text="go ")
        e.stroke(SHIFT).write("lon")
        eq(e.composing(), "Lon", "手動のシフトは次の 1 文字だけ大文字")
        e = ime(text="go ")
        e.stroke(SHIFT).stroke(SHIFT).write("nasa")
        eq((e.composing(), e.status()), ("NASA", "⇧⇧"), "シフト 2 回で CapsLock")
        e.stroke(SHIFT)
        eq(e.status(), "", "3 回目で解除")

        e = ime(text="see ")
        e.write("mp3")
        eq((e.out, e.composing()), ("see mp3", ""), "数字は単語を確定してそのまま入る")
        e = ime(text="see ")
        e.write("ok").stroke(RETURN)
        eq((e.out, e.composing(), e.enter_count), ("see ok", "", 1),
           "リターンは確定と改行を 1 回で済ませる")
        e = ime(text="see ")
        e.write("hi").punct("!")
        eq(e.out, "see hi!", "記号は書きかけの単語を確定してから入る")
        e = ime()
        e.write("hi").punct(".").stroke(SPACE).write("ok")
        eq(e.text(), "Hi. Ok", "文末のあとの空白で次の文頭が大文字になる")

        e = ime(predict_here=False, caps_field=False)
        e.write("abc")
        eq((e.out, e.composing(), e.candidates), ("abc", "", []),
           "予測を使わない欄では 1 文字ずつそのまま確定する")
        e = ime(caps_field=False)
        e.write("hi")
        eq(e.composing(), "hi", "入力欄が求めていなければ自動で大文字にしない")
        e = ime(auto_cap=False)
        e.write("hi")
        eq(e.composing(), "hi", "設定でオフなら自動で大文字にしない")

        e = ime(text="hello big world")
        e.delete_word()
        eq(e.out, "hello big ", "単語削除: 直前の単語")
        e.delete_word()
        eq(e.out, "hello ", "単語削除: 空白ごと次の単語")
        e.write("wor").delete_word()
        eq((e.out, e.composing(), e.candidates), ("hello ", "", []),
           "単語削除: 書きかけの単語は綴りごと捨てる")
        e = ime(text="One. ")
        e.delete_word()
        eq((e.out, e.status()), ("One", ""), "単語削除のあとは自動大文字も引き直す")

        pr = Predictor(d)
        e = EnglishIME(pr, text="by ")
        e.write("makitouch ").write("makitouch ")
        e.write("mak")
        eq(e.candidates, ["makitouch"], "確定した語を覚えて、次から候補に出す")
        pr = Predictor(d)
        e = EnglishIME(pr, text="by ", learning=False)
        e.write("makitouch ").write("makitouch ")
        eq(pr.history, {}, "学習が禁止された欄（シークレットタブなど）では覚えない")

    print("\n=== 6. Kotlin 側の配線 ===")
    ime_src = M.SRC["UniStrokeIME"]
    view_src = M.SRC["UniStrokeView"]
    check("inputMode = if (english || isLatinOnlyField(info)) InputMode.LATIN else savedMode()"
          in ime_src, "英語版は常に英字モードで開く")
    toggle = ime_src[ime_src.find("override fun onModeToggle()"):][:200]
    check("if (english) return" in toggle, "英語版ではモードトグルが効かない")
    check("override fun onDeleteWord()" in ime_src and "fun onDeleteWord() = Unit" in view_src,
          "単語削除がリスナー経由で繋がっている")
    check("EnglishText.wordDeleteLength(before)" in ime_src, "単語削除の長さは EnglishText が決める")
    check(view_src.count("listener?.onDeleteWord()") == 2,
          "左上のボタンは押下時と長押しリピートで単語削除を呼ぶ")
    check("UiTarget.BTN_MODE -> if (english)" in view_src, "英語版では離したときにトグルしない")
    word_ms = int(re.search(r"WORD_REPEAT_INTERVAL_MS = (\d+)L", view_src).group(1))
    cursor_ms = int(re.search(r" REPEAT_INTERVAL_MS = (\d+)L", view_src).group(1))
    check(word_ms >= 4 * cursor_ms,
          "単語削除のリピート %dms はカーソルの %dms よりずっと遅い（消しすぎない）"
          % (word_ms, cursor_ms))
    check("enPredictHere = english && Prefs.isEnglishPrediction(this) && allowsPrediction(info)"
          in ime_src, "予測は英語版・設定オン・許される欄の 3 つが揃ったときだけ")
    allow = ime_src[ime_src.find("private fun allowsPrediction"):][:400]
    check("isLatinOnlyField(info)" in allow and "TYPE_TEXT_FLAG_NO_SUGGESTIONS" in allow
          and "TYPE_NULL" in allow,
          "パスワード・メール・URL・数値の欄と、候補を断っている欄では予測しない")
    rec = ime_src[ime_src.find("private fun recordEnglish"):][:200]
    check("if (!learningAllowedHere) return" in rec, "学習が禁止された欄では英単語も覚えない")
    check('commitFinal(ic, "$word ", typed)' in ime_src,
          "候補の確定は「打っていた綴り」を戻す先として記録する")
    check("EnglishText.TIGHT_PUNCTUATION" in ime_src
          and 'ic.getTextBeforeCursor(1, 0)?.toString() == " "' in ime_src,
          "空白を詰める前に、直前が本当に空白か確かめる")
    check("ic.getCursorCapsMode(type)" in ime_src, "自動大文字は入力欄の指定に従う")
    check("override fun onUpdateSelection(" in ime_src and "abandonEnglishWord()" in ime_src,
          "カーソルが合成中の単語から離れたら手放す")
    check("VoiceCommands.match(text, english)" in ime_src, "ボイスコマンドは言語ごとの表で引く")
    check("AppLanguage.speechTag(context)" in M.SRC["VoiceInput"], "音声認識の言語がアプリの言語に従う")
    for name in ("EnglishDictionary", "EnglishPredictor", "EnglishText"):
        check("http" not in M.SRC[name] and "java.net" not in M.SRC[name],
              "%s は通信しない" % name)
    check("by lazy { EnglishPredictor.get(this) }" in ime_src,
          "英語辞書は英語版で使うときに初めて開く")
    manifest = open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()
    activities = re.findall(r'<activity\s+android:name="\.(\w+)"', manifest)
    pkg = os.path.join(ROOT, "app", "src", "main", "java", "com", "unistroke", "ime")
    plain = [a for a in activities
             if ": LocalizedActivity()" not in open(os.path.join(pkg, a + ".kt"), encoding="utf-8").read()]
    check(len(activities) >= 6 and not plain,
          "すべての画面がアプリの言語で表示される%s" % ("" if not plain else " -> " + ", ".join(plain)))
    check("super.attachBaseContext(AppLanguage.wrap(newBase))" in M.SRC["LocalizedActivity"],
          "画面の Context を言語で差し替えている")
    gradle = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check('noCompress += "dic"' in gradle, "英語辞書も無圧縮で同梱される（直接 mmap できる）")

    print("\n=== 7. 同梱の英語辞書 ===")
    if not os.path.exists(M.DIC_PATH):
        check(False, "英語辞書がある: %s（python3 tools/build_english_dictionary.py --fetch で作る）"
              % os.path.relpath(M.DIC_PATH, ROOT))
    else:
        d = Dictionary(M.DIC_PATH)
        size = os.path.getsize(M.DIC_PATH)
        eq(d.version, M.FORMAT_VERSION, "フォーマットの版")
        eq(d.blob_off + d.blob_len, size, "ヘッダの配置とファイルサイズが合う")
        check(30_000 <= d.word_count <= 200_000, "語数が想定の範囲（%d 語）" % d.word_count)
        check(size <= 3 * 1024 * 1024, "サイズが 3 MB 以下（%.2f MB）" % (size / 1048576.0))
        bad_order = 0
        bad_key = 0
        prev = (b"", 256)
        for i in range(d.word_count):
            k = d.key(i)
            f = d.frequency(i)
            if not k or any(not (97 <= c <= 122) for c in k) or len(k) > M.MAX_KEY_LENGTH:
                bad_key += 1
            if k < prev[0] or (k == prev[0] and f > prev[1]):
                bad_order += 1
            prev = (k, f)
        eq(bad_key, 0, "鍵はすべて英小文字で、長さの上限に収まる")
        eq(bad_order, 0, "鍵の昇順・同じ鍵は頻度の降順に並んでいる（二分探索の前提）")
        missing = [w for w in ("the", "and", "I", "I'm", "don't", "it's", "because",
                               "tomorrow", "London", "Monday") if not d.has_word(w)]
        check(not missing, "基本の語が入っている%s" % ("" if not missing else " -> " + ", ".join(missing)))
        p = Predictor(d)
        now = 10 * M.WEEK
        eq(p.predict("th", now)[0], "the", "th の先頭は the")
        eq(p.predict("dont", now)[0], "don't", "dont の先頭は don't")
        eq(p.predict("im", now)[0], "I'm", "im の先頭は I'm")
        eq(p.predict("i", now)[0], "I", "i の先頭は I")
        check("because" in p.predict("bec", now), "bec から because が出る")
        check("tomorrow" in p.predict("tom", now), "tom から tomorrow が出る")
        check("London" in p.predict("lond", now), "lond から London が出る")
        check("Thank" in p.predict("Tha", now) or "Thanks" in p.predict("Tha", now),
              "文頭の Tha から Thank / Thanks が出る")
        e = EnglishIME(Predictor(d))
        e.write("im")
        e.pick(0).write("goi")
        eq(e.out, "I'm ", "実辞書でも候補の確定が通る")
        check("going" in e.candidates, "goi から going が出る")

    print("\n=== 8. 英語のボイスコマンド ===")
    vc = M.SRC["VoiceCommands"]
    phrases_en = VoiceCmd._table(vc, "PHRASES_EN")
    table_en = {VoiceCmd.normalize(w): cmd for cmd, ws in phrases_en.items() for w in ws}
    check("if (english) TABLE_EN[normalize(text)] else TABLE[normalize(text)]" in vc,
          "言語ごとに別の表を引く（発話まるごとの完全一致のみ）")
    for text, want in (("Enter", "ENTER"), ("new line", "ENTER"), ("Send.", "ENTER"),
                       ("Delete", "BACKSPACE"), ("scratch that", "UNDO"),
                       ("Select all", "SELECT_ALL"), ("go left", "CURSOR_LEFT"),
                       ("Move right.", "CURSOR_RIGHT"), ("Stop listening", "STOP")):
        eq(table_en.get(VoiceCmd.normalize(text)), want, "“%s” -> %s" % (text, want))
    for text in ("Right.", "left", "Please send it to me", "delete the file", "I'll enter later",
                 "stop", "確定", "エンター"):
        eq(table_en.get(VoiceCmd.normalize(text)), None, "“%s” は文字として入力される" % text)
    for text in ("enter", "delete", "undo", "select all"):
        eq(VoiceCmd.match(text), None, "日本語版では英語の “%s” をコマンドにしない" % text)
    clash = [w for w in table_en if w in VoiceCmd.TABLE]
    check(not clash, "英語と日本語の言い回しが衝突しない")
    check(set(phrases_en) <= set(VoiceCmd.PHRASES), "英語のコマンドはすべて既存の操作")
    strings = open(os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
                   encoding="utf-8").read()
    listed = re.search(r'<string name="voice_commands_list">(.*?)</string>', strings, re.S)
    undocumented = [w for ws in phrases_en.values() for w in ws
                    if listed is None or ("“%s”" % w) not in listed.group(1)]
    check(not undocumented, "英語の設定画面に言い回しがすべて載っている%s"
          % ("" if not undocumented else " -> " + ", ".join(undocumented)))

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_english: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
