package com.unistroke.ime

/**
 * 設定画面の「開発者向け」の節を出すかどうか（**github フレーバー**）。
 *
 * GitHub 版は協力者向けの全機能版なので、開発者向けの項目は常に出す。
 * Google Play 版（`src/play` 側の同名オブジェクト）は既定で隠し、
 * 隠しコマンドで出す。
 */
object DeveloperGate {

    /** 常に表示する（隠しコマンドは要らない）。 */
    const val HIDDEN_BY_DEFAULT = false
}
