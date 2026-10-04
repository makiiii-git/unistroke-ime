package com.unistroke.ime

/**
 * ネット変換（読みを外部の変換サービスへ送る）を提供するか（**play フレーバー**）。
 *
 * Google Play 版では提供しない。入力アプリが入力内容（読み）を外部へ送る経路を
 * 持たないことで、「データを収集しない・共有しない」と言い切れる状態にしておく。
 * 変換は端末内辞書だけで行う。
 *
 * false のとき:
 *   - [Prefs.isNetworkConvertEnabled] は保存値に関係なく false を返す
 *   - [GoogleConvertClient] は通信せずに「結果なし」を返す（二重の歯止め）
 *   - 初回の可否確認と、設定画面の「ネット変換」「変換エンジン」は出さない
 */
object NetConvertGate {
    const val SUPPORTED = false
}
