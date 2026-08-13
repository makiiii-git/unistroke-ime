package com.unistroke.ime

import android.content.Context

/**
 * 端末に入っているビルドの識別。
 *
 * 「新しい APK を入れたつもりが、実は古いものが残っていた」という事故を
 * 目視で潰すためだけの仕組み。設定画面・MainActivity・IME のジェスチャー見本の
 * 3 か所に同じ値を出すので、**IME しか起動していない状況でも**確認できる。
 *
 * 値の作り方は app/build.gradle.kts の sourceBuildStamp() を参照
 * （ソースの最終更新時刻。毎回変わらないので増分ビルドを壊さない）。
 */
object BuildInfo {

    /** 例: 「バージョン: 1.0 (build 2026-08-11 10:43)」 */
    fun label(context: Context): String =
        context.getString(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME)

    /** 描画エリアの隅に置く短い形。例: 「v1.0 / 2026-08-11 10:43」 */
    fun shortLabel(context: Context): String =
        context.getString(
            R.string.build_info_short, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME,
        )
}
