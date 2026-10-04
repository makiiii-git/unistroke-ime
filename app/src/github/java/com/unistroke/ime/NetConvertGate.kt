package com.unistroke.ime

/**
 * ネット変換（読みを外部の変換サービスへ送る）を提供するか（**github フレーバー**）。
 *
 * GitHub で配る全機能版では、従来どおり利用者が設定で有効にしたときだけ使える（既定オフ）。
 *
 * false のとき:
 *   - [Prefs.isNetworkConvertEnabled] は保存値に関係なく false を返す
 *   - [GoogleConvertClient] は通信せずに「結果なし」を返す（二重の歯止め）
 *   - 初回の可否確認と、設定画面の「ネット変換」「変換エンジン」は出さない
 */
object NetConvertGate {
    const val SUPPORTED = true
}
