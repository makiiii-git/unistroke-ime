package com.unistroke.ime

/**
 * ローマ字 -> かな の逐次変換。
 *
 * IME からは「今までに溜めたローマ字バッファ」を渡し、
 * 確定できた先頭部分（[Result.kana]）と、まだ確定できない末尾（[Result.pending]）を受け取る。
 * 純粋関数なので単体テストしやすい。
 *
 * 「ん」の扱い（合成中の視認性を優先した仕様）:
 *   - 明示的な「ん」は **"nn" のみ**。単独の "n" は合成中も "n" と表示する
 *   - 確定・変換要求のときに末尾へ単独の "n" が残っていたら、Latin の "n" として扱う
 *     （"kan" + スペース -> 「かn」。「かん」が欲しければ "kann" と打つ）
 *   - ただし "n + 子音 -> ん" の先読みは維持する（"kondo" -> 「こんど」）。
 *     子音が来るまでの表示は "n" のまま（"こn" -> "こんd" -> "こんど"）
 *
 * 漢字変換は行わない（将来の拡張）。
 */
object RomajiConverter {

    /**
     * @param kana    確定したかな（そのまま commitText してよい）
     * @param pending 未確定のローマ字（setComposingText で表示する）
     */
    data class Result(val kana: String, val pending: String)

    /**
     * テーブル中の最長キー長。定数で持つと "xtsu"/"ltsu" のような 4 文字キーが
     * 永久にマッチしなくなるので、必ずテーブルから求める。
     */
    private val MAX_KEY: Int by lazy { TABLE.keys.maxOf { it.length } }

