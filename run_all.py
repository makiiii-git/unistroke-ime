#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""検証スイートをまとめて走らせる。

    python3 run_all.py            全部
    python3 run_all.py -q         各スイートの結果行だけ
"""

from __future__ import annotations

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

SUITES = [
    ("test_static.py", "静的検証（括弧・参照・リソース・入力パイプライン）"),
    ("test_romaji.py", "ローマ字 -> かな"),
    ("test_ime_sequence.py", "IME のストローク割当てシーケンス"),
    ("test_charset.py", "レスポンスの文字コード（◆ 対策）"),
    ("test_recognition.py", "認識器の自己テスト（K/X の字形を含む）"),
    ("test_context_bias.py", "かなモードの文脈バイアス"),
    ("test_dictionary.py", "オンデバイス辞書バイナリの整合"),
    ("test_ondevice.py", "オンデバイス変換エンジン（品質・統合）"),
    ("test_updater.py", "拡張辞書の配布マニフェストと受け入れ条件"),
]


def main() -> int:
    quiet = "-q" in sys.argv
    results = []
    for script, label in SUITES:
        print("\n" + "=" * 72)
        print("## %s  (%s)" % (label, script))
        print("=" * 72)
        p = subprocess.run([sys.executable, os.path.join(ROOT, script)],
                           capture_output=quiet, text=True, cwd=ROOT)
        if quiet:
            tail = [l for l in p.stdout.splitlines() if "PASS" in l or "FAIL" in l]
            print("\n".join(tail[-12:]))
        results.append((script, p.returncode))

    print("\n" + "=" * 72)
    bad = [s for s, rc in results if rc != 0]
    for script, rc in results:
        print("  %-24s %s" % (script, "PASS" if rc == 0 else "FAIL"))
    print("=" * 72)
    if bad:
        print("FAILED: %s" % ", ".join(bad))
        return 1
    print("ALL SUITES PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
