#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""プレミアムの鍵（試用 → 購入で解錠）の検証。

実機も Google Play も無しで確かめられる範囲を見る:

  1. 試用の残り時間の式（Entitlement.msLeft / daysLeft）を Python へ写して境界を総当たり
  2. 配布フレーバーごとの条件（play = 試用 5 日 + 購入 / github = 全機能・制限なし）
  3. 「入力中は通信しない」の線引き ―― IME サービスは Google Play へ問い合わせない
  4. 閉じる機能と、閉じてはいけない機能（基本の入力）の切り分け

購入そのもの（Play の購入画面・承認・復元）は Play Console にアイテムを登録した
ビルドでしか確かめられない。ここでは扱わない。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "app", "src")
PKG = ("java", "com", "unistroke", "ime")
KT = os.path.join(SRC, "main", *PKG)
KT_GITHUB = os.path.join(SRC, "github", *PKG)
KT_PLAY = os.path.join(SRC, "play", *PKG)

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def code(src):
    """コメントを落とした本文（コメント中の語に引っかからないように）。"""
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", src, flags=re.S)


DAY_MS = 24 * 60 * 60 * 1000


def ms_left(start, effective_now, trial_days):
    """Entitlement.msLeft の移植。"""
    return start + trial_days * DAY_MS - effective_now


def days_left(ms):
    """Entitlement.daysLeft の移植（切り上げ、終了後は 0）。"""
    return 0 if ms <= 0 else (ms + DAY_MS - 1) // DAY_MS


def effective_now(now, seen):
    """Entitlement.trialMsLeft の時計巻き戻し対策（見た最も遅い時刻より前へは戻らない）。"""
    return max(now, seen)