    private val TABLE: Map<String, String> = buildMap {
        // 母音
        put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")
        // か行
        put("ka", "か"); put("ki", "き"); put("ku", "く"); put("ke", "け"); put("ko", "こ")
        put("kya", "きゃ"); put("kyi", "きぃ"); put("kyu", "きゅ"); put("kye", "きぇ"); put("kyo", "きょ")
        put("ca", "か"); put("cu", "く"); put("co", "こ")
        put("qa", "くぁ"); put("qi", "くぃ"); put("qu", "く"); put("qe", "くぇ"); put("qo", "くぉ")
        // が行
        put("ga", "が"); put("gi", "ぎ"); put("gu", "ぐ"); put("ge", "げ"); put("go", "ご")
        put("gya", "ぎゃ"); put("gyi", "ぎぃ"); put("gyu", "ぎゅ"); put("gye", "ぎぇ"); put("gyo", "ぎょ")
        // さ行
        put("sa", "さ"); put("si", "し"); put("su", "す"); put("se", "せ"); put("so", "そ")
        put("shi", "し"); put("sha", "しゃ"); put("shu", "しゅ"); put("she", "しぇ"); put("sho", "しょ")
        put("sya", "しゃ"); put("syi", "しぃ"); put("syu", "しゅ"); put("sye", "しぇ"); put("syo", "しょ")
        // ざ行
        put("za", "ざ"); put("zi", "じ"); put("zu", "ず"); put("ze", "ぜ"); put("zo", "ぞ")
        put("ji", "じ"); put("ja", "じゃ"); put("ju", "じゅ"); put("je", "じぇ"); put("jo", "じょ")
        put("jya", "じゃ"); put("jyi", "じぃ"); put("jyu", "じゅ"); put("jye", "じぇ"); put("jyo", "じょ")
        put("zya", "じゃ"); put("zyi", "じぃ"); put("zyu", "じゅ"); put("zye", "じぇ"); put("zyo", "じょ")
        // た行
        put("ta", "た"); put("ti", "ち"); put("tu", "つ"); put("te", "て"); put("to", "と")
        put("chi", "ち"); put("tsu", "つ")
        put("cha", "ちゃ"); put("chu", "ちゅ"); put("che", "ちぇ"); put("cho", "ちょ")
        put("cya", "ちゃ"); put("cyi", "ちぃ"); put("cyu", "ちゅ"); put("cye", "ちぇ"); put("cyo", "ちょ")
        put("tya", "ちゃ"); put("tyi", "ちぃ"); put("tyu", "ちゅ"); put("tye", "ちぇ"); put("tyo", "ちょ")
        put("tha", "てゃ"); put("thi", "てぃ"); put("thu", "てゅ"); put("the", "てぇ"); put("tho", "てょ")
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        // だ行
        put("da", "だ"); put("di", "ぢ"); put("du", "づ"); put("de", "で"); put("do", "ど")
        put("dya", "ぢゃ"); put("dyi", "ぢぃ"); put("dyu", "ぢゅ"); put("dye", "ぢぇ"); put("dyo", "ぢょ")
        put("dha", "でゃ"); put("dhi", "でぃ"); put("dhu", "でゅ"); put("dhe", "でぇ"); put("dho", "でょ")
        // な行
        put("na", "な"); put("ni", "に"); put("nu", "ぬ"); put("ne", "ね"); put("no", "の")
        put("nya", "にゃ"); put("nyi", "にぃ"); put("nyu", "にゅ"); put("nye", "にぇ"); put("nyo", "にょ")
        // は行
        put("ha", "は"); put("hi", "ひ"); put("hu", "ふ"); put("he", "へ"); put("ho", "ほ")
        put("hya", "ひゃ"); put("hyi", "ひぃ"); put("hyu", "ひゅ"); put("hye", "ひぇ"); put("hyo", "ひょ")
        put("fu", "ふ"); put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("fya", "ふゃ"); put("fyu", "ふゅ"); put("fyo", "ふょ")
        // ば行 / ぱ行
        put("ba", "ば"); put("bi", "び"); put("bu", "ぶ"); put("be", "べ"); put("bo", "ぼ")
        put("bya", "びゃ"); put("byi", "びぃ"); put("byu", "びゅ"); put("bye", "びぇ"); put("byo", "びょ")
        put("pa", "ぱ"); put("pi", "ぴ"); put("pu", "ぷ"); put("pe", "ぺ"); put("po", "ぽ")
        put("pya", "ぴゃ"); put("pyi", "ぴぃ"); put("pyu", "ぴゅ"); put("pye", "ぴぇ"); put("pyo", "ぴょ")
        // ま行
        put("ma", "ま"); put("mi", "み"); put("mu", "む"); put("me", "め"); put("mo", "も")
        put("mya", "みゃ"); put("myi", "みぃ"); put("myu", "みゅ"); put("mye", "みぇ"); put("myo", "みょ")
        // や行
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("yi", "い"); put("ye", "いぇ")
        // ら行
        put("ra", "ら"); put("ri", "り"); put("ru", "る"); put("re", "れ"); put("ro", "ろ")
        put("rya", "りゃ"); put("ryi", "りぃ"); put("ryu", "りゅ"); put("rye", "りぇ"); put("ryo", "りょ")
        // わ行
        put("wa", "わ"); put("wo", "を"); put("wi", "うぃ"); put("we", "うぇ"); put("wu", "う")
        // ゔ
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        // 小書き
        put("xa", "ぁ"); put("xi", "ぃ"); put("xu", "ぅ"); put("xe", "ぇ"); put("xo", "ぉ")
        put("la", "ぁ"); put("li", "ぃ"); put("lu", "ぅ"); put("le", "ぇ"); put("lo", "ぉ")
        put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ")
        put("lya", "ゃ"); put("lyu", "ゅ"); put("lyo", "ょ")
        put("xtu", "っ"); put("ltu", "っ"); put("xtsu", "っ"); put("ltsu", "っ")
        put("xwa", "ゎ"); put("lwa", "ゎ")
        // 撥音（"nn" だけは次の 1 文字を見て解釈を変えるので TABLE には置かない）
        put("n'", "ん"); put("xn", "ん")
        // 長音
        put("-", "ー")
    }

    /** テーブルのどれかの接頭辞になりうるか（= まだ入力途中でありうるか）。 */
    private val PREFIXES: Set<String> = buildSet {
        for (k in TABLE.keys) {
            for (i in 1 until k.length) add(k.substring(0, i))
        }
        // "nn" は次の 1 文字が来るまで解釈を保留する
        add("n")
        add("nn")
    }

    private const val VOWELS = "aiueo"

    /**
     * 「ん」を明示的に入力する綴り。
     *
     * 単独の "n" を即「ん」に見せると合成中の見分けがつかない（"な行" の途中なのか
     * 撥音なのか分からない）ため、**表示・確定で「ん」になるのは "nn" だけ**にしている。
     * ただし [convert] の「n + 子音 -> ん」先読みは残してあるので、
     * "kondo" のような一般的な綴りはそのまま「こんど」になる。
     */
    private const val SOKUON_N = "nn"

    /**
     * [expectedNext] が返す期待集合の上限。
     * これを超える＝候補を絞れていないので、バイアスをかける意味がない。
     */
    const val MAX_EXPECTED = 12

    /** [s] がこの先かなに化けうるローマ字の途中かどうか。 */
    private fun isViablePrefix(s: String): Boolean = s in PREFIXES

