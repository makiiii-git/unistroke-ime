package com.unistroke.ime

import android.app.Activity

/**
 * アプリ本体の自己更新への入口（**github フレーバー**）。
 *
 * 自己更新（GitHub Releases から APK を取得してインストーラーへ渡す）は
 * 配布経路によって可否が変わる:
 *
 *   - github … 自前配布なので自分で更新を届ける必要がある → ここ（実体あり）
 *   - play   … 更新は Google Play が配る。APK を自分で取得・インストールするのは
 *              Play のポリシーで禁止 → `src/play` 側の同名オブジェクト（何もしない）
 *
 * `main` のコードは [AppUpdater] / [AppUpdateUi] を直接参照せず、必ずここを通す。
 * そうしておけば play 版の APK には更新のコードも権限も一切入らない。
 */
object AppUpdateGate {

    /** この配布形態で自己更新を提供するか。設定画面の「アプリの更新」節の表示に使う。 */
    const val SUPPORTED = true

    /** 画面を開いたときの便乗確認。間隔と設定は [AppUpdater.shouldAutoCheck] が見る。 */
    fun autoCheck(activity: Activity) {
        if (!AppUpdater.shouldAutoCheck(activity, System.currentTimeMillis())) return
        AppUpdateUi.checkAndOffer(activity, manual = false)
    }

    /** 設定画面からの手動確認。「最新です」も失敗も画面に出す。 */
    fun checkManually(activity: Activity, onDone: () -> Unit) {
        AppUpdateUi.checkAndOffer(activity, manual = true, onDone = onDone)
    }
}
