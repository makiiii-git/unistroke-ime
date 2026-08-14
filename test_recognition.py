#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
認識器の自己テスト。

Kotlin の StrokeTemplates / StrokeRecognizer をそのまま読み込み、
人が書いたストロークを合成して
  - 各テンプレートが自分自身として認識されるか（ゾーン別・記号モード別）
  - 他の字形との余裕（スコア差）がどれだけあるか
  - K / X のループが「見本と同じ回り方」で、折れ角の無い連続曲線になっているか
  - 見本（SampleStrokes のパラメータ）を真似たストロークが認識されるか
を確認する。
"""

from __future__ import annotations

import random
import sys

import unistroke_geom as G
from unistroke_model import (
    ASPECT_FLOOR, LINE_SYMBOLS, MIN_POINTS, REVERSAL_GATE, REVERSAL_SLACK,
    SWING_DEADBAND, SWING_GATE, lateral_swings,
    RETURN_MIN_SLANT, VERTICAL_MAX_SLANT, VERTICAL_SYMBOLS, angle_gated, vertical_slant,
    SCORE_THRESHOLD, SIMPLE_SYMBOLS, SPARSE_POINTS, STRAIGHT_GATE, SRC, TPL, Recognizer,
    aspect_of, interpolate_if_sparse, is_curved, path_length, resample,
    reversals, straightness,
)
from stroke_sim import decimate, fast_stroke, human_stroke

FAILURES = []


def check(cond, msg):
    if cond:
        print("  ok   %s" % msg)
    else:
        print("  FAIL %s" % msg)
        FAILURES.append(msg)


# ----------------------------------------------------------------- 人間モデル

# 「かなり雑に書いた」レベル。ここで 99% を切らないことを合格線にする。
HARD = dict(jitter=0.030, rot_deg=15.0, scale_var=0.30, smooth_window=9, samples=70)
# 通常の書き方
NORMAL = dict(jitter=0.015, rot_deg=8.0, scale_var=0.18, smooth_window=5, samples=90)


def sweep(name, templates, profile, trials, seed):
    """テンプレート集合の自己認識率と最小マージンを測る。"""
    rec = Recognizer(templates)
    total = ok = 0
    rows = []
    by_sym = {}
    for sym, pts in templates:
        by_sym.setdefault(sym, []).append(pts)
    for sym in sorted(by_sym):
        # 乱数は **文字ごとに** 種を分ける。
        # 1 本の rng を全文字で共有すると、ある文字にテンプレートを 1 つ足しただけで
        # それ以降の文字が引く乱数列がずれ、無関係な文字の結果が変わってしまう
        # （実際 O にバリアントを足したら V の判定が変わった）。
        # 文字ごとに固定しておけば、テンプレートを増やしても他の文字の結果は動かない。
        rng = random.Random("%s/%d" % (sym, seed))
        good = n = 0
        conf = {}
        margins = []
        for variant in by_sym[sym]:
            for _ in range(trials):
                s = human_stroke(variant, rng, **profile)
                sc = rec.scores(s)
                best = max(sc, key=lambda k: sc[k])
                n += 1
                if best == sym and sc[sym] >= SCORE_THRESHOLD:
                    good += 1
                else:
                    key = best if sc[best] >= SCORE_THRESHOLD else "(none)"
                    conf[key] = conf.get(key, 0) + 1
                others = max(v for k, v in sc.items() if k != sym)
                # ゲートで自分自身が候補から外れると sc に入らない。
                # その場合はマージン最小（= 完全な取りこぼし）として扱う。
                margins.append(sc.get(sym, -1.0) - others)
        margins.sort()
        rows.append((sym, good, n, conf, margins[len(margins) // 2], margins[0]))
        total += n
        ok += good
    rate = 100.0 * ok / total
    print("\n[%s] 自己認識率 %.2f%% (%d/%d)" % (name, rate, ok, total))
    bad = [r for r in rows if r[1] < r[2]]
    for sym, g, n, conf, med, mn in sorted(bad, key=lambda r: r[1] / r[2])[:8]:
        print("       %-4s %5.1f%%  margin med %+.3f min %+.3f  %s"
              % (sym, 100.0 * g / n, med, mn, sorted(conf.items(), key=lambda kv: -kv[1])[:3]))
    return rate, {r[0]: r for r in rows}


def main():
    print("=== 1. ゾーン別・モード別の自己認識 ===")
    rate, alpha = sweep("英字ゾーン(通常)", TPL.alpha_zone, NORMAL, 30, 1001)
    check(rate >= 99.5, "英字ゾーン(通常) >= 99.5%% (実測 %.2f%%)" % rate)

    rate, alpha_hard = sweep("英字ゾーン(雑)", TPL.alpha_zone, HARD, 30, 1002)
    # 「〜」を縦長に書く e のバリアントを加えたぶん、総平均はわずかに下がる
    # （波形そのものが直線と紙一重で、字形として難しいため）。
    # 実質の見張りは下の字形別・コマンド別の率で行う。
    check(rate >= 97.5, "英字ゾーン(雑) >= 97.5%% (実測 %.2f%%)" % rate)

    rate, _ = sweep("数字ゾーン(雑)", TPL.number_zone, HARD, 30, 1003)
    check(rate >= 98.5, "数字ゾーン(雑) >= 98.5%% (実測 %.2f%%)" % rate)

    rate, _ = sweep("Punctuation(雑)", TPL.blocks["punctuation"], HARD, 30, 1004)
    check(rate >= 97.0, "Punctuation(雑) >= 97.0%% (実測 %.2f%%)" % rate)

    rate, _ = sweep("Extended(雑)", TPL.blocks["extended"], HARD, 30, 1005)
    check(rate >= 97.0, "Extended(雑) >= 97.0%% (実測 %.2f%%)" % rate)

    print("\n=== 2. 衝突しやすい字の回帰（雑に書いても取り違えない） ===")
    # 「雑」プロファイルは手ぶれを相関のあるモデルへ直した時点で、以前より
    # 素直に難しくなった（全体が一方向へ歪むので、独立ノイズより形が崩れる）。
    # 1 本も間違えないことを求めると、モデルの厳しさに合わせて閾値を
    # 上下させるだけの意味の無いテストになるので、率で見る。
    # コマンドストロークは取り違えの被害が大きいので別枠で厳しく見る（下）。
    for sym in ("a", "k", "x", "y", "n", "v", "u", "m", "w", "e", "g", "p"):
        g, n, conf, med, mn = alpha_hard[sym][1:]
        rate_sym = 100.0 * g / n
        print("       %-3s %5.1f%%  margin med %+.3f min %+.3f %s"
              % (sym, rate_sym, med, mn, sorted(conf.items(), key=lambda kv: -kv[1])[:2]))
        check(rate_sym >= 93.0, "%s は雑に書いても >= 93%% (%.1f%%)" % (sym, rate_sym))
        check(med > 0.0, "%s のマージン中央値が正 (%.3f)" % (sym, med))

    print("\n=== 2b. コマンドストロークは取り違えない ===")
    for sym in ("#space", "#backspace", "#return", "#shift", "#ext"):
        if sym not in alpha_hard:
            continue
        g, n, conf, med, mn = alpha_hard[sym][1:]
        rate_sym = 100.0 * g / n
        print("       %-11s %5.1f%%  %s"
              % (sym, rate_sym, sorted(conf.items(), key=lambda kv: -kv[1])[:2]))
        check(rate_sym >= 85.0, "%s は雑に書いても >= 85%% (%.1f%%)" % (sym, rate_sym))

    print("\n=== 3. K / X のループ形状 ===")
    # バリアント（進入の腕が短い形）も含め、K / X の全テンプレートを
    # 定義順で対にして検査する。dict にすると後勝ちでバリアントしか見えない。
    k_all = [p for s, p in TPL.blocks["letters"] if s == "k"]
    x_all = [p for s, p in TPL.blocks["letters"] if s == "x"]
    punct = {s: p for s, p in TPL.blocks["punctuation"]}
    ext = {s: p for s, p in TPL.blocks["extended"]}
    check(len(k_all) == len(x_all), "K と X のバリアント数が揃っている")
    for i, (k, x) in enumerate(zip(k_all, x_all)):
        tag = "K/X" if i == 0 else "K/X バリアント%d" % i
        check(G.loop_orientation(k) == "cw", "%s: K のループは時計回り" % tag)
        check(G.loop_orientation(x) == "ccw", "%s: X のループは反時計回り（K の鏡像）" % tag)
        check(abs(G.total_turn(k) - 270.0) < 1.0,
              "%s: K の総回転量が 270 度 (%.1f)" % (tag, G.total_turn(k)))
        check(G.max_kink(k) < 20.0, "%s: K に折れ角が無い（最大 %.1f 度）" % (tag, G.max_kink(k)))
        check(G.max_kink(x) < 20.0, "%s: X に折れ角が無い（最大 %.1f 度）" % (tag, G.max_kink(x)))
        check(all(abs(a[0] - (1 - b[0])) < 1e-3 and abs(a[1] - b[1]) < 1e-3
                  for a, b in zip(k, x)),
              "%s: X は K の厳密な左右反転" % tag)
    check(G.loop_orientation(punct["+"]) == "cw", "Punctuation の + も K と同じ時計回り")
    check(G.loop_orientation(ext["+"]) == "cw", "Extended の + も K と同じ時計回り")
    check(punct["+"] == k_all[0] and ext["+"] == k_all[0], "+ は K（正準形）と同一の字形")

    # 進入・退出が交差してループになっていること（腕が閉じずに開いたままでない）
    def crosses(pts):
        def seg(p, q, r, s):
            def cr(o, a, b):
                return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
            d1, d2 = cr(p, q, r), cr(p, q, s)
            d3, d4 = cr(r, s, p), cr(r, s, q)
            return (d1 * d2 < 0) and (d3 * d4 < 0)
        for i in range(len(pts) - 1):
            for j in range(i + 2, len(pts) - 1):
                if seg(pts[i], pts[i + 1], pts[j], pts[j + 1]):
                    return True
        return False

    check(crosses(k), "K は自己交差する（= 本物のループ）")
    check(crosses(x), "X は自己交差する（= 本物のループ）")

    print("\n=== 4. 見本を真似たストロークが認識されるか ===")
    rec = Recognizer(TPL.alpha_zone)
    sr, sarm = G.sample_params()
    steps = G.sample_steps()
    print("       SampleStrokes: r=%.4f arm=%.4f steps=%d" % (sr, sarm, steps))
    smp = {"k": G.loop_glyph(sr, sarm, arc_steps=steps)}
    smp["x"] = G.mirror(smp["k"])
    check(G.loop_orientation(smp["k"]) == G.loop_orientation(k),
          "見本 K と認識テンプレート K のループの回り方が一致")
    check(G.loop_orientation(smp["x"]) == G.loop_orientation(x),
          "見本 X と認識テンプレート X のループの回り方が一致")
    check(G.max_kink(smp["k"]) < 10.0, "見本 K は折れ角ほぼ 0（最大 %.2f 度）" % G.max_kink(smp["k"]))

    for sym in ("k", "x"):
        rng = random.Random(4242)
        good = 0
        margins = []
        trials = 150
        for _ in range(trials):
            s = human_stroke(smp[sym], rng, **HARD)
            sc = rec.scores(s)
            best = max(sc, key=lambda kk: sc[kk])
            if best == sym and sc[sym] >= SCORE_THRESHOLD:
                good += 1
            margins.append(sc[sym] - max(v for kk, v in sc.items() if kk != sym))
        margins.sort()
        print("       見本どおりの %s -> %.1f%%  margin med %+.3f min %+.3f"
              % (sym, 100.0 * good / trials, margins[len(margins) // 2], margins[0]))
        check(good == trials, "見本を真似た %s が常に %s として認識される" % (sym, sym))

    print("\n=== 5. 速書き耐性（1 ストロークあたりの取得点数） ===")
    # MotionEvent は 1 フレーム（約 16ms）ぶんのサンプルをまとめて配る。
    # 履歴サンプル（getHistoricalX/Y）を捨てると、ストローク 1 本で拾える点は
    # 「所要時間 / 16ms」しか残らない。速く書くほど点が減り、字形が潰れる。
    # 指の動き自体は同じ（= 高解像度で作った 1 本のストローク）まま、
    # サンプラが拾える点数だけを変える。
    rec = Recognizer(TPL.alpha_zone)
    rates = {}
    for n_pts, label in ((90, "履歴も消化（取りこぼしなし）"),
                         (25, "400ms 相当（履歴なし）"),
                         (13, "200ms 相当（履歴なし）"),
                         (8, "120ms 相当（履歴なし・速書き）"),
                         (5, "80ms 相当（履歴なし・極速）")):
        rng = random.Random(777)
        total = ok = 0
        for sym, tpts in TPL.alpha_zone:
            for _ in range(12):
                full = human_stroke(tpts, rng, **NORMAL)
                s = full if n_pts >= len(full) else resample(full, n_pts, path_length(full))
                r = rec.recognize(s, 10.0)
                total += 1
                if r and r[0] == sym:
                    ok += 1
        rates[n_pts] = 100.0 * ok / total
        print("       %-28s 点数 %2d -> %.2f%%" % (label, n_pts, rates[n_pts]))
    check(rates[90] >= 99.0, "履歴を消化していれば >= 99%% (%.2f%%)" % rates[90])
    check(rates[8] < rates[90] and rates[5] < rates[90],
          "履歴を捨てると速書きで劣化する（%.2f%% -> %.2f%% / %.2f%%）: "
          "UniStrokeView が getHistoricalX/Y を消化する根拠"
          % (rates[90], rates[8], rates[5]))
    # decimate() は間引きの補助（回帰用途で残す）
    _ = decimate

    # ------------------------------------------------------ 速書きの内訳
    print("\n=== 速書き: 点数と角の丸まりを分けて測る ===")
    # fast_stroke は「高解像度で角を丸めてから間引く」ので、
    # サンプリングレート（点数）とコーナーの丸まりを独立に振れる。
    rec_a = Recognizer(TPL.alpha_zone)
    by_a = {}
    for sym, pts in TPL.alpha_zone:
        by_a.setdefault(sym, []).append(pts)

    def fast_rate(samples, corner, trials=8, seed=101):
        rng = random.Random(seed)
        ok = tot = 0
        rejected = 0
        for sym in sorted(by_a):
            for variant in by_a[sym]:
                for _ in range(trials):
                    st = fast_stroke(variant, rng, samples=samples, corner_radius=corner)
                    tot += 1
                    if len(st) < MIN_POINTS:
                        rejected += 1
                        continue
                    r = rec_a.recognize(st)
                    if r is None:
                        rejected += 1
                    elif r[0] == sym:
                        ok += 1
        return 100.0 * ok / tot, rejected

    rows = {}
    for samples in (32, 20, 14, 10, 8, 6, 5):
        acc, rej = fast_rate(samples, 0.10)
        rows[samples] = acc
        print("       %2d 点 : 認識 %5.1f%%  棄却 %d" % (samples, acc, rej))

    check(rows[32] >= 97.0, "点数が十分あれば >= 97%% (%.1f%%)" % rows[32])
    check(rows[14] >= 95.0, "14 点でも >= 95%% (%.1f%%)" % rows[14])
    check(rows[6] < rows[14] - 3.0,
          "8 点を切ると明確に劣化する（14 点 %.1f%% -> 6 点 %.1f%%）: "
          "requestUnbufferedDispatch でサンプル数を稼ぐ根拠" % (rows[14], rows[6]))

    print("\n=== 角の丸まり単独の影響（点数を十分に取った場合）===")
    corner_rows = {}
    for corner in (0.02, 0.10, 0.24):
        acc, _ = fast_rate(20, corner)
        corner_rows[corner] = acc
        print("       弧長比 %.2f : 認識 %5.1f%%" % (corner, acc))
    check(corner_rows[0.10] >= corner_rows[0.02] - 2.0,
          "常識的な丸まり（0.10）ではほとんど劣化しない "
          "(%.1f%% vs %.1f%%)" % (corner_rows[0.10], corner_rows[0.02]))

    print("\n=== 疎なストロークの曲線補間（interpolate_if_sparse）===")
    check(SPARSE_POINTS > MIN_POINTS,
          "補間の閾値 %d が最小点数 %d より大きい" % (SPARSE_POINTS, MIN_POINTS))
    # 補間は通過点を動かさない（制御点をそのまま通る）
    line = [(0.0, 0.0), (0.5, 0.25), (1.0, 1.0)]
    interp = interpolate_if_sparse(line)
    check(len(interp) > len(line), "3 点のストロークが補間で増える（%d 点）" % len(interp))
    check(abs(interp[0][0] - line[0][0]) < 1e-6 and abs(interp[0][1] - line[0][1]) < 1e-6,
          "始点は動かない")
    check(abs(interp[-1][0] - line[-1][0]) < 1e-6 and abs(interp[-1][1] - line[-1][1]) < 1e-6,
          "終点は動かない")
    dense = [(i / 40.0, (i / 40.0) ** 2) for i in range(41)]
    check(interpolate_if_sparse(dense) == dense,
          "点数が十分なストロークには一切手を加えない")

    print("\n=== Kotlin 側の速書き対策の配線 ===")
    view = SRC["UniStrokeView"]
    check("requestUnbufferedDispatch(event)" in view,
          "ACTION_DOWN でフルレート配送を要求している")
    check("getHistoricalX" in view, "MotionEvent の履歴サンプルを消化している")
    check("debugStrokes" in view and "logStroke" in view,
          "速書き調査用のデバッグログがある")
    check("interpolateIfSparse" in SRC["StrokeRecognizer"],
          "認識器が疎なストロークを補間している")

    # -------------------------------------------------- 曲率ゲート
    print("\n=== 曲率ゲート（縦長 ε が直線系に吸われない）===")

    def narrow_e(width):
        """幅の狭い ε。実機で e が i と誤認された書き方（縦長・幅狭）。"""
        pts = [
            (0.90, 0.10), (0.68, 0.02), (0.42, 0.03), (0.22, 0.14),
            (0.14, 0.30), (0.24, 0.42), (0.45, 0.47), (0.62, 0.50),
            (0.40, 0.56), (0.20, 0.64), (0.12, 0.80), (0.26, 0.93),
            (0.52, 0.99), (0.78, 0.94), (0.94, 0.84),
        ]
        return [(0.5 + (x - 0.5) * width, y) for x, y in pts]

    # 直線らしさの定義そのものの確認
    line = [(0.0, 0.0), (0.5, 0.5), (1.0, 1.0)]
    check(abs(straightness(line) - 1.0) < 1e-6, "まっすぐな線の直線らしさは 1.0")
    circle = [(0.5, 0.0), (1.0, 0.5), (0.5, 1.0), (0.0, 0.5), (0.5, 0.0)]
    check(straightness(circle) < 0.01, "閉じた円の直線らしさはほぼ 0")
    check(not is_curved(line), "まっすぐな線は「曲がっている」と判定されない")
    check(is_curved(circle), "円は「曲がっている」と判定される")

    # 理想字形: 直線系はゲートを通り、それ以外は通らない
    ideal = {}
    for grp in (TPL.alpha_zone, TPL.number_zone):
        for sym, pts in grp:
            ideal.setdefault(sym, []).append(pts)
    gated_wrong = [s for s in LINE_SYMBOLS
                   if s in ideal and any(is_curved(p) for p in ideal[s])]
    check(not gated_wrong,
          "直線系 %d 種の理想字形はゲートに掛からない%s"
          % (len(LINE_SYMBOLS), "" if not gated_wrong else "（掛かった: %s）" % gated_wrong))
    check(STRAIGHT_GATE > max(straightness(p) for p in ideal["e"]
                             if not all(p[i][1] <= p[i + 1][1] + 1e-9
                                        for i in range(len(p) - 1))),
          "ε の理想字形はゲートより曲がっている（波形の e は横揺れゲートの担当）")

    # 実機ログ相当（縦長 ε）が e として認識されること
    rec_gate = Recognizer(TPL.alpha_zone)
    for profile, label in ((NORMAL, "通常"), (HARD, "雑")):
        rng = random.Random(41)
        ok = total = 0
        conf = {}
        for width in (0.55, 0.45, 0.35, 0.28):
            for _ in range(30):
                r = rec_gate.recognize(human_stroke(narrow_e(width), rng, **profile))
                total += 1
                if r and r[0] == "e":
                    ok += 1
                elif r:
                    conf[r[0]] = conf.get(r[0], 0) + 1
        pct = 100.0 * ok / total
        print("       縦長 ε (%s) %5.1f%%  %s"
              % (label, pct, sorted(conf.items(), key=lambda kv: -kv[1])[:3]))
        check(pct >= 95.0, "縦長 ε (%s) が e として >= 95%% (%.1f%%)" % (label, pct))

    # 直線系そのものは巻き添えを食わない
    for sym in ("i", "#space", "#backspace", "#return", "#shift", "#ext"):
        pts = ideal[sym][0]
        rng = random.Random("gate/%s" % sym)
        ok = 0
        for _ in range(40):
            r = rec_gate.recognize(human_stroke(pts, rng, **HARD))
            if r and r[0] == sym:
                ok += 1
        check(ok >= 34, "%s は曲率ゲート後も >= 85%% (%d/40)" % (sym, ok))

    print("\n=== 細いストロークは引き伸ばさない（アスペクト下限）===")

    def aspect_of(pts):
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        w, h = max(xs) - min(xs), max(ys) - min(ys)
        return min(w, h) / max(w, h) if max(w, h) > 0 else 1.0

    punct = {s: p for s, p in TPL.zone_set("punctuation")}
    thin = [s for s in (":", "|", "_", "'", "!") if s in punct]
    worst_thin = max(aspect_of(punct[s]) for s in thin)
    check(worst_thin < ASPECT_FLOOR,
          "「細く下ろして戻す」系の記号 %s は下限 %.2f より細い（最大 %.3f）"
          % (thin, ASPECT_FLOOR, worst_thin))
    check(min(aspect_of(narrow_e(w)) for w in (0.55, 0.45, 0.35, 0.28)) >= ASPECT_FLOOR,
          "縦長 ε は下限より太いので引き伸ばしの対象になる")

    print("\n=== Kotlin 側の曲率ゲート配線 ===")
    kt = SRC["StrokeRecognizer"]
    check("if (curved && e.line) continue" in kt,
          "曲がったストロークで直線系テンプレートを除外している")
    check("val LINE_SYMBOLS" in kt, "直線系シンボルの定義がある")
    check("aspect >= ASPECT_FLOOR" in kt, "アスペクト下限を見ている")
    check("private fun straightness" in kt, "直線らしさの計算がある")

    print("\n=== 折り返しゲート（ε が f / l / t / c に吸われない）===")

    def flat_top_e():
        """上弧がほぼ水平な ε（実機で f と誤認された書き方）。"""
        return [
            (0.92, 0.06), (0.68, 0.05), (0.42, 0.05), (0.20, 0.06),
            (0.14, 0.20), (0.24, 0.42), (0.45, 0.47), (0.62, 0.50),
            (0.40, 0.56), (0.20, 0.64), (0.12, 0.80), (0.26, 0.93),
            (0.52, 0.99), (0.78, 0.94), (0.94, 0.84),
        ]

    def angular_e():
        """角張って書いた ε（z と構造が近づく書き方）。"""
        return [
            (0.92, 0.06), (0.14, 0.06), (0.14, 0.30), (0.62, 0.50),
            (0.12, 0.70), (0.12, 0.96), (0.94, 0.96),
        ]

    # 折り返し回数そのものの確認
    ideal_all = {}
    for grp in (TPL.alpha_zone, TPL.number_zone):
        for sym, pts in grp:
            ideal_all.setdefault(sym, []).append(pts)

    def is_wave(pts):
        """「〜」を縦長に書いた e のバリアントか（y が単調に増える）。"""
        return all(pts[i][1] <= pts[i + 1][1] + 1e-9 for i in range(len(pts) - 1))

    # 折り返しゲートは ε 系（弧 -> 折返し -> 弧）を対象にした話。
    # 波形の e は折り返し 2 回なので、そちらは横揺れゲートの担当。
    eps = [p for p in ideal_all["e"] if not is_wave(p)]
    check(min(reversals(p) for p in eps) >= 3,
          "ε は 3 回以上折り返す（%s）" % [reversals(p) for p in eps])
    # ゲート対象は ε より構造的に単純であること（= 折り返しが少ない）
    e_rev = min(reversals(p) for p in eps)
    for sym in sorted(SIMPLE_SYMBOLS):
        worst = max(reversals(p) for p in ideal_all[sym])
        check(worst < e_rev,
              "%s の折り返し(%d)は ε(%d)より少ない" % (sym, worst, e_rev))
    # 少なくとも 1 つのバリアントが実際にゲートされること（効いている証拠）
    for sym in sorted(SIMPLE_SYMBOLS):
        best = min(reversals(p) for p in ideal_all[sym])
        slack = REVERSAL_GATE[sym]
        check(e_rev - best >= slack,
              "ε は %s を候補から外せる（差 %d >= 許容差 %d）" % (sym, e_rev - best, slack))

    # 自分自身を巻き添えにしないこと（h / # で問題になった副作用の回帰）
    print("       -- 自己除外率（雑に書いた自分自身がゲートされる割合）--")
    for sym in sorted(SIMPLE_SYMBOLS):
        gated = n = 0
        for pts in ideal_all[sym]:
            tpl_rev = reversals(pts)
            rng = random.Random("self/%s" % sym)
            for profile in (NORMAL, HARD):
                for _ in range(60):
                    st = human_stroke(pts, rng, **profile)
                    n += 1
                    if (is_curved(st) and aspect_of(st) >= ASPECT_FLOOR
                            and reversals(st) - tpl_rev >= REVERSAL_GATE[sym]):
                        gated += 1
        print("          %-3s %d/%d" % (sym, gated, n))
        check(gated * 100 <= n, "%s の自己除外は 1%% 以下 (%d/%d)" % (sym, gated, n))
    check(reversals(flat_top_e()) >= 3, "上弧が浅い ε も 3 回折り返す")
    check(reversals(angular_e()) >= 3, "角張った ε も 3 回折り返す")

    # ゲート対象は絞ってある（雑に書いた自分自身を巻き添えにしないため）
    check(SIMPLE_SYMBOLS and all(s in ideal_all for s in SIMPLE_SYMBOLS),
          "ゲート対象 %s はすべて実在する字形" % sorted(SIMPLE_SYMBOLS))
    check("h" not in SIMPLE_SYMBOLS and "#" not in SIMPLE_SYMBOLS,
          "雑に書くと折り返しが増える字形（h / # など）は対象外")
    check(reversals(ideal_all["z"][0]) == 2 and "z" in SIMPLE_SYMBOLS,
          "z（折り返し 2 回）もゲート対象に入っている")

    # ε のマージンが広がっていること
    rec_rev = Recognizer(TPL.alpha_zone)
    for label, glyph in (("標準", ideal_all["e"][0]), ("上弧浅い", flat_top_e()),
                         ("角張り", angular_e())):
        rng = random.Random(55)
        ok = 0
        margins_e = []
        conf = {}
        for _ in range(60):
            sc = rec_rev.scores(human_stroke(glyph, rng, **HARD))
            order = sorted(sc.items(), key=lambda kv: -kv[1])
            if order[0][0] == "e":
                ok += 1
                margins_e.append(order[0][1] - order[1][1])
            else:
                conf[order[0][0]] = conf.get(order[0][0], 0) + 1
        margins_e.sort()
        med = margins_e[len(margins_e) // 2] if margins_e else -1.0
        print("       ε %-8s %2d/60  margin med %+.3f  %s"
              % (label, ok, med, sorted(conf.items(), key=lambda kv: -kv[1])[:2]))
        check(ok >= 57, "ε(%s) の誤認 5%% 未満 (%d/60)" % (label, ok))
        check(med >= 0.15,
              "ε(%s) のマージン中央値が十分 (%.3f >= 0.15)" % (label, med))

    # f / l / t / c 本来の書き方は影響を受けない
    for sym in sorted(SIMPLE_SYMBOLS):
        rng = random.Random("rev/%s" % sym)
        ok = n = 0
        for pts in ideal_all[sym]:
            for _ in range(50):
                r = rec_rev.recognize(human_stroke(pts, rng, **HARD))
                n += 1
                if r and r[0] == sym:
                    ok += 1
        check(ok == n, "%s 本来の書き方は回帰ゼロ (%d/%d)" % (sym, ok, n))

    print("\n=== 折り返しゲートの Kotlin 側配線 ===")
    kt2 = SRC["StrokeRecognizer"]
    check("val REVERSAL_GATE" in kt2, "ゲート対象と許容差の定義がある")
    check("entry.reversalSlack > 0 && curved" in kt2,
          "対象を絞り、かつ曲線のときだけ効かせている")
    check(REVERSAL_GATE.get("z") == 1,
          "z は許容差 1（折り返し回数がほぼ 2 に固定なので厳しくできる）")
    check("REVERSAL_PROMINENCE" in kt2, "細かい揺れを折り返しに数えない閾値がある")

    print("\n=== 横揺れゲート（縦長の波が i に吸われない）===")
    import math as _math

    def wave_e(amp=0.5, cycles=1.5, width=0.5, n=40):
        """「〜」を縦長に書いた e。実機でユーザーが使っている書き方。"""
        pts = []
        for i in range(n + 1):
            t = i / n
            x = 0.5 + 0.5 * amp * _math.sin(2 * _math.pi * cycles * t)
            pts.append((0.5 + (x - 0.5) * width, t))
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        w = max(xs) - min(xs)
        h = max(ys) - min(ys)
        sc = 1.0 / max(w, h)
        return [((p[0] - min(xs)) * sc, (p[1] - min(ys)) * sc) for p in pts]

    # 測度そのものの性質
    line = [(0.5, t / 20.0) for t in range(21)]
    check(lateral_swings(line) == 0, "まっすぐな線の横揺れは 0 回")
    slanted = [(0.5 + 0.2 * (t / 20.0), t / 20.0) for t in range(21)]
    check(lateral_swings(slanted) == 0, "傾いた直線の横揺れも 0 回")
    check(lateral_swings(wave_e()) >= SWING_GATE,
          "縦長の波の横揺れは %d 回以上（実測 %d）"
          % (SWING_GATE, lateral_swings(wave_e())))
    check(0.0 < SWING_DEADBAND < 0.2, "不感帯が妥当な範囲 (%.2f)" % SWING_DEADBAND)

    # 直線らしさだけでは切れないことの確認（この測度が必要な理由）
    from unistroke_model import straightness as _straight
    check(_straight(wave_e(amp=0.5, cycles=1.0)) >= STRAIGHT_GATE,
          "縦長の波は直線らしさ %.3f でゲートを通ってしまう（横揺れが必要な理由）"
          % _straight(wave_e(amp=0.5, cycles=1.0)))

    # 直線系の自己除外（h / # の教訓）
    print("       -- 直線系の自己除外率 --")
    line_ideal = {}
    for grp in (TPL.alpha_zone, TPL.number_zone):
        for sym, pts in grp:
            if sym in LINE_SYMBOLS:
                line_ideal.setdefault(sym, []).append(pts)
    for sym in sorted(line_ideal):
        gated = n = 0
        for pts in line_ideal[sym]:
            rng = random.Random("swing/%s" % sym)
            for profile in (NORMAL, HARD):
                for _ in range(80):
                    st = human_stroke(pts, rng, **profile)
                    n += 1
                    if lateral_swings(st) >= SWING_GATE:
                        gated += 1
        print("          %-11s %d/%d" % (sym, gated, n))
        check(gated * 100 <= n, "%s の自己除外は 1%% 以下 (%d/%d)" % (sym, gated, n))

    # 縦長の波が e として認識されること
    rec_sw = Recognizer(TPL.alpha_zone)
    ok = total = 0
    conf = {}
    for amp in (0.3, 0.5, 0.7):
        for cycles in (1.0, 1.5, 2.0):
            rng = random.Random(5)
            for profile in (NORMAL, HARD):
                for _ in range(20):
                    r = rec_sw.recognize(human_stroke(wave_e(amp, cycles), rng, **profile))
                    total += 1
                    if r and r[0] == "e":
                        ok += 1
                    elif r:
                        conf[r[0]] = conf.get(r[0], 0) + 1
    pct = 100.0 * ok / total
    print("       縦長の波 -> e  %.1f%%  %s"
          % (pct, sorted(conf.items(), key=lambda kv: -kv[1])[:3]))
    check(pct >= 65.0, "縦長の波が e として >= 65%% (%.1f%%)" % pct)
    # 振幅の浅い波は幾何学的にほぼ直線なので、i に流れるぶんが残る。
    # 対策前は 100% が i だったので、そこからの改善幅で見張る。
    check(conf.get("i", 0) * 4 <= total,
          "i への誤認が 25%% 以下 (%d/%d) ― 対策前は 100%%" % (conf.get("i", 0), total))

    # 直線系そのものは巻き添えを食わない。
    # 「1」は数字ゾーンにしか無いので、その記号が属するゾーンの認識器で見る。
    rec_num = Recognizer(TPL.number_zone)
    for sym in sorted(line_ideal):
        pts = line_ideal[sym][0]
        rec_for = rec_num if sym == "1" else rec_sw
        rng = random.Random("swreg/%s" % sym)
        ok2 = 0
        for _ in range(50):
            r = rec_for.recognize(human_stroke(pts, rng, **HARD))
            if r and r[0] == sym:
                ok2 += 1
        check(ok2 >= 42, "%s は横揺れゲート後も >= 84%% (%d/50)" % (sym, ok2))

    print("\n=== 横揺れゲートの Kotlin 側配線 ===")
    kt3 = SRC["StrokeRecognizer"]
    check("private fun lateralSwings" in kt3, "横揺れの計算がある")
    check("lateralSwings(points) >= SWING_GATE" in kt3,
          "直線らしさとの OR で直線系を外している")
    check("SWING_DEADBAND" in kt3, "不感帯の定数がある")

    print("\n=== 生の角度ゲート（i と #return の分離）===")

    def slanted_line(deg, n=16):
        """垂直から deg 度だけ「右上 -> 左下」へ傾いた直線。"""
        r = _math.radians(deg)
        pts = []
        for i in range(n + 1):
            t = i / n
            pts.append((0.5 + 0.5 * _math.sin(r) * (1 - 2 * t),
                        0.5 - 0.5 * _math.cos(r) * (1 - 2 * t)))
        return pts

    # 角度の定義そのもの
    check(abs(vertical_slant([(0.5, 0.0), (0.5, 1.0)])) < 0.1, "垂直線の傾きは 0 度")
    check(abs(vertical_slant([(0.0, 0.0), (1.0, 1.0)]) - 45.0) < 0.1, "45 度の斜線は 45 度")
    check(abs(vertical_slant([(0.0, 0.5), (1.0, 0.5)]) - 90.0) < 0.1, "水平線は 90 度")
    check(RETURN_MIN_SLANT < VERTICAL_MAX_SLANT, "曖昧帯が正しい向きに定義されている")

    # ゲートの判定
    check(angle_gated("#return", 10.0), "10 度では #return を外す")
    check(not angle_gated("#return", 25.0), "25 度では #return を残す")
    for sym in sorted(VERTICAL_SYMBOLS):
        check(angle_gated(sym, 25.0), "25 度では %s（縦線系）を外す" % sym)
        check(not angle_gated(sym, 10.0), "10 度では %s を残す" % sym)

    # #return テンプレートの実角度が閾値から十分離れていること
    ret_ideal = [p for s, p in TPL.alpha_zone if s == "#return"][0]
    ret_angle = vertical_slant(ret_ideal)
    check(ret_angle > VERTICAL_MAX_SLANT + 15,
          "#return の実角度 %.1f 度は閾値 %.0f 度から十分離れている" % (ret_angle, VERTICAL_MAX_SLANT))

    # 角度スイープ: 実際に書かれた角度で単調になること
    print("       -- 実際に書かれた角度ごとの勝者 --")
    rec_ang = Recognizer(TPL.alpha_zone)
    bins = {}
    rng = random.Random(7)
    for deg in range(0, 61):
        g = slanted_line(deg)
        for profile in (NORMAL, HARD):
            for _ in range(12):
                st = human_stroke(g, rng, **profile)
                r = rec_ang.recognize(st)
                key = r[0] if r else "(none)"
                b = int(vertical_slant(st) // 5) * 5
                bins.setdefault(b, {})
                bins[b][key] = bins[b].get(key, 0) + 1
    bad = []
    for b in sorted(bins):
        if b > 60:
            continue
        hist = bins[b]
        n = sum(hist.values())
        winner = max(hist, key=lambda k: hist[k])
        print("          %2d-%2d度 n=%4d  %-10s %d%%" % (b, b + 4, n, winner, 100 * hist[winner] // n))
        # 15 度未満は i、20 度以上は #return であること（曖昧帯 15-19 は i 側に倒す）
        if b + 4 < RETURN_MIN_SLANT and winner != "i":
            bad.append((b, winner))
        if b >= VERTICAL_MAX_SLANT and winner != "#return":
            bad.append((b, winner))
    check(not bad, "傾きに対して単調（前回の 10 度->#return / 15 度->i の逆転が無い）%s"
          % ("" if not bad else str(bad)))

    print("       -- 直線系の自己認識率 --")
    for sym in ("i", "l", "#shift", "#space", "#backspace", "#return", "#ext", "#ext_slash"):
        pool = [p for s, p in TPL.alpha_zone if s == sym]
        rng = random.Random("ang/%s" % sym)
        ok3 = n3 = 0
        for pts in pool:
            for profile in (NORMAL, HARD):
                for _ in range(40):
                    r = rec_ang.recognize(human_stroke(pts, rng, **profile))
                    n3 += 1
                    if r and r[0] == sym:
                        ok3 += 1
        pct3 = 100.0 * ok3 / n3
        print("          %-11s %.0f%%" % (sym, pct3))
        check(pct3 >= 90.0, "%s は角度ゲート後も >= 90%% (%.0f%%)" % (sym, pct3))

    # 「1」は数字ゾーンの認識器で見る
    rec_num2 = Recognizer(TPL.number_zone)
    pts_one = [p for s, p in TPL.number_zone if s == "1"][0]
    rng = random.Random("ang/1")
    ok3 = 0
    for profile in (NORMAL, HARD):
        for _ in range(40):
            r = rec_num2.recognize(human_stroke(pts_one, rng, **profile))
            if r and r[0] == "1":
                ok3 += 1
    check(ok3 >= 72, "1 は角度ゲート後も >= 90%% (%d/80)" % ok3)

    # #shift（下 -> 上）が i と取り違わないこと
    rng = random.Random(13)
    shift_pts = [p for s, p in TPL.alpha_zone if s == "#shift"][0]
    as_i = 0
    for profile in (NORMAL, HARD):
        for _ in range(60):
            r = rec_ang.recognize(human_stroke(shift_pts, rng, **profile))
            if r and r[0] == "i":
                as_i += 1
    check(as_i == 0, "#shift が i と取り違えられない (%d/120)" % as_i)

    print("\n=== 生の角度ゲート（横線と #return の分離）===")
    from unistroke_model import HORIZONTAL_MIN_SLANT, HORIZONTAL_SYMBOLS, RETURN_MAX_SLANT

    # 傾いた横線は、回転探索（±15 度）と非一様スケール（縦横比 0.40 以上で
    # 単位正方形へ引き伸ばし）の合わせ技で「きっかり 45 度の斜線」へ化けるため、
    # スコアでは #return と切れない（実機ログ: #backspace 0.998 vs #return 0.999）。
    # 縦線側（i vs #return）と同じく、生の角度で先に候補を絞る。
    check(HORIZONTAL_MIN_SLANT < RETURN_MAX_SLANT, "曖昧帯が正しい向きに定義されている")
    check(VERTICAL_MAX_SLANT < HORIZONTAL_MIN_SLANT, "縦側の閾値と重ならない")

    # ゲートの判定
    check(angle_gated("#return", 75.0), "75 度（横線寄り）では #return を外す")
    check(not angle_gated("#return", 60.0), "60 度では #return を残す")
    for sym in sorted(HORIZONTAL_SYMBOLS):
        check(angle_gated(sym, 60.0), "60 度では %s（横線系）を外す" % sym)
        check(not angle_gated(sym, 75.0), "75 度では %s を残す" % sym)

    # テンプレートの実角度が両閾値から十分離れていること
    check(ret_angle < HORIZONTAL_MIN_SLANT - 15,
          "#return の実角度 %.1f 度は横線ゲート %.0f 度から十分離れている"
          % (ret_angle, HORIZONTAL_MIN_SLANT))
    for sym in sorted(HORIZONTAL_SYMBOLS):
        h_angle = vertical_slant([p for s, p in TPL.alpha_zone if s == sym][0])
        check(h_angle > RETURN_MAX_SLANT + 15,
              "%s の実角度 %.1f 度は #return 上限 %.0f 度から十分離れている"
              % (sym, h_angle, RETURN_MAX_SLANT))

    # 角度スイープ: 右から左への直線を水平（90 度）から斜め（45 度）まで倒す。
    # 実際に書かれた角度が #return 上限以上なら #return は 1 本も出ない、
    # 横線ゲート以下なら横線系は 1 本も出ないこと（曖昧帯 65-69 はどちらも可）。
    print("       -- 実際に書かれた角度ごとの勝者（右 -> 左の直線） --")

    def leftward_line(deg, n=16):
        """垂直から deg 度・右上から左下へ向かう直線（90 度で水平の右 -> 左）。"""
        r = _math.radians(deg)
        pts = []
        for i in range(n + 1):
            t = i / n
            pts.append((0.8 - 0.7 * _math.sin(r) * t, 0.1 + 0.7 * _math.cos(r) * t))
        return pts

    bins2 = {}
    rng = random.Random(17)
    for deg in range(45, 91):
        g = leftward_line(deg)
        for profile in (NORMAL, HARD):
            for _ in range(12):
                st = human_stroke(g, rng, **profile)
                r = rec_ang.recognize(st)
                key = r[0] if r else "(none)"
                b = int(vertical_slant(st) // 5) * 5
                bins2.setdefault(b, {})
                bins2[b][key] = bins2[b].get(key, 0) + 1
    bad2 = []
    for b in sorted(bins2):
        hist = bins2[b]
        n = sum(hist.values())
        winner = max(hist, key=lambda k: hist[k])
        print("          %2d-%2d度 n=%4d  %-10s %d%%"
              % (b, b + 4, n, winner, 100 * hist[winner] // n))
        if b >= RETURN_MAX_SLANT and hist.get("#return", 0) > 0:
            bad2.append((b, "#return", hist["#return"]))
        if b + 4 < HORIZONTAL_MIN_SLANT:
            for sym in HORIZONTAL_SYMBOLS:
                if hist.get(sym, 0) > 0:
                    bad2.append((b, sym, hist[sym]))
    check(not bad2, "横線帯に #return が、斜線帯に横線系が 1 本も出ない%s"
          % ("" if not bad2 else " " + str(bad2)))

    print("\n=== o の回り込み（閉じたあと円弧に沿って書き進む） ===")

    # 速い円運動は閉じた位置でぴたりと止められず、円弧に沿って 1/4〜1/3 周ほど
    # 余分に回ってから離れる。この形は素の丸テンプレートから遠ざかる一方で
    # q（円 + 尻尾）にも届かず、「q:0.75 / o:0.70」のように全候補が閾値未満に
    # 落ちる死角だった（実機ログの below_threshold 12 件中 8 件がこの形）。
    # 回り込み付きバリアントがこの帯を埋めることを確認する。

    def circle_overshoot(extra_deg, n=48):
        """上から反時計回り（画面座標）の円を 360 + extra_deg 度ぶん描く。"""
        pts = []
        total = 360.0 + extra_deg
        for i in range(n + 1):
            a = _math.radians(-90.0 - total * i / n)
            pts.append((0.5 + 0.45 * _math.cos(a), 0.5 + 0.45 * _math.sin(a)))
        return pts

    rec_oq = Recognizer(TPL.alpha_zone)
    for extra in (0, 45, 90, 100, 110, 120, 130):
        rng = random.Random("oq/%d" % extra)
        ideal = circle_overshoot(extra)
        ok_o = as_q = 0
        for _ in range(30):
            s = human_stroke(ideal, rng, **NORMAL)
            sc = rec_oq.scores(s)
            best = max(sc, key=lambda k: sc[k])
            if sc[best] >= SCORE_THRESHOLD:
                if best == "o":
                    ok_o += 1
                elif best == "q":
                    as_q += 1
        check(ok_o >= 27, "回り込み %d 度の o が >= 90%% で認識される (%d/30)" % (extra, ok_o))
        check(as_q == 0, "回り込み %d 度の o が q に化けない (%d/30)" % (extra, as_q))

    print("\n=== k / x の進入の腕が短いバリアント ===")

    # 速書きではタッチ開始の取りこぼしでストロークの書き出しが欠け、
    # ∝ 字形の進入の腕だけが短くなる。素の k から遠ざかる一方で
    # h（縦線 + 山）や a（山型）に寄り、h:0.80/k:0.78 のような僅差で
    # 負けていた（実機ログで k -> h、書き直しで k -> a の誤認を確認）。

    def loop_glyph_arms(arm_in, arm_out, r=0.2071, steps=20, mirrored=False):
        """SampleStrokes.loopGlyph と同じ式（直線 -> 270 度の円弧 -> 直線）。"""
        sq = _math.sqrt(0.5)
        cx, cy = -r * sq, -r * sq
        pts = [(arm_in * sq, -arm_in * sq)]
        for i in range(steps + 1):
            th = _math.radians(45.0 + 270.0 * i / steps)
            pts.append((cx + r * _math.cos(th), cy + r * _math.sin(th)))
        t2 = pts[-1]
        pts.append((t2[0] + arm_out * sq, t2[1] + arm_out * sq))
        if mirrored:
            pts = [(-p[0], p[1]) for p in pts]
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        s = max(max(xs) - min(xs), max(ys) - min(ys))
        return [((p[0] - min(xs)) / s, (p[1] - min(ys)) / s) for p in pts]

    rec_kx = Recognizer(TPL.alpha_zone)
    for sym, mir in (("k", False), ("x", True)):
        for ain, aout in ((0.91, 0.91), (0.5, 1.1), (0.45, 1.15), (0.3, 1.2)):
            rng = random.Random("kx/%s/%s" % (sym, ain))
            ideal = loop_glyph_arms(ain, aout, mirrored=mir)
            ok = wrong = 0
            for _ in range(30):
                s = human_stroke(ideal, rng, **HARD)
                sc = rec_kx.scores(s)
                best = max(sc, key=lambda kk: sc[kk])
                if sc[best] >= SCORE_THRESHOLD:
                    if best == sym:
                        ok += 1
                    elif best in ("h", "a"):
                        wrong += 1
            check(ok >= 27, "腕 %.2f/%.2f の %s が >= 90%% で認識される (%d/30)"
                  % (ain, aout, sym, ok))
            check(wrong == 0, "腕 %.2f/%.2f の %s が h / a に化けない (%d/30)"
                  % (ain, aout, sym, wrong))

    print("\n=== 角度ゲートの Kotlin 側配線 ===")
    kt4 = SRC["StrokeRecognizer"]
    check("private fun verticalSlant" in kt4, "生の角度の計算がある")
    check("angleGated(e.symbol, slant)" in kt4, "スコア計算前に候補を絞っている")
    check("AMBIGUOUS_RETURN_PENALTY" in kt4, "曖昧帯で #return を不利にしている")
    check("straight && angleGated" in kt4, "直線的なストロークにだけ掛けている")
    check("slant >= RETURN_MAX_SLANT" in kt4, "#return に横線側の上限がある")
    check("symbol in HORIZONTAL_SYMBOLS -> slant <= HORIZONTAL_MIN_SLANT" in kt4,
          "横線系に斜線側の下限がある")

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_recognition: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
