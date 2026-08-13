#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
かなモードの文脈バイアス（RomajiConverter.expectedNext + StrokeRecognizer.CONTEXT_BONUS）の検証。

日本語のローマ字列を実際に 1 文字ずつ書いていく状況を再現し、
  誤認識がどれだけ減るか（救済） vs バイアスのせいで新たに間違えるか（誤爆）
を下駄の値ごとに測る。コマンドストローク（スペース等）が
文字に横取りされないことも確認する。

`python3 test_context_bias.py --sweep` で下駄の掃引結果を表示する。
"""

from __future__ import annotations

import random
import sys

from unistroke_model import CONTEXT_BONUS, SCORE_THRESHOLD, TPL, Recognizer, Romaji
from stroke_sim import human_stroke

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


# 実際に打ちそうな語のローマ字（母音+子音の組み合わせ・3 文字連結を含む）
CORPUS = [
    "konnichiha", "arigatou", "ohayou", "otsukaresama", "yoroshiku",
    "kyou", "kinou", "ashita", "gakkou", "kaisha", "densha", "denwa",
    "shashin", "shinkansen", "tanoshii", "muzukashii", "kyuukei",
    "byouin", "ryokou", "chotto", "hyaku", "jugyou", "sensei", "gakusei",
    "tsukue", "natsuyasumi", "kekkon", "happyou", "nyuuryoku", "sakusei",
    "shitsumon", "setsumei", "mitsukatta", "kakunin", "gijutsu",
    "tabemono", "nomimono", "yasumi", "asagohan", "yoru", "hiru",
    "kissaten", "kyakusama", "myouji", "ryouri", "shuppatsu", "tousho",
]

# バイアスの対象外であるべきコマンド（ローマ字文脈の途中でも書かれる）
COMMANDS = ["#space", "#backspace", "#return", "#shift", "#ext", "#ext_slash"]

# 誤認識をある程度起こさせるための「かなり雑」プロファイル。
# 実測でここが素の誤認識率 8% 前後になるよう振ってある（効果が測れる領域）。
SLOPPY = dict(jitter=0.070, rot_deg=24.0, scale_var=0.40, smooth_window=15, samples=45)


def variants():
    out = {}
    for sym, pts in TPL.alpha_zone:
        out.setdefault(sym, []).append(pts)
    return out


def run(bonus, seed=31337, profile=SLOPPY):
    """コーパスを 1 文字ずつ書いて、バイアス有無での結果を比べる。"""
    rec = Recognizer(TPL.alpha_zone)
    vs = variants()
    rng = random.Random(seed)

    stats = dict(n=0, err_plain=0, err_bias=0, rescued=0, broken=0, biased_strokes=0)
    cmd = dict(n=0, err_plain=0, err_bias=0, broken=0)

    for word in CORPUS:
        pending = ""
        for i, ch in enumerate(word):
            expected = Romaji.expected_next(pending)
            if ch not in vs:
                pending = ""
                continue
            stroke = human_stroke(rng.choice(vs[ch]), rng, **profile)

            p = rec.recognize(stroke)
            got_plain = p[0] if p else None
            b = rec.recognize(stroke, 0.0, expected, bonus)
            got_bias = b[0] if b else None

            stats["n"] += 1
            if expected:
                stats["biased_strokes"] += 1
            if got_plain != ch:
                stats["err_plain"] += 1
            if got_bias != ch:
                stats["err_bias"] += 1
            if got_plain != ch and got_bias == ch:
                stats["rescued"] += 1
            if got_plain == ch and got_bias != ch:
                stats["broken"] += 1

            # 実機と同じく「認識された文字」を積む（誤認識も伝播させる）
            typed = got_bias if got_bias else ""
            kana, pending = Romaji.convert(pending + typed)

            # 音節の途中（pending あり）でコマンドを書いても横取りされないか
            if pending and i % 3 == 0:
                for c in COMMANDS:
                    cpts = [p for s, p in TPL.alpha_zone if s == c]
                    for cp in cpts:
                        cs = human_stroke(cp, rng, **profile)
                        pr = rec.recognize(cs)
                        br = rec.recognize(cs, 0.0, Romaji.expected_next(pending), bonus)
                        bp = pr[0] if pr else None
                        bb = br[0] if br else None
                        cmd["n"] += 1
                        if bp != c:
                            cmd["err_plain"] += 1
                        if bb != c:
                            cmd["err_bias"] += 1
                        if bp == c and bb != c:
                            cmd["broken"] += 1
    return stats, cmd


def fmt(bonus, s, c):
    return ("  bonus %+.3f | 文字 %4d (うちバイアス対象 %4d) 誤り %3d -> %3d "
            "(救済 %2d / 新規誤爆 %2d) | コマンド %3d 誤り %2d -> %2d (横取り %d)"
            % (bonus, s["n"], s["biased_strokes"], s["err_plain"], s["err_bias"],
               s["rescued"], s["broken"], c["n"], c["err_plain"], c["err_bias"], c["broken"]))


def sweep():
    print("=== 下駄の掃引（誤認識削減 vs 新規誤爆） ===")
    rows = []
    for bonus in (0.00, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10, 0.15):
        s, c = run(bonus)
        rows.append((bonus, s, c))
        print(fmt(bonus, s, c))
    return rows


def main():
    if "--sweep" in sys.argv:
        sweep()
        return 0

    print("=== 期待集合の妥当性 ===")
    cases = {
        "k": {"a", "i", "u", "e", "o", "y", "k"},
        "ky": {"a", "i", "u", "e", "o"},
        "sh": {"a", "i", "u", "e", "o"},
        "ts": {"a", "i", "u", "e", "o"},
        "ny": {"a", "i", "u", "e", "o"},
        # "n" の直後は撥音の先読み（n + 子音 -> ん）でほぼ全子音が正当な続きなので、
        # 母音側だけを優遇しない = 空集合（バイアス無し）
        "n": set(),
    }
    for pending, want in cases.items():
        got = Romaji.expected_next(pending)
        check(got == want, "pending %-3r -> %s" % (pending, "".join(sorted(got))))
    check(Romaji.expected_next("") == set(), "pending 無しではバイアスをかけない")
    check(Romaji.expected_next("a") == set(), "母音が残ることは無い（空集合）")
    for p in ("k", "s", "t", "h", "m", "y", "r", "w", "g", "z", "d", "b", "p"):
        got = Romaji.expected_next(p)
        check(len(got) <= 12 and {"a", "i", "u", "e", "o"} <= got,
              "pending %r の期待集合に母音が全部入り、12 個以内 (%d 個)" % (p, len(got)))
    # 2 子音クラスタは母音のみ = より強い期待
    for p in ("ky", "sh", "ch", "ts", "ry", "by", "py", "gy", "ny", "my", "hy"):
        got = Romaji.expected_next(p)
        check(got == {"a", "i", "u", "e", "o"}, "2 子音クラスタ %r は母音のみを期待" % p)

    print("\n=== 実効果（採用値 %.3f） ===" % CONTEXT_BONUS)
    base, base_c = run(0.0)
    cur, cur_c = run(CONTEXT_BONUS)
    print(fmt(0.0, base, base_c))
    print(fmt(CONTEXT_BONUS, cur, cur_c))
    check(cur["err_bias"] < base["err_bias"],
          "バイアスで誤認識が減る (%d -> %d)" % (base["err_bias"], cur["err_bias"]))
    check(cur["broken"] == 0, "バイアスによる新規誤爆が 0 (%d)" % cur["broken"])
    check(cur_c["broken"] == 0, "コマンドストロークが文字に横取りされない (%d)" % cur_c["broken"])

    print("\n=== pending \"n\" の直後に子音を書いても壊れない（n+子音 -> ん の先読み） ===")
    rec = Recognizer(TPL.alpha_zone)
    vs = variants()
    exp_n = Romaji.expected_next("n")
    rng = random.Random(555)
    broken = 0
    total = 0
    for ch in ("d", "j", "b", "k", "s", "t", "g", "z", "p", "m", "r", "h"):
        for _ in range(12):
            s = human_stroke(rng.choice(vs[ch]), rng, **SLOPPY)
            p = rec.recognize(s)
            b = rec.recognize(s, 0.0, exp_n, CONTEXT_BONUS)
            total += 1
            if (p[0] if p else None) == ch and (b[0] if b else None) != ch:
                broken += 1
    check(broken == 0, "pending 'n' のバイアスが子音を壊さない (%d/%d)" % (broken, total))
    check(not exp_n, "pending 'n' では期待集合が空（= バイアスしない）")
    # 参考: 母音側だけ優遇すると壊れることの裏付け（この設定は採用していない）
    naive = set("aiueo") | {"y", "n"}
    naive_broken = 0
    rng = random.Random(555)
    for ch in ("d", "j", "b", "k", "s", "t", "g", "z", "p", "m", "r", "h"):
        for _ in range(12):
            s = human_stroke(rng.choice(vs[ch]), rng, **SLOPPY)
            p = rec.recognize(s)
            b = rec.recognize(s, 0.0, naive, CONTEXT_BONUS)
            if (p[0] if p else None) == ch and (b[0] if b else None) != ch:
                naive_broken += 1
    print("       （母音だけ優遇した場合の子音誤爆: %d/%d — 不採用の根拠）"
          % (naive_broken, total))
    check(naive_broken > 0, "母音だけ優遇する案は実際に子音を壊す（不採用の裏付け）")
    kana, pend = Romaji.convert("kond")
    check((kana, pend) == ("こん", "d"), "n + 子音の先読みが生きている: kond -> こん + d")
    check(Romaji.flush("kan") == "かn", "末尾の単独 n は Latin の n（flush(kan) = かn）")
    check(Romaji.preview("n") == "n" and Romaji.preview("nn") == "ん",
          "合成中の表示: n -> n / nn -> ん")

    print("\n=== 過剰バイアスの検知（大きすぎる下駄は誤爆する） ===")
    big, big_c = run(0.30)
    print(fmt(0.30, big, big_c))
    check(big["broken"] > 0 or big_c["broken"] > 0,
          "下駄 0.30 では誤爆が出る（採用値がタイブレーク域であることの裏付け）")
    check(CONTEXT_BONUS <= 0.05, "採用値がタイブレーク域（<= 0.05）")

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_context_bias: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
