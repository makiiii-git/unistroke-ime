package com.unistroke.ime

import java.util.Calendar

/**
 * 日付の候補。「きょう」と書いたら今日の日付をいくつかの書式で候補に出す。
 *
 *   きょう -> 2026/10/06, 2026年10月6日, 10月6日, 10月6日(火), 2026-10-06, 令和8年10月6日
 *
 * 辞書の項目ではなく、読みが**完全に一致**したときにその場で組み立てる（日付は毎日変わる）。
 * 候補バー（自動候補）と文節編集モードの両方で、先頭の候補（ふつうは「今日」）の直後に差す。
 *
 * 履歴には積まない。覚えると翌日に古い日付が「きょう」の予測として出てしまうので、
 * 記録側は [containsGenerated] で弾く。
 *
 * 時刻は端末のタイムゾーン（[Calendar.getInstance]）で見る。「今日」はユーザーの時計基準。
 */
object DateCandidates {

    /** 読み -> 今日からの日数差。ここに足せば「あした」「きのう」も同じ仕組みで出る。 */
    private val OFFSETS: Map<String, Int> = mapOf(
        "きょう" to 0,
    )

    /** 曜日の一字。[Calendar.DAY_OF_WEEK] は日曜 = 1 なので添字は -1 する。 */
    private const val WEEKDAYS = "日月火水木金土"

    /** 日付候補を出す読みか。 */
    fun handles(reading: String): Boolean = reading in OFFSETS

    /**
     * [reading] に対応する日付の候補。該当しない読みなら空。
     * [now] は現在時刻（ミリ秒）。テストから差し替えられるように引数にしている。
     */
    fun forReading(reading: String, now: Long = System.currentTimeMillis()): List<String> {
        val offset = OFFSETS[reading] ?: return emptyList()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        if (offset != 0) cal.add(Calendar.DAY_OF_MONTH, offset)
        return formats(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.DAY_OF_WEEK),
        )
    }

    /**
     * 1 つの日付に対する書式の一覧。並び順がそのまま候補の並び。
     * [dayOfWeek] は [Calendar.DAY_OF_WEEK]（日曜 = 1 … 土曜 = 7）。
     *
     * 数字は Int.toString() で組む（String.format は端末のロケールで数字が変わりうる）。
     */
    fun formats(year: Int, month: Int, day: Int, dayOfWeek: Int): List<String> {
        val out = ArrayList<String>(6)
        val mm = two(month)
        val dd = two(day)
        out.add("$year/$mm/$dd")
        out.add("${year}年${month}月${day}日")
        out.add("${month}月${day}日")
        if (dayOfWeek in 1..7) {
            out.add("${month}月${day}日(${WEEKDAYS[dayOfWeek - 1]})")
        }
        out.add("$year-$mm-$dd")
        era(year, month, day)?.let { out.add(it) }
        return out
    }

    /**
     * [surface] に、いま生成する日付候補のどれかが含まれているか。
     * 履歴に積まないための判定。「きょうは」->「2026/10/06は」のように
     * 文の一部として確定した場合も弾けるよう、一致ではなく包含で見る。
     */
    fun containsGenerated(surface: String, now: Long): Boolean {
        if (surface.isEmpty()) return false
        for (reading in OFFSETS.keys) {
            for (d in forReading(reading, now)) {
                if (surface.contains(d)) return true
            }
        }
        return false
    }

    /**
     * 文節編集モードの文節に日付候補を差し込む。
     * 読みが一致する文節だけ、先頭候補の直後に（重複は除いて）足す。
     * 先頭は動かさないので、既定で選ばれる候補は変わらない。
     */
    fun insertInto(
        segments: List<GoogleConvertClient.Segment>,
        now: Long = System.currentTimeMillis(),
    ): List<GoogleConvertClient.Segment> {
        if (segments.none { handles(it.reading) }) return segments
        return segments.map { seg ->
            val dates = forReading(seg.reading, now)
            if (dates.isEmpty()) return@map seg
            val merged = ArrayList<String>(seg.candidates.size + dates.size)
            if (seg.candidates.isNotEmpty()) merged.add(seg.candidates[0])
            for (d in dates) if (d !in merged) merged.add(d)
            for (i in 1 until seg.candidates.size) {
                val c = seg.candidates[i]
                if (c !in merged) merged.add(c)
            }
            seg.copy(candidates = merged)
        }
    }

    /** 2 桁ゼロ詰め。 */
    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    /**
     * 和暦。令和（2019-05-01〜）と平成（1989-01-08〜）だけ扱い、それ以前は出さない。
     * 1 年目は「元年」。
     */
    private fun era(year: Int, month: Int, day: Int): String? {
        val ymd = year * 10000 + month * 100 + day
        val (name, firstYear) = when {
            ymd >= 20190501 -> "令和" to 2019
            ymd >= 19890108 -> "平成" to 1989
            else -> return null
        }
        val n = year - firstYear + 1
        val y = if (n == 1) "元" else n.toString()
        return "$name${y}年${month}月${day}日"
    }
}
