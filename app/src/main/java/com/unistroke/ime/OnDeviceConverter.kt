package com.unistroke.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 端末内だけで動くかな漢字変換。
 *
 * ネット変換（[GoogleConvertClient]）が使えないとき ―― 圏外・通信失敗・
 * ユーザーがオフラインを選んだとき ―― の肩代わりをする。
 * 出力は [GoogleConvertClient.Segment] と同じ形なので、
 * 候補バーや文節編集モードの仕組みはそのまま流用できる。
 *
 * 仕組みは辞書引き（共通接頭辞検索）でラティスを組み、Viterbi で最小コスト経路を選ぶ、
 * かな漢字変換の教科書どおりの構成。単語コストと接続コストはどちらも Mozc の
 * OSS データ（BSD-3-Clause）から作った [OnDeviceDictionary] に入っている。
 * 接続コストは 2672 の文脈IDを約 190 の品詞グループへ畳んであるので、
 * Mozc 本体ほどの精度は出ないが、手書きのヒューリスティクスよりはるかに素直に切れる。
 *
 * 辞書はメモリマップなので、この class を作ってもヒープはほとんど増えない。
 */
class OnDeviceConverter private constructor(private val dic: OnDeviceDictionary) {

    // 変換が一度も走らない入力欄（英字だけの欄など）でスレッドを作らないよう遅延生成する。
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "unistroke-ondevice").apply { isDaemon = true }
        }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 最新のリクエストだけを採用するための世代番号（メインスレッドからのみ更新）。 */
    @Volatile
    private var generation = 0

    // --------------------------------------------------------------- 公開 API

    /**
     * [reading]（ひらがな）を変換して、結果をメインスレッドで [onResult] に渡す。
     * 変換自体は 20 文字で数ミリ秒だが、辞書のページインで待たされうるので
     * [GoogleConvertClient.convert] と同じく別スレッドで走らせる。
     */
    fun convert(reading: String, onResult: (List<GoogleConvertClient.Segment>?) -> Unit) {
        val seq = ++generation
        executor.execute {
            val result = runCatching { convertBlocking(reading) }.getOrNull()
            main.post { if (seq == generation) onResult(result) }
        }
    }

    /** 実行中リクエストの結果を無視する。 */
    fun cancel() {
        generation++
    }

    fun shutdown() {
        generation++
        // 一度も変換していなければ executor はまだ作られていない。作らずに済ませる。
        runCatching { executor.shutdownNow() }
    }

    /**
     * その場で変換する（呼び出し元がすでにワーカースレッドにいる場合用）。
     * 変換できなければ null ではなく「全文かな 1 文節」を返すので、候補が空にはならない。
     */
    fun convertBlocking(reading: String): List<GoogleConvertClient.Segment> {
        if (reading.isEmpty()) return emptyList()
        val lattice = buildLattice(reading)
        val path = lattice.bestPath()
        if (path.isEmpty()) return listOf(fallbackSegment(reading))
        val out = ArrayList<GoogleConvertClient.Segment>(path.size)
        for (node in path) {
            out.add(segmentOf(lattice, node, reading))
        }
        return out
    }

    /**
     * 前方一致の予測変換。[prefix] を読みの先頭に持つ語を、出やすい順に返す。
     *
     * 接頭辞の範囲を**全部**なめる。鍵は辞書順に並んでいるので、先頭の数百件だけ見ると
     * 3 文字目が五十音の前のほうの語（「かいあ…」「かいい…」）に偏って
     * 「会社」「会議」が出てこない。走査中は読みも表記も復元せず、コストと品詞だけを
     * 読んで上位 N 件を保持し、最後に N 件ぶんだけ文字列にする。1 文字の接頭辞でも
     * 数千語 x 数回のバッファ読みで済むので、メインスレッドから呼べる。
     *
     * 読みが [prefix] と同じ語（完全一致）も含める。変換の往復を待たずに
     * 「でんわ」->「電話」が即座に出る。候補バー側は表記で重複を省くので、
     * あとから来る変換結果と二重には並ばない。
     */
    fun predict(prefix: String, limit: Int): List<PredictionEngine.Candidate> {
        if (prefix.length < MIN_PREDICT_PREFIX || limit <= 0) return emptyList()
        // メインスレッドから呼ぶので、壊れた辞書で IME ごと落ちないようにする
        return runCatching { predictUnsafe(prefix, limit) }.getOrDefault(emptyList())
    }

    private fun predictUnsafe(prefix: String, limit: Int): List<PredictionEngine.Candidate> {
        val q = ByteArray(dic.maxKeyChars)
        val qlen = dic.encodeInto(prefix, 0, dic.maxKeyChars, q)
        if (qlen != prefix.length) return emptyList()
        val range = dic.prefixRange(q, qlen)
        if (range[0] >= range[1]) return emptyList()

        // スコア昇順に保つ小さな配列。表記の重複で間引かれるぶん、limit より多めに持つ。
        val cap = limit * 2 + 4
        val topScore = IntArray(cap)
        val topWord = IntArray(cap)
        val topKey = IntArray(cap)
        var n = 0
        var scanned = 0
        var wStart = dic.wordStart(range[0])
        for (key in range[0] until range[1]) {
            // 次の鍵の開始番号がこの鍵の終端。読み直さずに持ち回る
            val wEnd = dic.wordStart(key + 1)
            val wFrom = wStart
            wStart = wEnd
            val len = dic.keyLength(key)
            if (len < MIN_PREDICT_READING) continue
            scanned += wEnd - wFrom
            if (scanned > PREDICT_SCAN_WORDS) break
            val lengthCost = PREDICT_LENGTH_COST * (len - prefix.length)
            val last = dic.keyChar(key, len - 1)
            for (w in wFrom until wEnd) {
                val score = predictScore(w, lengthCost, last)
                if (score > PREDICT_MAX_SCORE) continue
                // 同点は先に見つかった（語番号の小さい）ほうを残す
                if (n == cap && score >= topScore[n - 1]) continue
                var i = if (n < cap) n++ else n - 1
                while (i > 0 && topScore[i - 1] > score) {
                    topScore[i] = topScore[i - 1]
                    topWord[i] = topWord[i - 1]
                    topKey[i] = topKey[i - 1]
                    i--
                }
                topScore[i] = score
                topWord[i] = w
                topKey[i] = key
            }
        }

        val out = ArrayList<PredictionEngine.Candidate>(limit)
        val seen = HashSet<String>()
        for (i in 0 until n) {
            if (out.size >= limit) break
            val surface = dic.wordSurface(topWord[i])
            if (seen.add(surface)) {
                out.add(
                    PredictionEngine.Candidate(
                        dic.keyReading(topKey[i]), surface, PredictionEngine.Source.ONDEVICE,
                    ),
                )
            }
        }
        return out
    }

    /**
     * 語 [w] を「接頭辞の続きとして出す」ときのスコア。小さいほど上。
     * ondevice_model.py の predict_score と同じ式。
     *
     *   語コスト + 補完量 + BOS/EOS 接続コストの半分 + 品詞・活用形の補正
     *
     * BOS/EOS 接続コストは「その語だけで文を始めて終えられるか」の目安。
     * 連用形や助詞はここで自然に沈む。ただし全部は効かせない（[PREDICT_CONTEXT_PERCENT]）。
     * 「ください」「ございます」のように文頭には立たないが単語として打つ語が
     * 消えてしまうため。[last] は読みの末尾 1 文字で、活用形の見分けに使う。
     */
    private fun predictScore(w: Int, lengthCost: Int, last: Char): Int {
        val lg = dic.wordLeftGroup(w)
        val rg = dic.wordRightGroup(w)
        val pos = dic.groupPos(lg)
        val flags = dic.groupFlags(lg)
        var score = dic.wordCost(w) + lengthCost +
            Math.floorDiv(
                (dic.bosConnection(lg) + dic.eosConnection(rg)) * PREDICT_CONTEXT_PERCENT,
                100,
            )
        when (pos) {
            POS_PROPER -> score += PREDICT_PROPER_PENALTY
            POS_NUMBER -> score += PREDICT_NUMBER_PENALTY
            POS_PREFIX -> score += PREDICT_PREFIX_PENALTY
            POS_PARTICLE, POS_AUX, POS_SUFFIX, POS_SYMBOL, POS_OTHER ->
                score += PREDICT_BOUND_PENALTY
        }
        if ((flags and FLAG_NONFINAL) != 0) {
            // 形容詞の連用ゴザイ接続（ありがとう・おめでとう）は挨拶として単独で立つ
            if (!(pos == POS_ADJ && last == 'う')) score += PREDICT_NONFINAL_PENALTY
        } else if ((flags and FLAG_FINAL) != 0) {
            // 基本形はウ段（動詞）／イ（形容詞）で終わる。それ以外の終止は命令形。
            // イで終わる命令形（ください・なさい）は単独で打つ語なので許す。
            if (pos == POS_VERB && last != 'い' && last !in PREDICT_U_ROW) {
                score += PREDICT_NONFINAL_PENALTY
            }
            if (pos == POS_ADJ && last != 'い') score += PREDICT_NONFINAL_PENALTY
        }
        return score
    }

    // ------------------------------------------------------------ 候補の組み立て

    private fun fallbackSegment(reading: String) = GoogleConvertClient.Segment(
        reading, listOf(reading, RomajiConverter.toKatakana(reading)),
    )

    /** 経路上の 1 ノードを、代替候補つきの文節にする。 */
    private fun segmentOf(
        lattice: Lattice,
        node: Int,
        reading: String,
    ): GoogleConvertClient.Segment {
        val from = lattice.start[node]
        val to = lattice.end[node]
        val segReading = reading.substring(from, to)
        val cands = ArrayList<String>(MAX_ALTERNATIVES + 2)
        cands.add(lattice.surfaceOf(node, reading))
        val key = lattice.key[node]
        if (key >= 0) {
            var w = dic.wordStart(key)
            val wEnd = dic.wordStart(key + 1)
            while (w < wEnd && cands.size < MAX_ALTERNATIVES) {
                val s = dic.wordSurface(w)
                if (s !in cands) cands.add(s)
                w++
            }
        }
        if (segReading !in cands) cands.add(segReading)
        val kata = RomajiConverter.toKatakana(segReading)
        if (kata !in cands) cands.add(kata)
        return GoogleConvertClient.Segment(segReading, cands)
    }

    // ---------------------------------------------------------------- ラティス

    /**
     * ラティス。ノードは並列な IntArray に持つ（1 変換で数百ノード作るので
     * オブジェクトを作らない）。ノードは start の昇順に積まれる ―― 前向き Viterbi が
     * 「作った順に 1 回なめるだけ」で済むのはこの順序のおかげ。
     */
    private class Lattice(val length: Int) {
        var size = 0
        var start = IntArray(INITIAL)
        var end = IntArray(INITIAL)

        /** 辞書語なら語番号、かな素通しノードなら -1。 */
        var word = IntArray(INITIAL)

        /** 辞書語なら鍵番号（代替候補を引くのに使う）、かなノードなら -1。 */
        var key = IntArray(INITIAL)
        var lgroup = IntArray(INITIAL)
        var rgroup = IntArray(INITIAL)
        var cost = IntArray(INITIAL)
        var total = IntArray(INITIAL)
        var prev = IntArray(INITIAL)

        /** end 位置ごとの単方向リスト（head と next）。 */
        val endHead = IntArray(length + 1) { -1 }
        var endNext = IntArray(INITIAL)

        fun add(
            from: Int, to: Int, w: Int, k: Int, lg: Int, rg: Int, c: Int,
        ): Int {
            if (size == start.size) grow()
            val i = size++
            start[i] = from
            end[i] = to
            word[i] = w
            key[i] = k
            lgroup[i] = lg
            rgroup[i] = rg
            cost[i] = c
            total[i] = UNREACHABLE
            prev[i] = -1
            endNext[i] = endHead[to]
            endHead[to] = i
            return i
        }

        private fun grow() {
            val n = start.size * 2
            start = start.copyOf(n)
            end = end.copyOf(n)
            word = word.copyOf(n)
            key = key.copyOf(n)
            lgroup = lgroup.copyOf(n)
            rgroup = rgroup.copyOf(n)
            cost = cost.copyOf(n)
            total = total.copyOf(n)
            prev = prev.copyOf(n)
            endNext = endNext.copyOf(n)
        }

        lateinit var owner: OnDeviceConverter

        fun surfaceOf(node: Int, reading: String): String {
            val w = word[node]
            return if (w >= 0) {
                owner.dic.wordSurface(w)
            } else {
                reading.substring(start[node], end[node])
            }
        }

        /** 最小コスト経路を start の昇順で返す。 */
        fun bestPath(): IntArray {
            var last = -1
            var bestTotal = UNREACHABLE
            var node = endHead[length]
            while (node >= 0) {
                if (total[node] != UNREACHABLE) {
                    val c = total[node] + owner.connectionOf(this, node, -1)
                    if (c < bestTotal) {
                        bestTotal = c
                        last = node
                    }
                }
                node = endNext[node]
            }
            if (last < 0) return IntArray(0)
            var count = 0
            var n = last
            while (n >= 0) {
                count++
                n = prev[n]
            }
            val out = IntArray(count)
            n = last
            var i = count - 1
            while (n >= 0) {
                out[i--] = n
                n = prev[n]
            }
            return out
        }

        companion object {
            const val INITIAL = 256
            const val UNREACHABLE = Int.MAX_VALUE
        }
    }

    /**
     * ノード [from] からノード [to] へ繋ぐコスト。-1 は BOS（[from]）/ EOS（[to]）。
     * かな素通しノードは品詞が分からないので一律の値を使う。
     */
    private fun connectionOf(lattice: Lattice, from: Int, to: Int): Int {
        if (from >= 0 && lattice.word[from] < 0) return UNKNOWN_CONNECTION
        if (to >= 0 && lattice.word[to] < 0) return UNKNOWN_CONNECTION
        val left = if (from < 0) dic.bosGroup else lattice.rgroup[from]
        val right = if (to < 0) dic.bosGroup else lattice.lgroup[to]
        return dic.connection(left, right)
    }

    private fun buildLattice(reading: String): Lattice {
        val n = reading.length
        val lattice = Lattice(n)
        lattice.owner = this
        val q = ByteArray(dic.maxKeyChars)
        val hits = IntArray(dic.maxKeyChars)

        for (i in 0 until n) {
            val qlen = dic.encodeInto(reading, i, dic.maxKeyChars, q)
            var maxHit = 0
            if (qlen > 0) maxHit = dic.commonPrefixSearch(q, qlen, hits)

            for (len in 1..maxHit) {
                val key = hits[len - 1]
                if (key < 0) continue
                var w = dic.wordStart(key)
                val wEnd = dic.wordStart(key + 1)
                while (w < wEnd) {
                    val lg = dic.wordLeftGroup(w)
                    val pos = dic.groupPos(lg)
                    val flags = dic.groupFlags(lg)
                    lattice.add(
                        i, i + len, w, key, lg, dic.wordRightGroup(w),
                        dic.wordCost(w) + nodePenalty(pos, flags, len),
                    )
                    w++
                }
            }

            // 辞書に無い区間を埋めるかな素通しノード。
            // 「辞書に当たった長さ」はそのまま使えるので、重複だけ避ける。
            val maxKana = minOf(UNKNOWN_MAX_LEN, n - i)
            for (len in 1..maxKana) {
                if (len <= maxHit && hits[len - 1] >= 0) continue
                lattice.add(
                    i, i + len, -1, -1, dic.bosGroup, dic.bosGroup,
                    UNKNOWN_BASE + UNKNOWN_PER_CHAR * len + WORD_PENALTY,
                )
            }
        }

        forward(lattice)
        return lattice
    }

    /** 前向き Viterbi。ノードは start 昇順に並んでいるので 1 パスで済む。 */
    private fun forward(lattice: Lattice) {
        for (i in 0 until lattice.size) {
            val from = lattice.start[i]
            if (from == 0) {
                lattice.total[i] = lattice.cost[i] + connectionOf(lattice, -1, i)
                continue
            }
            var best = -1
            var bestCost = Lattice.UNREACHABLE
            var p = lattice.endHead[from]
            while (p >= 0) {
                val t = lattice.total[p]
                if (t != Lattice.UNREACHABLE) {
                    val c = t + connectionOf(lattice, p, i)
                    if (c < bestCost) {
                        bestCost = c
                        best = p
                    }
                }
                p = lattice.endNext[p]
            }
            if (best >= 0) {
                lattice.total[i] = bestCost + lattice.cost[i]
                lattice.prev[i] = best
            }
        }
    }

    /** 単語コストへの上乗せ。文節が増えすぎるのと、1 文字語の乱発を抑える。 */
    private fun nodePenalty(pos: Int, flags: Int, length: Int): Int {
        var c = WORD_PENALTY
        if (pos == POS_PROPER) c += PROPER_PENALTY
        if (length == 1 && (flags and FLAG_INDEPENDENT) != 0 &&
            (pos == POS_NOUN || pos == POS_PROPER || pos == POS_VERB ||
                pos == POS_ADJ || pos == POS_NUMBER)
        ) {
            c += SHORT_CONTENT_PENALTY
        }
        return c
    }

    companion object {
        // ---- コスト定数。ondevice_model.py の同名定数と必ず一致させること ----

        /** 文節が増えることへの一律ペナルティ（過分割を抑える）。 */
        const val WORD_PENALTY = 1200

        /** 辞書に無いかな列のノード基本コスト。 */
        const val UNKNOWN_BASE = 8000

        /** 同・1 文字あたり。 */
        const val UNKNOWN_PER_CHAR = 3000

        /** かな素通しノードの最大長。 */
        const val UNKNOWN_MAX_LEN = 6

        /** 1 文字の自立語（名詞・動詞など）へのペナルティ。 */
        const val SHORT_CONTENT_PENALTY = 1500

        /** 固有名詞へのペナルティ。 */
        const val PROPER_PENALTY = 700

        /** かなノードの接続コスト（品詞が分からないので固定）。 */
        const val UNKNOWN_CONNECTION = 5000

        /** 1 文節あたりに返す代替候補の数。 */
        const val MAX_ALTERNATIVES = 5

        // ---- 前方一致予測のスコア。ondevice_model.py の同名定数と必ず一致させること ----

        /** 1 文字よけいに補完するたびに足すコスト。 */
        const val PREDICT_LENGTH_COST = 300

        /** BOS/EOS 接続コスト（文頭・文末に立てるか）を効かせる割合（%）。 */
        const val PREDICT_CONTEXT_PERCENT = 50

        /** 固有名詞。 */
        const val PREDICT_PROPER_PENALTY = 2000

        /** 助詞・助動詞・接尾・記号など、単独で立たない語。 */
        const val PREDICT_BOUND_PENALTY = 3000

        /** 未然形・連用形・命令形など、言い切りでない活用形。 */
        const val PREDICT_NONFINAL_PENALTY = 3000

        /** 数詞（澗・垓のような大数が 0 コストで入っている）。 */
        const val PREDICT_NUMBER_PENALTY = 1500

        /** 接頭詞（快・各・新）。変換なら正解になりうるが、予測としては「会社」より後でよい。 */
        const val PREDICT_PREFIX_PENALTY = 1500

        /** これを超える候補は出さない（珍しい語の長い補完を切る）。 */
        const val PREDICT_MAX_SCORE = 9000

        /**
         * 1 回の予測でなめる語数の上限。
         * 1 文字の接頭辞でもコア辞書で 6 千語、拡張辞書で 2 万語程度なので普段は届かない。
         * メインスレッドから呼ぶので、壊れた辞書で暴走しないための保険。
         */
        const val PREDICT_SCAN_WORDS = 50000

        /** 1 文字目から予測する。 */
        const val MIN_PREDICT_PREFIX = 1

        /** 読みが 1 文字の語（蚊・可・科）は予測に出さない。 */
        const val MIN_PREDICT_READING = 2

        /**
         * 動詞の基本形はウ段で終わる。終止形フラグが立っていてウ段でもイでもなければ命令形
         * （書け・食べろ・食べよ）。イで終わる命令形（ください・なさい）は単独で使う語なので許す。
         */
        const val PREDICT_U_ROW = "うくぐすずつづぬふぶぷむゆる"

        // ---- 品詞クラス。tools/build_dictionary.py の POS_* と一致させること ----

        const val POS_OTHER = 0
        const val POS_NOUN = 1
        const val POS_PROPER = 2
        const val POS_VERB = 3
        const val POS_ADJ = 4
        const val POS_ADVERB = 5
        const val POS_PARTICLE = 6
        const val POS_AUX = 7
        const val POS_PREFIX = 8
        const val POS_SUFFIX = 9
        const val POS_ADNOMINAL = 10
        const val POS_CONJUNCTION = 11
        const val POS_INTERJECTION = 12
        const val POS_NUMBER = 13
        const val POS_SYMBOL = 14

        const val FLAG_NONFINAL = 1
        const val FLAG_FINAL = 2
        const val FLAG_INDEPENDENT = 4

        @Volatile
        private var instance: OnDeviceConverter? = null

        @Volatile
        private var loadFailed = false

        /**
         * プロセス内で共有する唯一のインスタンス。辞書を開けなければ null。
         *
         * 開く（＝ mmap する）だけなので数ミリ秒で終わるが、
         * 一度失敗したら二度と試さない（毎回 assets を舐めに行かせない）。
         */
        fun get(context: Context): OnDeviceConverter? {
            instance?.let { return it }
            if (loadFailed) return null
            return synchronized(this) {
                instance ?: run {
                    val dic = OnDeviceDictionary.open(context)
                    if (dic == null) {
                        loadFailed = true
                        null
                    } else {
                        OnDeviceConverter(dic).also { instance = it }
                    }
                }
            }
        }

        /**
         * 次の [get] で辞書を開き直させる。
         *
         * 拡張辞書を入れ替えた／消したときに呼ぶ。既に配られたインスタンスは
         * 古い mmap を掴んだまま動き続けるが、rename 前の inode は生きているので
         * 壊れない（次に IME が開かれた時点で新しい辞書に入れ替わる）。
         */
        fun reset() {
            synchronized(this) {
                instance = null
                loadFailed = false
            }
        }
    }
}
