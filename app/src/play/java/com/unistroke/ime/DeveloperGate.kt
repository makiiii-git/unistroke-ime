package com.unistroke.ime

/**
 * 設定画面の「開発者向け」の節を出すかどうか（**play フレーバー**）。
 *
 * Google Play 版は一般の利用者向けなので、デバッグログのような開発者向けの項目は
 * 既定で隠す。必要なときは隠しコマンド（設定画面の「ライセンス」見出しを 5 回連打）で
 * 出せる（[SettingsActivity]）。
 *
 * 形（定数）は `src/github` 側と揃えること。
 */
object DeveloperGate {

    /** 開発者向けの節を既定で隠す。隠しコマンドで表示を切り替える。 */
    const val HIDDEN_BY_DEFAULT = true
}
