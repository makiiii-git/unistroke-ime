package com.unistroke.ime

import android.app.Activity

/**
 * アプリ本体の自己更新への入口（**play フレーバー**）。
 *
 * Google Play 配布では更新は Play が配る。アプリが自分で APK を取得して
 * インストールさせるのは Play のポリシーで禁止されているので、こちらは何もしない。
 * 更新の実体（AppUpdater / AppUpdateUi）、`REQUEST_INSTALL_PACKAGES`、FileProvider は
 * `src/github` にだけあり、play 版の APK には含まれない。
 *
 * 形（定数と関数のシグネチャ）は `src/github` 側と揃えること。
 */
object AppUpdateGate {

    /** 自己更新は提供しない。設定画面の「アプリの更新」節は隠す。 */
    const val SUPPORTED = false

    @Suppress("UNUSED_PARAMETER")
    fun autoCheck(activity: Activity) = Unit

    fun checkManually(@Suppress("UNUSED_PARAMETER") activity: Activity, onDone: () -> Unit) = onDone()
}