    /**
     * [buffer] を可能な限りかなへ変換する。
     *
     * ストローク誤認で「kt」のような不正な子音列がバッファに入っても
     * 詰まらないよう、**先頭 1 文字を英字のまま確定して必ず前進する**回復処理を持つ
     * （Google 日本語入力と同じ方針。"kta" -> "kた"）。
     * 保留するのは「まだ伸びれば必ずかなになる」接頭辞のときだけなので、
     * 未確定バッファが MAX_KEY 文字以上に伸び続けることはない。
     *
     * @param katakana true ならカタカナで返す
     */
    fun convert(buffer: String, katakana: Boolean = false): Result =
        convertInternal(buffer, katakana, null)

    /**
     * [buffer] を変換しつつ、回復処理（下の 5.）が何回起きたかを [fallbacks] へ数える。
     * [fallbacks] は 1 要素の配列（null なら数えない）。
     */
    private fun convertInternal(
        buffer: String,
        katakana: Boolean,
        fallbacks: IntArray?,
    ): Result {
        val out = StringBuilder()
        var rest = buffer
        loop@ while (rest.isNotEmpty()) {
            // 0) "nn" は次の 1 文字を見てから決める。
            //    nn + 母音 -> 「ん」で n を 1 つだけ消費（konnichiha -> こんにちは）
            //    nn + それ以外 -> 「ん」で n を 2 つ消費（kinnyoubi -> きんようび / zennbu -> ぜんぶ）
            if (rest.startsWith("nn")) {
                if (rest.length == 2) break@loop // 続きを待つ
                out.append("ん")
                rest = rest.substring(if (rest[2] in VOWELS) 1 else 2)
                continue@loop
            }

            // 1) 最長一致
            var matched = false
            var len = minOf(MAX_KEY, rest.length)
            while (len >= 1) {
                val kana = TABLE[rest.substring(0, len)]
                if (kana != null) {
                    // "n" 単独は次の入力次第で「な行」になるので、ここでは確定しない
                    out.append(kana)
                    rest = rest.substring(len)
                    matched = true
                    break
                }
                len--
            }
            if (matched) continue@loop

            val c = rest[0]

            // 2) 撥音の先読み: n + 子音（y を除く）-> ん。
            //    「ん」の明示入力は "nn" だけだが、この先読みまで捨てると
            //    "kondo" のような一般的な綴りが軒並み壊れるので残す。
            //    次の子音が確定するまで表示は "n" のまま（"こn" -> "こんd"）。
            if (c == 'n' && rest.length >= 2) {
                val next = rest[1]
                if (next !in VOWELS && next != 'y' && next != 'n' && next != '\'') {
                    out.append("ん")
                    rest = rest.substring(1)
                    continue@loop
                }
            }

            // 3) 促音: 同じ子音の連続 -> っ（その子音が実在する行の頭であること）
            if (rest.length >= 2 && c == rest[1] && c !in VOWELS && c != 'n' &&
                isViablePrefix(c.toString())
            ) {
                out.append("っ")
                rest = rest.substring(1)
                continue@loop
            }

            // 4) まだ伸びればかなになる接頭辞なら未確定として残す。
            //    長さの上限を明示して、万一テーブルが壊れても無限に溜まらないようにする。
            if (rest.length < MAX_KEY && isViablePrefix(rest)) break@loop

            // 5) 回復処理: どのローマ字にもならない先頭 1 文字は英字のまま確定し、
            //    残りで再試行する（必ず 1 文字前進するので詰まらない）。
            if (fallbacks != null) fallbacks[0]++
            out.append(c)
            rest = rest.substring(1)
        }

        val kana = out.toString()
        return Result(if (katakana) toKatakana(kana) else kana, rest)
    }

    /**
     * 未確定バッファを強制確定する（スペース / リターン / モード切替時）。
     *
     * 末尾に残った **単独の "n" は「ん」にしない**（Latin の n のまま確定する）。
     * 「ん」を打つ手段は "nn" だけ、という方針（[SOKUON_N] 参照）。
     *   flush("kan")  -> "かn"
     *   flush("kann") -> "かん"
     */
    fun flush(buffer: String, katakana: Boolean = false): String {
        if (buffer.isEmpty()) return ""
        val r = convert(buffer, katakana)
        if (r.pending.isEmpty()) return r.kana
        val tail = if (r.pending == SOKUON_N) {
            if (katakana) "ン" else "ん"
        } else {
            r.pending
        }
        return r.kana + tail
    }

    /**
     * 未確定バッファの表示用テキスト。
     *
     * 「ん」として見せるのは "nn" のときだけ。
     * 単独の "n" は **"n" のまま**表示する（"kon" -> 「こn」）。
     * n + 子音の先読みは [convert] 側で生きているので、
     * 次の子音が来た時点で「こんd」のように「ん」へ変わる。
     */
    fun preview(pending: String, katakana: Boolean = false): String = when (pending) {
        SOKUON_N -> if (katakana) "ン" else "ん"
        else -> pending
    }

