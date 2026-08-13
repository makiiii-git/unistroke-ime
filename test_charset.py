#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
候補バーの「◆」（U+FFFD）対策のテスト。

GoogleConvertClient.kt のデコード方針を Python に写し、
非 UTF-8 のバイト列を模したレスポンスでも文字化けしないことを確認する。
Kotlin 側にリクエストパラメータと U+FFFD 除去が入っているかも静的に見る。
"""

from __future__ import annotations

import json
import re
import sys

from unistroke_model import SRC

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


SRC_G = SRC["GoogleConvertClient"]

# Kotlin の FALLBACK_NAMES と同じ並びを読む（Python 側の別名へ写す）
ALIAS = {"UTF-8": "utf-8", "Windows-31J": "cp932", "EUC-JP": "euc_jp",
         "Shift_JIS": "shift_jis", "UTF-16LE": "utf-16-le", "UTF-16BE": "utf-16-be"}


def fallback_names():
    m = re.search(r"FALLBACK_NAMES\s*=\s*listOf\(([^)]*)\)", SRC_G)
    return [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]


FALLBACKS = [ALIAS[n] for n in fallback_names()]

CHARSET_RE = re.compile(r"charset\s*=\s*([^;\s]+)", re.I)


def charset_of(content_type):
    if not content_type:
        return None
    m = CHARSET_RE.search(content_type)
    if not m:
        return None
    name = m.group(1).strip().strip('"\'')
    return ALIAS.get(name, ALIAS.get(name.replace("_", "-"), name)) or None


def strict_decode(b, enc):
    try:
        return b.decode(enc, errors="strict")
    except (UnicodeDecodeError, LookupError):
        return None


def decode_bom(b):
    if b[:3] == b"\xef\xbb\xbf":
        return strict_decode(b[3:], "utf-8") or b[3:].decode("utf-8", "replace")
    if b[:2] == b"\xff\xfe":
        return b[2:].decode("utf-16-le", "replace")
    if b[:2] == b"\xfe\xff":
        return b[2:].decode("utf-16-be", "replace")
    return None


def decode(b, declared=None):
    """GoogleConvertClient.decode() と同じ順序。"""
    if not b:
        return ""
    bom = decode_bom(b)
    if bom is not None:
        return bom
    if declared:
        s = strict_decode(b, declared)
        if s is not None:
            return s
    for enc in FALLBACKS:
        s = strict_decode(b, enc)
        if s is not None:
            return s
    return b.decode("utf-8", "replace")


def is_usable(s):
    return bool(s) and "�" not in s


SUGGEST = ["ありがとう", ["ありがとうございます", "ありがとう 英語", "ありがとうございました"]]
TRANSLIT = [["かんじ", ["漢字", "感じ", "幹事"]], ["へんかん", ["変換", "返還"]]]


def body(obj):
    return json.dumps(obj, ensure_ascii=False)


def main():
    print("=== 1. 文字コード別のデコード ===")
    cases = [
        ("UTF-8 / charset 宣言あり", "utf-8", "application/json; charset=UTF-8"),
        ("UTF-8 / 宣言なし", "utf-8", "text/javascript"),
        ("UTF-8 / Content-Type なし", "utf-8", None),
        ("Shift_JIS(cp932) / 宣言あり", "cp932", "text/javascript; charset=Shift_JIS"),
        ("Shift_JIS(cp932) / 宣言なし", "cp932", "text/javascript"),
        ("EUC-JP / 宣言あり", "euc_jp", "text/javascript; charset=EUC-JP"),
        ("EUC-JP / 宣言なし", "euc_jp", None),
        ("宣言が嘘（実体は cp932 なのに UTF-8 と名乗る）", "cp932", "text/javascript; charset=utf-8"),
        ("宣言が未知の文字コード名", "utf-8", "text/javascript; charset=x-unknown-99"),
        ("charset がクォート付き", "cp932", 'text/javascript; charset="Shift_JIS"'),
    ]
    for label, enc, ct in cases:
        raw = body(SUGGEST).encode(enc)
        got = decode(raw, charset_of(ct))
        ok = got == body(SUGGEST) and "�" not in got
        check(ok, "%s -> 復元 %s" % (label, "成功" if ok else "失敗: %r" % got[:40]))

    print("\n=== 2. BOM ===")
    for label, enc, bom in (
        ("UTF-8 BOM", "utf-8", b"\xef\xbb\xbf"),
        ("UTF-16LE BOM", "utf-16-le", b"\xff\xfe"),
        ("UTF-16BE BOM", "utf-16-be", b"\xfe\xff"),
    ):
        raw = bom + body(SUGGEST).encode(enc)
        got = decode(raw, None)
        check(got == body(SUGGEST) and "�" not in got, "%s を正しく剥がす" % label)

    print("\n=== 3. 復元した本文が JSON として読めて候補が化けない ===")
    for enc in ("utf-8", "cp932", "euc_jp"):
        raw = body(SUGGEST).encode(enc)
        parsed = json.loads(decode(raw, None))
        cands = [c for c in parsed[1] if is_usable(c) and " " not in c]
        check(cands == ["ありがとうございます", "ありがとうございました"],
              "suggest(%s) の候補が化けず、空白入りだけ落ちる" % enc)
        raw = body(TRANSLIT).encode(enc)
        parsed = json.loads(decode(raw, None))
        segs = [(p[0], [c for c in p[1] if is_usable(c)]) for p in parsed]
        check(segs == [("かんじ", ["漢字", "感じ", "幹事"]), ("へんかん", ["変換", "返還"])],
              "transliterate(%s) の文節が化けない" % enc)

    print("\n=== 4. どうしても読めないバイト列（最後の砦） ===")
    # UTF-8 でも cp932 でも EUC-JP でも解釈できない列を混ぜる
    # 0x81 + 0x20 は UTF-8 / cp932 / EUC-JP のいずれでも不正な組み合わせ
    broken = b'["\x81\x20\x8f\x20\xc0\xaf", ["\x81\x20"]]'
    got = decode(broken, None)
    check("�" in got, "壊れたバイト列は置換文字になる（= 最終フォールバックが働く）")
    check(not is_usable("あ�い"), "U+FFFD を含む候補は捨てられる")
    check(is_usable("ありがとう"), "正常な候補は残る")
    check(not is_usable(""), "空文字は候補にしない")

    print("\n=== 5. Kotlin 側の実装確認 ===")
    check("ie=utf-8&oe=utf-8" in SRC_G, "Suggest リクエストに ie=utf-8&oe=utf-8 が付いている")
    check('setRequestProperty("Accept-Charset", "utf-8")' in SRC_G,
          "Accept-Charset ヘッダを送っている")
    check(SRC_G.count('setRequestProperty("Accept-Charset", "utf-8")') == 2,
          "transliterate / suggest の両方に付いている")
    check("InputStreamReader" not in SRC_G,
          "UTF-8 決め打ちの InputStreamReader を使っていない")
    check("CodingErrorAction.REPORT" in SRC_G, "厳密デコード（置換ではなく例外）を使っている")
    check("readBody(conn)" in SRC_G and SRC_G.count("readBody(conn)") == 2,
          "transliterate / suggest の両方が共通デコードを通る")
    check(re.search(r"REPLACEMENT_CHAR\s*=\s*'\\uFFFD'", SRC_G) is not None,
          "U+FFFD をエスケープで定義している")
    check(SRC_G.count("isUsable(") >= 4, "候補と読みの両方で U+FFFD を弾いている")
    check("Windows-31J" in SRC_G and "EUC-JP" in SRC_G,
          "日本語の代表的な非 UTF-8 文字コードをフォールバックに持つ")

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_charset: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
