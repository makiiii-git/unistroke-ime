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

    /** 入っている拡張辞書の版。0 / 未設定なら拡張辞書なし。 */
    const val KEY_DICT_VERSION = "dict_version"

    /** 拡張辞書の更新を自動で確認するか（既定オフ）。 */
    const val KEY_DICT_AUTO_UPDATE = "dict_auto_update"

    /** 最後に更新を確認した時刻。頻繁な問い合わせを抑えるために持つ。 */
    const val KEY_DICT_LAST_CHECK = "dict_last_check"

    /** 拡張辞書の取得をセットアップで一度案内したか。 */
    const val KEY_DICT_PROMPTED = "dict_prompted"

    /** アプリ本体の更新を自動で確認するか（既定オン）。 */
    const val KEY_APP_AUTO_UPDATE = "app_auto_update"

    /** 最後にアプリの更新を確認した時刻。 */
    const val KEY_APP_LAST_CHECK = "app_last_check"

    /** 大画面でパネルを寄せた側（[SIDE_LEFT] / [SIDE_RIGHT]）。未設定なら利き手側。 */
    const val KEY_PANEL_SIDE = "panel_side"

    /** 大画面でのパネル上下位置（0 = 最上 / 1 = 最下）。 */
    const val KEY_PANEL_Y = "panel_y"

    const val HAND_RIGHT = "right"
    const val HAND_LEFT = "left"

    const val SIDE_LEFT = "left"
    const val SIDE_RIGHT = "right"

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
            // 利き手を変えたら、大画面のパネルも新しい利き手側へ戻す。
            // 以前ドラッグして置いた側に居座ると、設定を変えたのに効かないように見える。
            .remove(KEY_PANEL_SIDE)
            .apply()
    }

    /**
     * 大画面でパネルを左端側へ置くか。
     * ユーザーがドラッグで決めた側があればそれを、無ければ利き手側を使う。
     */
    fun panelOnLeft(context: Context, leftHanded: Boolean): Boolean =
        when (of(context).getString(KEY_PANEL_SIDE, null)) {
            SIDE_LEFT -> true
            SIDE_RIGHT -> false
            else -> leftHanded
        }

    /** 大画面でのパネル上下位置。既定は最下（＝従来と同じ画面下）。 */
    fun panelYRatio(context: Context): Float =
        of(context).getFloat(KEY_PANEL_Y, 1f).coerceIn(0f, 1f)

    fun setPanelPosition(context: Context, onLeft: Boolean, yRatio: Float) {
        of(context).edit()
            .putString(KEY_PANEL_SIDE, if (onLeft) SIDE_LEFT else SIDE_RIGHT)
            .putFloat(KEY_PANEL_Y, yRatio.coerceIn(0f, 1f))
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
     * 拡張辞書の更新を自動で確認するか。既定は false。
     *
     * 有効でも定期的な通信は起こさない。アプリを開いたときに、前回から十分
     * 時間が経っていれば便乗して確認するだけ（[DictionaryUpdater.shouldAutoCheck]）。
     */
    fun isDictAutoUpdate(context: Context): Boolean =
        of(context).getBoolean(KEY_DICT_AUTO_UPDATE, false)

    fun setDictAutoUpdate(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_DICT_AUTO_UPDATE, enabled).apply()
    }

    /**
     * アプリ本体の更新確認。**既定はオン**。
     *
     * 入力中（IME サービス）からは絶対に走らせない。アプリの画面を開いたときだけ、
     * 1 日 1 回を上限に GitHub のリリース情報を見に行く。
     * 送るのは「最新版は何か」という問い合わせだけで、入力内容は含まれない。
     */
    fun isAppAutoUpdate(context: Context): Boolean =
        of(context).getBoolean(KEY_APP_AUTO_UPDATE, true)

    fun setAppAutoUpdate(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_APP_AUTO_UPDATE, enabled).apply()
    }

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
