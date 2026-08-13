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
     * 前方一致の予測変換。[prefix] を読みの先頭に持つ語を返す。
     *
     * 読みの短い順（＝補完量の少ない順）に並んだ鍵をコスト順に見るだけなので、
     * メインスレッドから呼んでも問題ない程度に軽い。
     */
    fun predict(prefix: String, limit: Int): List<PredictionEngine.Candidate> {
        if (prefix.length < MIN_PREDICT_PREFIX || limit <= 0) return emptyList()
        val q = ByteArray(dic.maxKeyChars)
        val qlen = dic.encodeInto(prefix, 0, dic.maxKeyChars, q)
        if (qlen != prefix.length) return emptyList()
        val range = dic.prefixRange(q, qlen)
        var key = range[0]
        val end = minOf(range[1], range[0] + PREDICT_SCAN_KEYS)

        // (コスト, 読み, 表記) を軽く並べ替えるだけ。件数が少ないので素朴な実装で足りる。
        val scored = ArrayList<Triple<Int, String, String>>()
        while (key < end) {
            val len = dic.keyLength(key)
            if (len > prefix.length) {
                val reading = dic.keyReading(key)
                var w = dic.wordStart(key)
                val wEnd = dic.wordStart(key + 1)
                var taken = 0
                while (w < wEnd && taken < MAX_ALTERNATIVES) {
                    val surface = dic.wordSurface(w)
                    if (surface != reading) {
                        // 補完量が多いほど後ろへ回す（「おは」->「おはよう」を先に出す）
                        val score = dic.wordCost(w) + PREDICT_LENGTH_COST * (len - prefix.length)
                        scored.add(Triple(score, reading, surface))
                    }
                    w++
                    taken++
                }
            }
            key++
        }
        scored.sortBy { it.first }
        val out = ArrayList<PredictionEngine.Candidate>(limit)
        val seen = HashSet<String>()
        for ((_, reading, surface) in scored) {
            if (out.size >= limit) break
            if (seen.add(surface)) {
                out.add(PredictionEngine.Candidate(reading, surface, PredictionEngine.Source.ONDEVICE))
            }
        }
        return out
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

        /** 予測変換で「1 文字よけいに補完するたび」に足すコスト。 */
        const val PREDICT_LENGTH_COST = 300

        /**
         * 予測変換でなめる鍵の上限。
         * 鍵は読みの短い順に並んでいるので、先頭だけ見れば補完量の少ない候補が揃う。
         * メインスレッドから呼ぶので、1 文字の接頭辞で数万件を走査しないよう抑える。
         */
        const val PREDICT_SCAN_KEYS = 600

        /** これより短い読みでは予測を出さない（候補がノイズになる）。 */
        const val MIN_PREDICT_PREFIX = 2

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
    }
}