    /**
     * 未確定バッファ [pending] のうち、**すでにかなとして見えている**部分。
     *
     * "nn" は次の 1 文字を見てから n を 1 つ消費するか 2 つ消費するかを決めるため
     * 未確定のまま持っているが、画面には [preview] によって「ん」と表示されている。
     * 変換や予測の読みを組むときはこれを含めないと、
     * ユーザーに見えている文字列と変換対象がずれる（「しゃけん」と見えて「しゃけ」を変換する）。
     *
     * それ以外の未確定（"k" や "sy" のような子音の途中）はまだかなになっていないので空を返す。
     */
    fun settledPending(pending: String, katakana: Boolean = false): String =
        if (pending == SOKUON_N) preview(pending, katakana) else ""

    /**
     * 未確定ローマ字 [pending] の直後に来うる文字の集合（かなモードの文脈バイアス用）。
     *
     * ローマ字テーブルそのものから導くので、テーブルを増やせば自動で追随する。
     *   - 子音 1 つが残っている（"k"）  -> 母音 + "y" 等 + 促音の同字重ね（"kk"）
     *   - 2 子音クラスタ（"ky" / "sh" / "ts"）-> ほぼ母音のみ（強く期待できる）
     *   - "n"                         -> **バイアスしない**（下記）
     *
     * "n" の直後は「な行 / にゃ行 / nn」だけでなく、撥音の先読み（n + 子音 -> ん）によって
     * ほぼすべての子音が正当な続きになる（"kondo" の d）。
     * ここで母音側だけを優遇すると、雑に書いた d / r / p が a に化ける。
     * ハーネス実測では "n" を除いても救済数は変わらず（7 件のまま）、
     * 子音の誤爆（雑書き 720 本中 53 本）だけが消えたので、"n" では一切偏らせない。
     *
     * 期待集合が [MAX_EXPECTED] より広い場合も「絞り込めていない」とみなして空を返す。
     * 空集合ならバイアスは一切かからない（= 従来どおりの認識）。
     */
    fun expectedNext(pending: String): Set<String> {
        if (pending.isEmpty() || pending.length >= MAX_KEY) return emptySet()
        if (pending == "n") return emptySet()
        val out = HashSet<String>()
        for (c in 'a'..'z') {
            val cand = pending + c
            if (TABLE.containsKey(cand) || isViablePrefix(cand)) out.add(c.toString())
        }
        if (pending.length == 1) {
            val head = pending[0]
            // 促音: 同じ子音を重ねると「っ」（"kka" -> っか）
            if (head !in VOWELS && isViablePrefix(head.toString())) {
                out.add(head.toString())
            }
        }
        return if (out.size > MAX_EXPECTED) emptySet() else out
    }

    // ------------------------------------------------------ 日本語らしさ判定

    /**
     * [raw]（単語ぶんの生ローマ字）で回復処理が何回起きるか。
     *
     * 回復処理は「どうやってもローマ字にならない文字」に対してだけ走るので、
     * この回数がそのまま「日本語として無理のある度合い」になる。
     * 子音の数を直接数える方式と違い、撥音（"kansha" の nsh）や
     * 促音（"issho" の ssh）を誤って数えることがない。
     */
    fun latinFallbackCount(raw: String): Int {
        if (raw.isEmpty()) return 0
        val counter = IntArray(1)
        convertInternal(raw, false, counter)
        return counter[0]
    }

    /**
     * [raw] が「日本語のローマ字ではない」と言い切れるか。
     *
     * 誤判定するとかな入力が英字に化けて実害が大きいので、**控えめに**倒す。
     * 回復処理が [NON_JAPANESE_FALLBACKS] 回以上起きたときだけ真。
     * 1 回だけの回復（ストローク 1 個の誤認識、"kta" など）では発動しない。
     *
     * Python ハーネス（test_romaji.py）実測:
     *   日本語 29 語（kyou / konnichiha / kansha / issho / kta ...）→ 誤発動 0
     *   英単語 27 語（strike / night / script / through ...）→ 22 語で発動
     */
    fun looksNonJapanese(raw: String): Boolean =
        latinFallbackCount(raw) >= NON_JAPANESE_FALLBACKS

    /** 自動英字化に必要な回復処理の回数。 */
    const val NON_JAPANESE_FALLBACKS = 2

    /** ひらがな -> カタカナ（「ー」や記号はそのまま）。 */
    fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }
        return sb.toString()
    }
}
