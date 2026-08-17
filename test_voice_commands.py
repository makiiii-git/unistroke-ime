#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VoiceCommands.kt の対応表と正規化のテスト。

音声認識の結果を「文字として入れる」か「操作として実行する」かを分ける判定なので、
誤爆（普通の発話がコマンドになる）と取りこぼし（言い回しが表に載っているのに
一致しない）の両方を潰す。表は Kotlin から読むので、Kotlin を直せば追随する。
"""

from __future__ import annotations

import os
import re
import sys

from unistroke_model import ROOT, SRC, VoiceCmd

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def eq(got, want, msg):
    check(got == want, "%s（got=%r want=%r）" % (msg, got, want))


def main() -> int:
    print("=== 1. 正規化 ===")
    eq(VoiceCmd.normalize("エンター"), "えんたー", "カタカナはひらがなへ")
    eq(VoiceCmd.normalize("確定。"), "確定", "末尾の句点を落とす")
    eq(VoiceCmd.normalize("取り 消し"), "取り消し", "途中の空白を落とす")
    eq(VoiceCmd.normalize("　全選択　"), "全選択", "全角空白を落とす")
    eq(VoiceCmd.normalize("スペース！"), "すぺーす", "長音はひらがな化しても残る")
    eq(VoiceCmd.normalize("Enter"), "enter", "ASCII は小文字へ")
    # 対応表は正規化した形で引く。二度かけても変わらないこと。
    unstable = [w for w in VoiceCmd.TABLE if VoiceCmd.normalize(w) != w]
    check(not unstable, "対応表の鍵 %d 件が正規化で不動%s"
          % (len(VoiceCmd.TABLE),
             "" if not unstable else " -> " + ", ".join(unstable)))

    print("\n=== 1b. 表の言い回しはすべて一致する ===")
    broken = []
    for table, label in ((VoiceCmd.PHRASES, "一覧"), (VoiceCmd.READINGS, "読み")):
        for cmd, words in table.items():
            for w in words:
                if VoiceCmd.match(w) != cmd:
                    broken.append("%s:%s(%s)" % (cmd, w, label))
    check(not broken, "表に載せた言い回しがすべて自分のコマンドに一致する%s"
          % ("" if not broken else " -> " + ", ".join(broken)))

    print("\n=== 2. コマンドの一致 ===")
    CASES = [
        ("エンター", "ENTER"),
        ("改行", "ENTER"),
        ("送信", "ENTER"),
        ("スペース", "SPACE"),
        ("変換", "CONVERT"),
        ("確定", "COMMIT"),
        ("かくてい", "COMMIT"),
        ("決定", "COMMIT"),
        ("削除", "BACKSPACE"),
        ("消して", "BACKSPACE"),
        ("取り消し", "UNDO"),
        ("取り消して", "UNDO"),
        ("全選択", "SELECT_ALL"),
        ("左", "CURSOR_LEFT"),
        ("右へ", "CURSOR_RIGHT"),
        ("音声終了", "STOP"),
        ("終わり", "STOP"),
    ]
    for text, want in CASES:
        eq(VoiceCmd.match(text), want, "「%s」-> %s" % (text, want))

    print("\n=== 3. 普通の発話はコマンドにしない ===")
    # 「発話まるごと一致」なので、文の一部に含まれていても実行されない
    NOT_COMMANDS = [
        "確定してください",
        "この内容で確定します",
        "取り消しの手続きについて",
        "右に曲がってください",
        "今日は晴れです",
        "会議は終わりました",
        "削除ボタンを押す",
        "エンターキーを押してください",
        "変換して送信します",
    ]
    for text in NOT_COMMANDS:
        eq(VoiceCmd.match(text), None, "「%s」は文字として入力される" % text)

    print("\n=== 4. 言い回しの衝突と安全性 ===")
    seen = {}
    dup = []
    for table in (VoiceCmd.PHRASES, VoiceCmd.READINGS):
        for cmd, words in table.items():
            for w in words:
                key = VoiceCmd.normalize(w)
                if key in seen and seen[key] != cmd:
                    dup.append("%s（%s と %s）" % (w, seen[key], cmd))
                seen[key] = cmd
    check(not dup, "同じ言い回しが 2 つのコマンドに割り当てられていない%s"
          % ("" if not dup else " -> " + ", ".join(dup)))

    # 戻しにくい操作ほど、短すぎる言い回しを持たせない
    RISKY = ("ENTER", "SELECT_ALL", "STOP", "BACKSPACE")
    short = [
        "%s:%s" % (cmd, w)
        for cmd in RISKY
        for table in (VoiceCmd.PHRASES, VoiceCmd.READINGS)
        for w in table.get(cmd, [])
        if len(w) < 2
    ]
    check(not short, "戻しにくい操作に 1 文字の言い回しが無い%s"
          % ("" if not short else " -> " + ", ".join(short)))

    print("\n=== 5. Kotlin 側との対応 ===")
    ime = SRC["UniStrokeIME"]
    missing = [
        cmd for cmd in VoiceCmd.PHRASES
        if ("VoiceCommands.Command.%s ->" % cmd) not in ime
    ]
    check(not missing, "すべてのコマンドが IME で実行されている%s"
          % ("" if not missing else " -> " + ", ".join(missing)))

    strings = open(
        os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
        encoding="utf-8",
    ).read()
    listed = re.search(
        r'<string name="voice_commands_list">(.*?)</string>', strings, re.S
    )
    check(listed is not None, "設定画面にコマンド一覧がある")
    if listed:
        # 案内する言い回し（PHRASES）は全部載っていること。
        # READINGS は同じ発話の別表記なので一覧には出さない。
        body = listed.group(1)
        undocumented = [
            w for words in VoiceCmd.PHRASES.values() for w in words if w not in body
        ]
        check(not undocumented, "一覧に載っていない言い回しが無い%s"
              % ("" if not undocumented else " -> " + ", ".join(undocumented)))

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_voice_commands: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
