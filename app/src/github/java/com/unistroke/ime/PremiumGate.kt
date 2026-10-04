package com.unistroke.ime

import android.app.Activity
import android.content.Context

/**
 * プレミアムの鍵（**github フレーバー**）。
 *
 * GitHub で配る APK は協力者向けの全機能版で、試用期間も購入も無い。
 * [Entitlement.state] は常に PREMIUM を返し、設定画面の「プレミアム」節は出ない。
 * Google Play 版（`src/play`）とは別物として扱う。
 *
 * 形（定数と関数のシグネチャ）は `src/play` 側と揃えること。
 */
object PremiumGate {

    /** 購入しないと閉じる機能があるか。 */
    const val REQUIRES_PURCHASE = false

    /** 試用日数。購入が要らないので使われない。 */
    const val TRIAL_DAYS = 0

    @Suppress("UNUSED_PARAMETER")
    fun refresh(context: Context, onDone: () -> Unit = {}) = onDone()

    @Suppress("UNUSED_PARAMETER")
    fun loadPrice(context: Context, onPrice: (String?) -> Unit) = onPrice(null)

    @Suppress("UNUSED_PARAMETER")
    fun purchase(activity: Activity, onResult: (Entitlement.PurchaseResult) -> Unit) =
        onResult(Entitlement.PurchaseResult.UNAVAILABLE)
}
