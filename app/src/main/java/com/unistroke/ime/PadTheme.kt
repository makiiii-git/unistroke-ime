package com.unistroke.ime

import android.content.Context

/**
 * キーボードの配色（テーマ）。プレミアム。
 *
 * 既定の「Classic」は res/values/colors.xml の pad_* そのもの。ダークモードのときは
 * res/values-night/colors.xml の冷色系（スチールブルー）に自動で切り替わる。ほかのテーマは
 * 同じ 8 色の役割を置き換えるだけで、寸法や配置には触らない
 * （レイアウトが変わるとストロークの座標がずれるため）。
 *
 * 試用が終わって未購入なら、選んでいても Classic で描く（[palette]）。
 */
object PadTheme {

    const val CLASSIC = "classic"

    /** PDA の液晶画面（明るいオリーブの地に黒いインク）。 */
    const val LCD = "lcd"

    /** 黒に近い地と青いインク。 */
    const val MIDNIGHT = "midnight"

    /** 白い紙と青いインク。 */
    const val PAPER = "paper"

    /** 設定画面に並べる順。 */
    val IDS = listOf(CLASSIC, LCD, MIDNIGHT, PAPER)

    class Palette(
        val bezel: Int,
        val status: Int,
        val zoneAlpha: Int,
        val zoneNumber: Int,
        val divider: Int,
        val hint: Int,
        val text: Int,
        val ink: Int,
    ) {
        /** pad_* の色リソースを、このテーマの色に読み替える。 */
        fun color(id: Int): Int = when (id) {
            R.color.pad_bezel -> bezel
            R.color.pad_status -> status
            R.color.pad_zone_alpha -> zoneAlpha
            R.color.pad_zone_number -> zoneNumber
            R.color.pad_divider -> divider
            R.color.pad_hint -> hint
            R.color.pad_text -> text
            R.color.pad_ink -> ink
            else -> throw IllegalArgumentException("pad_* 以外の色: $id")
        }
    }

    /** 設定に保存されたテーマ（知らない値なら Classic）。鍵は見ない。 */
    fun selected(context: Context): String {
        val id = Prefs.of(context).getString(Prefs.KEY_PAD_THEME, CLASSIC) ?: CLASSIC
        return if (id in IDS) id else CLASSIC
    }

    fun select(context: Context, id: String) {
        Prefs.of(context).edit().putString(Prefs.KEY_PAD_THEME, id).apply()
    }

    /** いま描くテーマ。試用が終わって未購入なら、選んでいても Classic。 */
    fun current(context: Context): String =
        if (Entitlement.isUnlocked(context)) selected(context) else CLASSIC

    fun palette(context: Context): Palette = paletteOf(context, current(context))

    fun paletteOf(context: Context, id: String): Palette = when (id) {
        LCD -> Palette(
            bezel = 0xFF8E9B7D.toInt(),
            status = 0xFF7F8C6F.toInt(),
            zoneAlpha = 0xFFB3C19F.toInt(),
            zoneNumber = 0xFFA5B391.toInt(),
            divider = 0xFF5E6B4D.toInt(),
            hint = 0xFF6F7D5E.toInt(),
            text = 0xFF1E2816.toInt(),
            ink = 0xFF18210F.toInt(),
        )
        MIDNIGHT -> Palette(
            bezel = 0xFF111318.toInt(),
            status = 0xFF0B0D11.toInt(),
            zoneAlpha = 0xFF1D212A.toInt(),
            zoneNumber = 0xFF171A22.toInt(),
            divider = 0xFF4C566A.toInt(),
            hint = 0xFF3D4555.toInt(),
            text = 0xFFD7DDE9.toInt(),
            ink = 0xFF7FB6FF.toInt(),
        )
        PAPER -> Palette(
            bezel = 0xFFE2DED6.toInt(),
            status = 0xFFD6D1C7.toInt(),
            zoneAlpha = 0xFFF8F6F1.toInt(),
            zoneNumber = 0xFFEDE9E1.toInt(),
            divider = 0xFF979186.toInt(),
            hint = 0xFFADA79B.toInt(),
            text = 0xFF2A2723.toInt(),
            ink = 0xFF2B5BC8.toInt(),
        )
        else -> Palette(
            bezel = context.getColor(R.color.pad_bezel),
            status = context.getColor(R.color.pad_status),
            zoneAlpha = context.getColor(R.color.pad_zone_alpha),
            zoneNumber = context.getColor(R.color.pad_zone_number),
            divider = context.getColor(R.color.pad_divider),
            hint = context.getColor(R.color.pad_hint),
            text = context.getColor(R.color.pad_text),
            ink = context.getColor(R.color.pad_ink),
        )
    }
}
