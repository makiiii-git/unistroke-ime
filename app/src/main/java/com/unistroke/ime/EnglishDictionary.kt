package com.unistroke.ime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 英語版の単語辞書リーダ（単語の予測に使う）。
 *
 * assets/english.dic を [OnDeviceDictionary] と同じく**メモリマップ**して読む。
 * 展開もパースもしないので、起動コストもヒープの消費もほぼ無い。
 *
 * 辞書は tools/build_english_dictionary.py が頻度付きの単語リストから作る。
 * フォーマット（リトルエンディアン）:
 *
 *   ヘッダ（32 バイト）
 *      0  magic "UNIENG1\0"
 *      8  u32 フォーマットの版
 *     12  u32 語数
 *     16  u32 オフセット表の位置
 *     20  u32 頻度表の位置
 *     24  u32 ブロブの位置
 *     28  u32 ブロブの長さ
 *   オフセット表  (語数 + 1) * u32 : 各語のブロブ内オフセット（長さは次語との差分）
 *   頻度表        語数 * u8        : 0〜255。大きいほどよく使う
 *   ブロブ                         : 語を連結。1 語は「鍵」または「鍵 TAB 表記」
 *
 * **鍵**は英小文字だけ（[EnglishText.key] と同じ規則。don't -> dont / London -> london）。
 * 表記が鍵と同じ語（ふつうの小文字の単語）は鍵だけを持ち、違う語だけ TAB のあとに
 * UTF-8 の表記を持つ。語は鍵のバイト列の昇順に並び、同じ鍵の中では頻度の高い順。
 * TAB（0x09）はどの英小文字よりも小さいので、鍵の大小がそのままバイト列の大小になる。
 */
class EnglishDictionary private constructor(private val buf: ByteBuffer) {

    val wordCount: Int