def main() -> int:
    ent = read(os.path.join(KT, "Entitlement.kt"))
    gate_play = read(os.path.join(KT_PLAY, "PremiumGate.kt"))
    gate_gh = read(os.path.join(KT_GITHUB, "PremiumGate.kt"))
    ime = read(os.path.join(KT, "UniStrokeIME.kt"))
    gradle = read(os.path.join(ROOT, "app", "build.gradle.kts"))

    print("=== 1. 試用の残り時間 ===")
    check("start + trialDays * DAY_MS - effectiveNow" in ent, "残り時間の式が移植元と同じ")
    check("(msLeft + DAY_MS - 1) / DAY_MS" in ent, "残り日数は切り上げ")
    check("maxOf(now, seen)" in ent, "時計を巻き戻しても試用は延びない（最も遅い時刻を使う）")
    t0 = 1_800_000_000_000
    check(days_left(ms_left(t0, t0, 5)) == 5, "開始直後は残り 5 日")
    check(days_left(ms_left(t0, t0 + 1, 5)) == 5, "開始 1ms 後も残り 5 日（切り上げ）")
    check(days_left(ms_left(t0, t0 + DAY_MS, 5)) == 4, "ちょうど 1 日後は残り 4 日")
    check(days_left(ms_left(t0, t0 + 5 * DAY_MS - 1, 5)) == 1, "終了 1ms 前は残り 1 日")
    check(ms_left(t0, t0 + 5 * DAY_MS - 1, 5) > 0, "終了 1ms 前はまだ試用中")
    check(ms_left(t0, t0 + 5 * DAY_MS, 5) <= 0, "ちょうど 5 日で終了")
    check(days_left(ms_left(t0, t0 + 30 * DAY_MS, 5)) == 0, "終了後の残り日数は 0（負にならない）")
    seen = t0 + 6 * DAY_MS
    rolled_back = t0 + 1 * DAY_MS
    check(ms_left(t0, effective_now(rolled_back, seen), 5) <= 0,
          "終了後に時計を戻しても終了のまま")
    check("if (start == 0L) start = effective" in ent, "試用は最初に判定した時刻から始まる")
    check("SEEN_WRITE_INTERVAL_MS" in ent and "effective - seen >= SEEN_WRITE_INTERVAL_MS" in ent,
          "入力欄を開くたびに設定を書き直さない（1 時間に 1 回まで）")

    print("\n=== 2. 配布フレーバー ===")
    check("const val REQUIRES_PURCHASE = true" in gate_play, "play: 購入で解錠する")
    check("const val TRIAL_DAYS = 5" in gate_play, "play: 試用は 5 日")
    check("const val REQUIRES_PURCHASE = false" in gate_gh, "github: 購入不要（協力者向けの全機能版）")
    check("if (!PremiumGate.REQUIRES_PURCHASE) return State.PREMIUM" in ent,
          "購入が要らない配布では常にプレミアム（試用の時計も回さない）")
    sig = lambda src: sorted(re.findall(r"\n    fun (\w+)\(", src))
    consts = lambda src: sorted(set(re.findall(r"const val (\w+)", src)) - {"PRODUCT_ID"})
    check(sig(gate_play) == sig(gate_gh) and sig(gate_play) == ["loadPrice", "purchase", "refresh"],
          "PremiumGate の公開関数が両フレーバーで揃っている")
    check(consts(gate_play) == consts(gate_gh), "PremiumGate の定数が両フレーバーで揃っている")
    check('"playImplementation"("com.android.billingclient:billing:' in gradle,
          "課金ライブラリは play フレーバーにだけ入れる")
    check("billingclient" not in "".join(
        read(os.path.join(d, f)) for d in (KT, KT_GITHUB) for f in os.listdir(d) if f.endswith(".kt")),
        "main と github は課金ライブラリを参照しない")
    check(not re.search(r"[¥￥]\s*\d|\d+\s*円", gate_play + ent),
          "価格をコードに書いていない（Play Console で決め、表示は Play の値を使う）")
    check("ProductType.INAPP" in gate_play and "ProductType.SUBS" not in gate_play,
          "買い切り（1 回限りのアイテム）で、サブスクではない")
    check("acknowledgePurchase" in gate_play, "購入を承認している（しないと 3 日で自動払い戻し）")
    check("PurchaseState.PURCHASED" in gate_play,
          "支払い待ち（PENDING）では鍵を開けない")
    check("if (result.isOk()) applyPurchases(app, purchases)" in gate_play,
          "問い合わせに失敗したときは前回の結果を変えない（圏外で鍵が閉じない）")

    print("\n=== 3. 入力中は通信しない ===")
    check("PremiumGate" not in code(ime), "IME サービスは Google Play へ問い合わせない")
    check("PremiumGate" not in code(read(os.path.join(KT, "UniStrokeView.kt"))),
          "入力ビューも問い合わせない")
    check("PremiumGate.refresh" not in code(ent) and "PremiumGate.purchase" not in code(ent),
          "Entitlement は設定を読むだけ")
    for name in ("MainActivity.kt", "SettingsActivity.kt"):
        check("PremiumGate.refresh(this)" in read(os.path.join(KT, name)),
              "%s を開いたときに購入状態を確かめる（復元を兼ねる）" % name)

    print("\n=== 4. 閉じる機能・閉じない機能 ===")
    check("inputView?.voiceAvailable = premiumUnlocked &&" in ime, "音声入力はプレミアム")
    check("if (!premiumUnlocked) return\n        if (!Prefs.isVoiceInputEnabled(this)) return" in ime,
          "長押しからも始められない")
    check("if (!premiumUnlocked) return\n        learner.onCharacter(" in ime,
          "新しい書き癖の学習はプレミアム")
    dic = read(os.path.join(KT, "OnDeviceDictionary.kt"))
    check("if (Entitlement.isUnlocked(app)) {\n                mapFromFile(extFile(app))" in dic,
          "拡張辞書はプレミアム")
    check("mapFromAssets(app)?.let { return it }" in dic, "コア辞書は鍵に関係なく開く")
    check("Prefs.KEY_PREMIUM_PURCHASED -> syncEntitlement()" in ime,
          "購入・復元の直後に IME へ反映する")
    view = read(os.path.join(KT, "UniStrokeView.kt"))
    check(ime.count("premiumNotice = !premiumUnlocked") == 2,
          "試用終了後は手書きゾーンの背景に案内を出す（ビュー生成時と鍵の引き直し時）")
    check("if (premiumNotice && !voiceActive)" in view and "PREMIUM_NOTICE_LINES" in view,
          "案内は背景の透かしとして描く")
    setter = view.split("var premiumNotice: Boolean = false")[1].split("\n    /**")[0]
    check("invalidate()" in setter and "requestLayout" not in setter,
          "案内の出し入れでレイアウトを動かさない（寸法が変わるとストロークを壊す）")
    check(len(re.findall(r"\bpremiumUnlocked\b", code(ime))) <= 10,
          "鍵を見る場所が増えていない（基本の入力に鍵を掛けない。増やすときはここを直す）")
    check("OnDeviceConverter.reset()" in ent, "鍵が変わったら辞書を開き直す")

    print("\n=== 5. Google Play 版の配布条件 ===")
    net_play = read(os.path.join(KT_PLAY, "NetConvertGate.kt"))
    net_gh = read(os.path.join(KT_GITHUB, "NetConvertGate.kt"))
    check("const val SUPPORTED = false" in net_play, "play: ネット変換を提供しない")
    check("const val SUPPORTED = true" in net_gh, "github: ネット変換は従来どおり（既定オフのオプトイン）")
    prefs = read(os.path.join(KT, "Prefs.kt"))
    check("NetConvertGate.SUPPORTED && of(context).getBoolean(KEY_NET_CONVERT, false)" in prefs,
          "提供しない配布では、保存値に関係なくネット変換は無効")
    check("!NetConvertGate.SUPPORTED || of(context).getBoolean(KEY_NET_CONVERT_ASKED, false)" in prefs,
          "提供しない配布では初回の可否確認を出さない")
    client = read(os.path.join(KT, "GoogleConvertClient.kt"))
    guards = re.findall(r"fun (convert|suggest)\(reading: String, onResult: [^\n]+\{\n"
                        r"        //[^\n]*\n        if \(!NetConvertGate\.SUPPORTED\) \{\n"
                        r"            onResult\(null\)\n            return", client)
    check(sorted(guards) == ["convert", "suggest"],
          "通信する 2 つの入口（変換・サジェスト）がどちらも最初に歯止めを見る")
    check("R.id.section_net_convert" in read(os.path.join(KT, "SettingsActivity.kt")),
          "設定画面のネット変換・変換エンジンは提供しない配布で隠す")
    check('applicationId = "io.github.makiiii_git.unistroke"' in gradle,
          "play: アプリケーション ID（Play へ上げたら変えられない）")
    check(re.search(r'defaultConfig \{\s*applicationId = "com\.unistroke\.ime"', gradle) is not None,
          "github: 従来の ID のまま（既存の利用者が上書き更新できる）")
    play_block = gradle.split('create("play") {')[1].split("}")[0]
    gh_block = gradle.split('create("github") {')[1].split("}")[0]
    check('findByName("playUpload")' in play_block and 'findByName("release")' in gh_block,
          "署名鍵は配布経路ごとに別（play = アップロード鍵、github = release 鍵）")
    release_type = gradle.split("buildTypes {")[1].split("compileOptions")[0]
    check("signingConfig =" not in code(release_type),
          "buildTypes 側で署名を指定していない（指定するとフレーバーの鍵より優先される）")
    check(not re.search(r"(?i)password\s*=\s*\"[^\"$]", gradle + read(os.path.join(ROOT, "tools", "play-env.sh"))),
          "パスワードをリポジトリに書いていない")

    print()
    if FAILURES:
        print("test_entitlement: %d FAILED" % len(FAILURES))
        return 1
    print("test_entitlement: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
