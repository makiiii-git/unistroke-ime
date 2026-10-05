package com.unistroke.ime

import android.app.Activity
import android.content.Context

/**
 * アプリの言語（日本語版 / 英語版）で表示する画面の土台。
 *
 * 端末の言語と違う言語を設定で選べるので、画面の Context を
 * [AppLanguage.wrap] で差し替えてから組み立てる。レイアウトの文字列も
 * getString もここを通るので、各画面は言語を意識しなくてよい。
 * 設定で言語を変えたときは、その画面を recreate() すれば新しい言語で作り直される。
 */
open class LocalizedActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