    private val offsetTableOff: Int
    private val freqTableOff: Int
    private val blobOff: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        wordCount = buf.getInt(12)
        offsetTableOff = buf.getInt(16)
        freqTableOff = buf.getInt(20)
        blobOff = buf.getInt(24)
    }

    /** 予測の 1 件。[exact] は鍵が打った綴りと完全に一致したもの（its に対する it's など）。 */
    class Hit(val word: String, val frequency: Int, val exact: Boolean)

    // ------------------------------------------------------------ 低レベル

    private fun entryStart(i: Int): Int = buf.getInt(offsetTableOff + 4 * i)

    fun frequency(i: Int): Int = buf.get(freqTableOff + i).toInt() and 0xFF

    /** 語 [i] の鍵の長さ（TAB の手前まで）。 */
    private fun keyLength(i: Int): Int {
        val start = entryStart(i)
        val end = entryStart(i + 1)
        var n = 0
        while (start + n < end && buf.get(blobOff + start + n) != TAB) n++
        return n
    }

    /** 語 [i] の表記。 */
    fun word(i: Int): String {
        val start = entryStart(i)
        val end = entryStart(i + 1)
        var from = start
        for (p in start until end) {
            if (buf.get(blobOff + p) == TAB) {
                from = p + 1
                break
            }
        }
        val bytes = ByteArray(end - from)
        // ByteBuffer の position を触らない（複数スレッドから読んでも壊れないように）
        for (k in bytes.indices) bytes[k] = buf.get(blobOff + from + k)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 語 [i] の鍵の先頭 [qlen] バイトを [q] と比べる。
     * 鍵のほうが短く、そこまで一致していれば「小さい」。先頭が一致すれば 0。
     */
    private fun comparePrefix(i: Int, q: ByteArray, qlen: Int): Int {
        val start = entryStart(i)
        val end = entryStart(i + 1)
        for (d in 0 until qlen) {
            if (start + d >= end) return -1
            val b = buf.get(blobOff + start + d)
            if (b == TAB) return -1
            val v = b.toInt() and 0xFF
            val w = q[d].toInt() and 0xFF
            if (v != w) return if (v < w) -1 else 1
        }
        return 0
    }

    /** 鍵が [q] で始まる語の範囲 [開始, 終了)。無ければ空の範囲。 */
    private fun prefixRange(q: ByteArray): IntArray {
        var lo = 0
        var hi = wordCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparePrefix(mid, q, q.size) < 0) lo = mid + 1 else hi = mid
        }
        val start = lo
        hi = wordCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparePrefix(mid, q, q.size) <= 0) lo = mid + 1 else hi = mid
        }
        return intArrayOf(start, lo)
    }

    /** 鍵（英小文字だけ）をバイト列にする。それ以外の字が混じっていたら null。 */
    private fun encode(key: String): ByteArray? {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
        val out = ByteArray(key.length)
        for (i in key.indices) {
            val ch = key[i]
            if (ch !in 'a'..'z') return null
            out[i] = ch.code.toByte()
        }
        return out
    }

    // -------------------------------------------------------------- 検索

    /**
     * 鍵が [key] で始まる語を、使われやすい順に最大 [limit] 件返す。
     *
     * 鍵が完全に一致する語（打った綴りそのものの別表記）を先頭に置き、
     * 残りを頻度の高い順に並べる。頻度が同じなら辞書順（＝短い語が先）。
     *
     * 1 文字の接頭辞だと範囲は 1 万語を超えるが、見るのは頻度表の 1 バイトだけなので、
     * 上位 [limit] 件を選ぶのに全体を並べ替える必要は無い。
     */
    fun complete(key: String, limit: Int): List<Hit> {
        if (limit <= 0) return emptyList()
        val q = encode(key) ?: return emptyList()
        val range = prefixRange(q)
        var i = range[0]
        val to = range[1]
        val out = ArrayList<Hit>(limit)

        // 鍵が同じ長さの語は範囲の先頭に、頻度の高い順で並んでいる
        while (i < to && keyLength(i) == q.size) {
            if (out.size < limit) out.add(Hit(word(i), frequency(i), true))
            i++
        }

        val k = limit - out.size
        if (k <= 0) return out
        val topIndex = IntArray(k)
        val topFreq = IntArray(k)
        var n = 0
        while (i < to) {
            val f = frequency(i)
            if (n < k || f > topFreq[n - 1]) {
                var p = if (n < k) n++ else n - 1
                // 同じ頻度なら先に見つけたほう（辞書順で前）を上に残す
                while (p > 0 && topFreq[p - 1] < f) {
                    topIndex[p] = topIndex[p - 1]
                    topFreq[p] = topFreq[p - 1]
                    p--
                }
                topIndex[p] = i
                topFreq[p] = f
            }
            i++
        }
        for (j in 0 until n) out.add(Hit(word(topIndex[j]), topFreq[j], false))
        return out
    }

    /** 表記が [word] とまったく同じ語が辞書にあるか（大文字・小文字・記号まで一致）。 */
    fun hasWord(word: String): Boolean {
        val q = encode(EnglishText.key(word)) ?: return false
        val range = prefixRange(q)
        var i = range[0]
        while (i < range[1] && keyLength(i) == q.size) {
            if (word(i) == word) return true
            i++
        }
        return false
    }

    companion object {
        const val ASSET_NAME = "english.dic"

        /** ヘッダ長（固定）。 */
        private const val HEADER_BYTES = 32

        /** 読めるフォーマットの版。ビルドスクリプトの FORMAT_VERSION と一致させること。 */
        private const val FORMAT_VERSION = 1

        /** 鍵の最大長。これより長い綴りは辞書に無いので引かない。 */
        private const val MAX_KEY_LENGTH = 48

        private const val TAB: Byte = 0x09

        private val MAGIC = byteArrayOf(
            'U'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'E'.code.toByte(),
            'N'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte(), 0,
        )

        @Volatile
        private var instance: EnglishDictionary? = null

        @Volatile
        private var opened = false

        /**
         * プロセス内で共有する辞書。開けなければ null（履歴だけで予測する）。
         * 開けなかった結果も覚えておき、入力のたびに開き直しに行かない。
         */
        fun get(context: Context): EnglishDictionary? {
            if (opened) return instance
            return synchronized(this) {
                if (!opened) {
                    val app = context.applicationContext
                    instance = mapFromAssets(app) ?: mapFromCache(app)
                    opened = true
                }
                instance
            }
        }

        private fun mapFromAssets(context: Context): EnglishDictionary? = runCatching {
            context.assets.openFd(ASSET_NAME).use { afd ->
                FileInputStream(afd.fileDescriptor).use { input ->
                    verified(
                        input.channel.map(
                            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength,
                        ),
                    )
                }
            }
        }.getOrNull()

        /** assets が圧縮されていて直接 mmap できないときだけ、一度展開してから開く。 */
        private fun mapFromCache(context: Context): EnglishDictionary? = runCatching {
            val file = File(context.cacheDir, ASSET_NAME)
            if (!file.exists() || file.length() == 0L) {
                val tmp = File(context.cacheDir, "$ASSET_NAME.tmp")
                context.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return@runCatching null
                }
            }
            FileInputStream(file).use { input ->
                verified(input.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
            }
        }.getOrNull()

        private fun verified(buf: ByteBuffer): EnglishDictionary? {
            if (buf.capacity() < HEADER_BYTES) return null
            for (i in MAGIC.indices) if (buf.get(i) != MAGIC[i]) return null
            buf.order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(8) != FORMAT_VERSION) return null
            // 壊れたファイルを掴んで範囲外を読みに行かないよう、配置だけは確かめる
            val words = buf.getInt(12)
            val offsets = buf.getInt(16)
            val freqs = buf.getInt(20)
            val blob = buf.getInt(24)
            val blobLen = buf.getInt(28)
            if (words < 0 || offsets != HEADER_BYTES) return null
            if (freqs != offsets + 4 * (words + 1) || blob != freqs + words) return null
            if (blobLen < 0 || blob + blobLen != buf.capacity()) return null
            return EnglishDictionary(buf)
        }
    }
}
