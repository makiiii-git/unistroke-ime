package com.unistroke.ime

import android.content.Context
import android.content.SharedPreferences

/** アプリ全体で共有する設定。キー名を 1 箇所に集約する。 */
object Prefs {

    const val NAME = "unistroke"

    const val KEY_HANDEDNESS = "handedness"
    const val KEY_LAST_INPUT_MODE = "last_input_mode"
    const val KEY_TRAINING_PROMPTED = "training_prompted"

    /** ネット変換のオプトイン状態。 */
    const val KEY_NET_CONVERT = "net_convert"

    /** 一度でも可否を尋ねたか（初回プロンプトを 1 回だけ出すため）。 */
    const val KEY_NET_CONVERT_ASKED = "net_convert_asked"

    /** 変換エンジンの選択（[ENGINE_AUTO] / [ENGINE_ONDEVICE]）。 */
    const val KEY_CONVERT_ENGINE = "convert_engine"

    /** 速書きの調査用ログ（既定オフ）。 */
    const val KEY_DEBUG_STROKES = "debug_strokes"

    const val HAND_RIGHT = "right"
    const val HAND_LEFT = "left"

    /** オンライン優先。失敗・タイムアウト時は端末内へ落とす。 */
    const val ENGINE_AUTO = "auto"

    /** 常に端末内辞書だけで変換する。 */
    const val ENGINE_ONDEVICE = "ondevice"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isLeftHanded(context: Context): Boolean =
        of(context).getString(KEY_HANDEDNESS, HAND_RIGHT) == HAND_LEFT

    fun setLeftHanded(context: Context, left: Boolean) {
        of(context).edit()
            .putString(KEY_HANDEDNESS, if (left) HAND_LEFT else HAND_RIGHT)
            .apply()
    }

    /**
     * ネット変換（外部サーバへ読みを送る漢字変換）が有効か。
     *
     * 既定は false。ユーザーが明示的に有効化するまで、この IME は一切通信しない。
     * 変換・予測は履歴と内蔵辞書だけで動く。
     */
    fun isNetworkConvertEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_NET_CONVERT, false)

    /** 可否を記録する。尋ねた事実も同時に立てるので、初回プロンプトは二度出ない。 */
    fun setNetworkConvertEnabled(context: Context, enabled: Boolean) {
        of(context).edit()
            .putBoolean(KEY_NET_CONVERT, enabled)
            .putBoolean(KEY_NET_CONVERT_ASKED, true)
            .apply()
    }

    fun wasNetworkConvertAsked(context: Context): Boolean =
        of(context).getBoolean(KEY_NET_CONVERT_ASKED, false)

    /**
     * 変換エンジンの選択。既定は [ENGINE_AUTO]（オンライン優先・失敗したら端末内）。
     *
     * ネット変換自体がオフなら、この値に関わらず端末内だけで動く。
     * つまり通信するのは「ネット変換オン」かつ「エンジンが自動」のときだけ。
     */
    fun convertEngine(context: Context): String =
        of(context).getString(KEY_CONVERT_ENGINE, ENGINE_AUTO) ?: ENGINE_AUTO

    fun setConvertEngine(context: Context, engine: String) {
        of(context).edit().putString(KEY_CONVERT_ENGINE, engine).apply()
    }

    fun isOnDeviceOnly(context: Context): Boolean =
        convertEngine(context) == ENGINE_ONDEVICE

    /**
     * ストロークのデバッグログを出すか（既定オフ）。
     * 点数・所要時間・スコア・棄却理由を logcat（タグ UniStroke）へ出す。
     * 速書きの認識不良を実機で調べるための開発者向けスイッチ。
     */
    fun isDebugStrokes(context: Context): Boolean =
        of(context).getBoolean(KEY_DEBUG_STROKES, false)

    fun setDebugStrokes(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_DEBUG_STROKES, enabled).apply()
    }
}
