package com.unistroke.ime

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * アプリの言語（日本語版 / 英語版）の切り替え。
 *
 * 言語は表示だけでなく IME の動きも決める。
 *
 *   日本語版 … ローマ字かな入力・かな漢字変換・左上のボタンは「abc ⇄ かな」
 *   英語版   … 英字入力のみ・候補バーは英単語の予測・左上のボタンは単語削除
 *
 * 設定は [Prefs.language]。既定の [Prefs.LANG_AUTO] では端末の言語に合わせ、
 * 日本語の端末なら日本語版、それ以外はすべて英語版になる
 * （リソースも values = 英語 / values-ja = 日本語 の 2 つだけ）。
 *
 * 端末の言語と違う言語を選べるように、文字列は [wrap] で言語を差し替えた
 * Context から引く。画面（Activity）は attachBaseContext で丸ごと差し替え、
 * 常駐する IME サービスは文字列を引くときだけ使う（サービス自身の Context は
 * 差し替えない ―― 画面サイズなどの構成変更をそのまま受け取るため）。
 */
object AppLanguage {

    /**
     * 設定値と端末の言語から、実際に使う言語（[Prefs.LANG_JA] / [Prefs.LANG_EN]）を決める。
     * Android に依存しない純粋な関数（test_english.py が同じ規則を検証する）。
     */
    fun resolve(setting: String, systemLanguage: String): String = when (setting) {
        Prefs.LANG_JA -> Prefs.LANG_JA
        Prefs.LANG_EN -> Prefs.LANG_EN
        else -> if (systemLanguage == Prefs.LANG_JA) Prefs.LANG_JA else Prefs.LANG_EN
    }

    /** いま使う言語（[Prefs.LANG_JA] / [Prefs.LANG_EN]）。 */
    fun current(context: Context): String =
        resolve(Prefs.language(context), systemLocale().language)

    /** 英語版として動くか。 */
    fun isEnglish(context: Context): Boolean = current(context) == Prefs.LANG_EN

    /**
     * 端末そのものの言語。アプリ側の差し替えの影響を受けないよう、
     * アプリの Context ではなくシステムのリソースから読む。
     */
    private fun systemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.ENGLISH else locales[0]
    }

    /**
     * 表示に使うロケール。
     *
     * 端末の言語がそのまま使えるときは端末のものを返す（en-GB などの地域を保つ）。
     * 英語版を日本語の端末で使う、といった食い違いのときだけ既定の地域へ落とす。
     */
    fun locale(context: Context): Locale {
        val system = systemLocale()
        return when (current(context)) {
            Prefs.LANG_JA -> if (system.language == Prefs.LANG_JA) system else Locale.JAPAN
            else -> if (system.language == Prefs.LANG_EN) system else Locale.US
        }
    }

    /** 音声認識へ渡す言語タグ（例: ja-JP / en-US）。 */
    fun speechTag(context: Context): String = locale(context).toLanguageTag()

    /**
     * 言語を差し替えた Context を返す。
     *
     * 差し替えるのは言語だけ（空の Configuration にロケールだけ入れて重ねる）。
     * 画面サイズや向きまで固定してしまうと、折りたたみの開閉に追随しなくなる。
     */
    fun wrap(base: Context): Context {
        val config = Configuration()
        config.setLocale(locale(base))
        return base.createConfigurationContext(config)
    }
}
