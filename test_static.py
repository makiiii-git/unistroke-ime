#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静的検証（この環境に JDK が無いのでコンパイルの代わりに行う）。

  1. すべての .kt の括弧バランス（文字列・文字リテラル・コメントを除外して数える）
  2. 自作クラス/オブジェクトへの参照 `Type.MEMBER` が実在するか
  3. R.string / R.color / R.layout / R.id と、XML の @string/@color 参照の整合
  4. レイアウト XML が指すカスタム View クラスの実在
  5. XML の well-formed 検証
  6. 入力パイプライン（速書き対応）の実装確認
"""

from __future__ import annotations

import os
import re
import sys
import xml.dom.minidom

from unistroke_model import PKG, ROOT

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


KT_FILES = sorted(f for f in os.listdir(PKG) if f.endswith(".kt"))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


SRCS = {f[:-3]: read(os.path.join(PKG, f)) for f in KT_FILES}


# --------------------------------------------------------------- 1. 括弧

def strip_kotlin(src: str) -> str:
    """文字列・文字リテラル・コメントを空白へ潰す。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"' and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(" " * (j - i))
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("/*", i):
            depth = 0
            j = i
            while j < n:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                    continue
                if src.startswith("*/", j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                    continue
                j += 1
            out.append(" " * (j - i))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def balance(name, code):
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack or stack[-1][0] != pairs[ch]:
                return "%s: %d 行目の '%s' が対応しない" % (name, line, ch)
            stack.pop()
    if stack:
        return "%s: %d 行目の '%s' が閉じていない" % (name, stack[-1][1], stack[-1][0])
    return None


# --------------------------------------------- 2. 型メンバの参照整合

DECL_RE = re.compile(
    r"\b(?:object|class|interface|enum\s+class|data\s+class|sealed\s+class)\s+(\w+)")
MEMBER_RE = re.compile(
    r"\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?(\w+)")
ENUM_BODY_RE = re.compile(r"\benum\s+class\s+\w+[^{]*\{([^}]*)\}", re.S)


# enum / object / data class が暗黙に持つメンバ
IMPLICIT = {
    "valueOf", "values", "entries", "name", "ordinal", "Companion", "INSTANCE",
    "copy", "equals", "hashCode", "toString", "javaClass", "class",
}


def declared_types():
    """型名 -> それを宣言しているファイルの一覧。

    入れ子の型は別のクラスの中で同じ名前を使える（AppUpdater.Failure と
    DictionaryUpdater.Failure など）。1 つに決め打ちすると、
    先に見つかったほうだけを正として他方を「存在しない」と誤判定するので、
    宣言しているファイルを全部覚えておく。
    """
    out = {}
    for name, src in SRCS.items():
        code = strip_kotlin(src)
        for m in DECL_RE.finditer(code):
            out.setdefault(m.group(1), []).append(name)
    return out


def members_of_file(name):
    code = strip_kotlin(SRCS[name])
    out = set(MEMBER_RE.findall(code))
    out |= set(DECL_RE.findall(code))
    for body in ENUM_BODY_RE.findall(code):
        for entry in re.findall(r"\b([A-Z][A-Z0-9_]*)\b", body):
            out.add(entry)
    # data class / コンストラクタ引数
    for m in re.finditer(r"\b(?:data\s+class|class)\s+\w+\s*\(([^)]*)\)", code, re.S):
        for p in re.findall(r"\b(?:val|var)\s+(\w+)", m.group(1)):
            out.add(p)
    return out


def main():
    print("=== 1. Kotlin の括弧バランス ===")
    for name in sorted(SRCS):
        err = balance(name + ".kt", strip_kotlin(SRCS[name]))
        check(err is None, err or "%s.kt の括弧が閉じている" % name)

    print("\n=== 2. 自作型へのメンバ参照 ===")
    types = declared_types()
    cache = {}
    bad = []
    refs = 0
    for name, src in SRCS.items():
        code = strip_kotlin(src)
        for m in re.finditer(r"\b([A-Z]\w+)\.(\w+)", code):
            tname, member = m.group(1), m.group(2)
            owners = types.get(tname)
            if not owners:
                continue  # Android / stdlib の型
            if member in IMPLICIT:
                continue
            # 同じ名前の入れ子型が複数あるときは、参照元のファイル自身の宣言を優先する。
            # それが無ければ、宣言しているどれかに在れば解決とみなす。
            candidates = [name] if name in owners else owners
            refs += 1
            if not any(member in cache.setdefault(o, members_of_file(o)) for o in candidates):
                bad.append("%s.kt: %s.%s が %s に無い"
                           % (name, tname, member, " / ".join(c + ".kt" for c in candidates)))
    check(not bad, "自作型への参照 %d 件がすべて解決する%s"
          % (refs, "" if not bad else " -> " + "; ".join(sorted(set(bad))[:6])))

    print("\n=== 3. リソース参照 ===")
    strings = set(re.findall(r'<string name="([^"]+)"', read(os.path.join(RES, "values", "strings.xml"))))
    colors = set(re.findall(r'<color name="([^"]+)"', read(os.path.join(RES, "values", "colors.xml"))))
    styles = set(re.findall(r'<style name="([^"]+)"', read(os.path.join(RES, "values", "themes.xml"))))
    layouts = {f[:-4] for f in os.listdir(os.path.join(RES, "layout"))}
    drawables = {f[:-4] for f in os.listdir(os.path.join(RES, "drawable"))}
    ids = set()
    for f in os.listdir(os.path.join(RES, "layout")):
        ids |= set(re.findall(r'android:id="@\+id/([^"]+)"', read(os.path.join(RES, "layout", f))))

    missing = []
    for name, src in SRCS.items():
        # android.R.* はフレームワーク側のリソースなので除外する
        code = strip_kotlin(src).replace("android.R.", "ANDROID_R_")
        for kind, pool in (("string", strings), ("color", colors), ("layout", layouts),
                           ("style", styles), ("id", ids)):
            for m in re.findall(r"\bR\.%s\.(\w+)" % kind, code):
                if m not in pool:
                    missing.append("%s.kt: R.%s.%s" % (name, kind, m))
    check(not missing, "Kotlin からのリソース参照がすべて実在する%s"
          % ("" if not missing else " -> " + ", ".join(missing[:8])))

    xml_missing = []
    xml_files = []
    for sub in ("layout", "values", "xml", "drawable"):
        d = os.path.join(RES, sub)
        xml_files += [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".xml")]
    xml_files.append(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))
    for path in xml_files:
        body = read(path)
        for kind, pool in (("string", strings), ("color", colors), ("style", styles),
                           ("drawable", drawables), ("layout", layouts)):
            for m in re.findall(r'"@%s/([\w.]+)"' % kind, body):
                if m not in pool:
                    xml_missing.append("%s: @%s/%s" % (os.path.basename(path), kind, m))
    check(not xml_missing, "XML からのリソース参照がすべて実在する%s"
          % ("" if not xml_missing else " -> " + ", ".join(xml_missing[:8])))

    print("\n=== 4. レイアウトが指すカスタム View ===")
    custom = set()
    for f in os.listdir(os.path.join(RES, "layout")):
        custom |= set(re.findall(r"<com\.unistroke\.ime\.(\w+)", read(os.path.join(RES, "layout", f))))
    for c in sorted(custom):
        check(c in types, "%s クラスが存在する（%s.kt）" % (c, types.get(c)))

    print("\n=== 5. XML の well-formed ===")
    for path in xml_files:
        try:
            xml.dom.minidom.parse(path)
            ok = True
        except Exception as e:  # noqa: BLE001
            ok = False
            print("       %s: %s" % (path, e))
        check(ok, "%s が well-formed" % os.path.relpath(path, ROOT))

    print("\n=== 6. 入力パイプライン（速書き対応） ===")
    v = SRCS["UniStrokeView"]
    check("getHistoricalX" in v and "getHistoricalY" in v,
          "MOVE の履歴サンプルを取り込んでいる（速書きで軌跡が粗くならない）")
    check(v.count("event.historySize") >= 2,
          "MOVE と UP の両方で履歴サンプルを消化している")
    check("ACTION_POINTER_DOWN" in v,
          "2 本目の指で現在のストロークを即確定している")
    check("STROKE_GUARD_MS" not in v, "ペンダウンの抑制時間（STROKE_GUARD_MS）が撤廃されている")
    check("lastUpTime" not in v, "抑制時間用の状態が残っていない")
    up = v[v.find("MotionEvent.ACTION_UP ->"):]
    up = up[:up.find("MotionEvent.ACTION_CANCEL")]
    check("finishStroke()" in up, "ペンアップで即座に認識している（遅延なし）")
    check("postDelayed" not in up, "ペンアップから認識までに遅延を挟んでいない")
    fs = v[v.find("private fun finishStroke()"):]
    fs = fs[:fs.find("private fun clearStroke()")]
    check("listener?.onSymbol(" in fs, "認識結果を同期的に通知している（処理順序が保証される）")
    check(fs.find("strokeFade.start()") < fs.find("listener?.onSymbol("),
          "フェード開始より前に軌跡を退避している（次のストロークと干渉しない）")

    print("\n=== 7. 見本のパラメトリック生成器 ===")
    s = SRCS["SampleStrokes"]
    check("fun loopGlyph(" in s, "SampleStrokes がパラメトリック生成器を持つ")
    check(re.search(r"val cx = -r \* SQ", s) and re.search(r"val cy = -r \* SQ", s),
          "円の中心が T1 の左上（= 進行方向 135° の右手側 -> 時計回り）")
    check("45f + 270f * i / steps" in s, "円弧は位置角 45° から 270° ぶん（時計回り）")
    check("SAMPLE_LOOP_R" in s and "SAMPLE_ARM" in s and "SAMPLE_ARC_STEPS" in s,
          "見本のパラメータが定数として外に出ている")
    check('"k" to K' in s and '"x" to X' in s and '"+" to K' in s,
          "K / X / + の見本が同じ生成器（loopGlyph）から来る")
    check('"b" to B' in s, "B にも見本専用ストロークがある（始点と終点を重ねない）")
    check("mirrored = true" in s, "X は K の鏡像として生成している")
    check("loopGlyph" in s and "listOf(" not in s.split("fun loopGlyph")[0].split("OVERRIDES")[0],
          "見本の座標をハードコードしていない")
    t = SRCS["StrokeTemplates"]
    check("SampleStrokes.loopGlyph" in t or "同じ式" in t,
          "認識テンプレート側に生成元の式が明記されている")

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_static: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
