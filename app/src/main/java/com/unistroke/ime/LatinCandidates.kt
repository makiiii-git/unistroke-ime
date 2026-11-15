package com.unistroke.ime

import java.util.Locale

/**
 * 打った綴り（ローマ字）そのものを候補に出す。
 *
 * 「かいしゃ」と書いたとき、候補の末尾に `kaisha` / `Kaisha` / `KAISHA` を並べる。
 * かなモードのまま英単語を 1 語だけ入れたいとき（abc へ切り替えて戻す手間を省く）のためのもの。
 * 並びは「打ったまま → 先頭だけ大文字 → 全部大文字」。
 *
 * Android 依存を持たない純粋な計算なので、そのままテストできる。
 */
object LatinCandidates {

    /**
     * 生の綴り [raw] から作る英字の候補。英字の綴りでなければ空。
     * 同じ表記は重ねない（1 文字の "a" は "a" / "A" の 2 つになる）。
     */
    fun forRaw(raw: String): List<String> {
        if (!isSpelling(raw)) return emptyList()
        val lower = raw.lowercase(Locale.ROOT)
        val forms = listOf(
            raw,
            lower.replaceFirstChar { it.uppercaseChar() },
            raw.uppercase(Locale.ROOT),
        )
        val out = ArrayList<String>(forms.size)
        for (f in forms) if (f !in out) out.add(f)
        return out
    }

    /**
     * 英字の綴りか。英字と、長音に使う `-` だけでできていて、英字を 1 つは含む。
     * 合成に記号（「、」など）が混じった単語は対象にしない。
     */
    fun isSpelling(s: String): Boolean =
        s.any { isLetter(it) } && s.all { isLetter(it) || it == '-' }

    private fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * 文節編集モードの候補へ英字を足す。
     *
     * 読み全体が 1 文節のままで、その読みが [raw] をかなにしたもの（[rawReading]）と
     * 一致するときだけ、候補の末尾に足す。2 文節以上に切れたときは、文節ごとの綴りを
     * 取り出せないので足さない。既定で選ばれる先頭候補は動かさない。
     */
    fun insertInto(
        segments: List<GoogleConvertClient.Segment>,
        raw: String,
        rawReading: String,
    ): List<GoogleConvertClient.Segment> {
        if (segments.size != 1) return segments
        val seg = segments[0]
        if (seg.reading != rawReading) return segments
        val forms = forRaw(raw)
        if (forms.isEmpty()) return segments
        val merged = ArrayList<String>(seg.candidates.size + forms.size)
        merged.addAll(seg.candidates)
        for (f in forms) if (f !in merged) merged.add(f)
        return listOf(seg.copy(candidates = merged))
    }
}
