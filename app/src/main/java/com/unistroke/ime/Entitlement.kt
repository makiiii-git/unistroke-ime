package com.unistroke.ime

import android.content.Context

/**
 * プレミアム機能を使えるかどうかの判定（キーオープン型）。
 *
 * アプリは 1 本で、無料版・有料版には分けない。インストール直後は試用期間として
 * 全機能が開き、期間を過ぎるとプレミアム機能だけが閉じる。購入すると鍵が開く。
 * **基本の入力（ストローク・ローマ字かな・コア辞書）は試用後も閉じない** ――
 * キーボードが打てなくなると端末から締め出されるのと同じなので、そこは塞がない。
 *
 * 配布形態による違いは [PremiumGate]（フレーバーごとに実体が違う）に閉じ込めてある:
 *   - play   … 試用 [PremiumGate.TRIAL_DAYS] 日 → Google Play の購入で解錠
 *   - github … 協力者向けの全機能版。常に [State.PREMIUM]
 *
 * ### 通信について
 * ここは**設定を読むだけ**で、通信も Google Play への問い合わせもしない。
 * IME サービスが見るのはここだけ。購入状態の問い合わせ（[PremiumGate.refresh]）は
 * アプリの画面を開いたときにだけ走り、結果をここへ書き込む。
 */
object Entitlement {

    enum class State {
        /** 購入済み、または購入が要らない配布形態。 */
        PREMIUM,

        /** 試用期間中。全機能が使える。 */
        TRIAL,

        /** 試用期間が終わり、未購入。プレミアム機能は閉じる。 */
        EXPIRED,
    }

    /** 購入操作の結果。[PremiumGate.purchase] が返す。 */
    enum class PurchaseResult { PURCHASED, PENDING, CANCELED, UNAVAILABLE, FAILED }

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** 「最後に見た時刻」を書き直す最小間隔。入力欄を開くたびに設定を書かないため。 */
    const val SEEN_WRITE_INTERVAL_MS = 60L * 60 * 1000

    fun state(context: Context, now: Long = System.currentTimeMillis()): State {
        if (!PremiumGate.REQUIRES_PURCHASE) return State.PREMIUM
        if (Prefs.of(context).getBoolean(Prefs.KEY_PREMIUM_PURCHASED, false)) return State.PREMIUM
        return if (trialMsLeft(context, now) > 0) State.TRIAL else State.EXPIRED
    }

    /** プレミアム機能を使ってよいか（購入済み、または試用中）。 */
    fun isUnlocked(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        state(context, now) != State.EXPIRED

    /** 試用の残り日数（切り上げ）。終わっていれば 0。 */
    fun trialDaysLeft(context: Context, now: Long = System.currentTimeMillis()): Int =
        daysLeft(trialMsLeft(context, now))

    /**
     * 試用の残り時間（ミリ秒）。負なら終了済み。
     *
     * 試用は**最初にここが呼ばれた時刻**から始まる（アプリを開いたとき、または
     * IME を初めて出したとき）。端末の時計を巻き戻して延ばせないよう、
     * 「これまでに見た最も遅い時刻」を覚えておき、それより前には戻らないものとして扱う。
     */
    fun trialMsLeft(context: Context, now: Long): Long {
        val prefs = Prefs.of(context)
        var start = prefs.getLong(Prefs.KEY_TRIAL_START, 0L)
        val seen = prefs.getLong(Prefs.KEY_TRIAL_LAST_SEEN, 0L)
        val effective = maxOf(now, seen)
        if (start == 0L || effective - seen >= SEEN_WRITE_INTERVAL_MS) {
            if (start == 0L) start = effective
            prefs.edit()
                .putLong(Prefs.KEY_TRIAL_START, start)
                .putLong(Prefs.KEY_TRIAL_LAST_SEEN, effective)
                .apply()
        }
        return msLeft(start, effective, PremiumGate.TRIAL_DAYS)
    }

    /** 残り時間の計算だけを取り出したもの（test_entitlement.py が同じ式を写して検証する）。 */
    fun msLeft(start: Long, effectiveNow: Long, trialDays: Int): Long =
        start + trialDays * DAY_MS - effectiveNow

    fun daysLeft(msLeft: Long): Int =
        if (msLeft <= 0) 0 else ((msLeft + DAY_MS - 1) / DAY_MS).toInt()

    /**
     * 購入状態を記録する。値が変わったら true。
     *
     * 呼ぶのは [PremiumGate] だけ（Google Play が「持っている／いない」と答えたとき）。
     * 払い戻しで false に戻ることもある。問い合わせに失敗したときは呼ばない
     * （圏外で鍵が閉じないように、前回の答えをそのまま使う）。
     */
    fun setPurchased(context: Context, purchased: Boolean): Boolean {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(Prefs.KEY_PREMIUM_PURCHASED, false) == purchased) return false
        prefs.edit().putBoolean(Prefs.KEY_PREMIUM_PURCHASED, purchased).apply()
        // 拡張辞書を使う／使わないが切り替わるので、次の get() で開き直させる
        OnDeviceConverter.reset()
        return true
    }
}
