package com.unistroke.ime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * オンデバイス変換用のバイナリ辞書リーダ。
 *
 * assets/ondevice.dic を **メモリマップ**して読む。展開もパースもしないので、
 * 起動コストはほぼゼロで、7 MB の辞書がヒープを一切食わない
 * （OS のページキャッシュ上にあり、メモリ不足時は勝手に捨てられる）。
 *
 * 辞書は tools/build_dictionary.py が Mozc の OSS 辞書（BSD-3-Clause）から作る。
 * フォーマットは同スクリプトと tools/README.md を参照。要点だけ書くと:
 *
 *   ヘッダ（80 バイト）
 *   鍵インデックス  (keyCount + 1) * 8   : u32 鍵ブロブ内オフセット, u32 語の開始番号
 *   鍵ブロブ                              : 読みを 1 文字 1 バイトへ符号化して連結
 *   語配列          (wordCount + 1) * 10 : u32 表記オフセット, u16 コスト,
 *                                          u16 左グループ, u16 右グループ
 *   表記ブロブ                            : UTF-8 の表記を連結（長さは次語との差分）
 *   字母表                                : u16 個数 + 個数 * u16 コードポイント
 *   グループ表      groupCount * 2       : u8 品詞クラス, u8 フラグ
 *   接続行列        groupCount^2 * 2     : i16（左語の右グループ x 右語の左グループ）
 *
 * 読みは符号化バイト列の辞書順に並んでいる。字母表はコードポイント昇順なので、
 * バイト列の大小がそのまま文字列の大小になり、二分探索がそのまま使える。
 */
class OnDeviceDictionary private constructor(private val buf: ByteBuffer) {

    val keyCount: Int
    val wordCount: Int
    val groupCount: Int
    val bosGroup: Int
    val maxKeyChars: Int

    private val keyIndexOff: Int
    private val keyBlobOff: Int
    private val wordArrayOff: Int
    private val surfaceBlobOff: Int
    private val groupTableOff: Int
    private val matrixOff: Int

    /** 文字 -> 符号（1 始まり）。範囲外の文字は 0。 */
    private val encodeTable = ByteArray(ENCODE_RANGE)

    /** 符号 -> 文字。読みの復元は予測変換で 1 回に数千文字ぶん走るので逆引き表を持つ。 */
    private val decodeTable = CharArray(256)

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        keyCount = buf.getInt(8)
        wordCount = buf.getInt(12)
        keyIndexOff = buf.getInt(16)
        keyBlobOff = buf.getInt(20)
        // 24: 鍵ブロブ長（長さは鍵インデックスの差分で分かるので使わない）
        wordArrayOff = buf.getInt(28)
        surfaceBlobOff = buf.getInt(32)
        // 36: 表記ブロブ長
        maxKeyChars = buf.getInt(40)
        val alphabetOff = buf.getInt(44)
        groupCount = buf.getInt(48)
        groupTableOff = buf.getInt(52)
        matrixOff = buf.getInt(56)
        bosGroup = buf.getInt(60)

        val n = buf.getShort(alphabetOff).toInt() and 0xFFFF
        for (i in 0 until n) {
            val cp = buf.getShort(alphabetOff + 2 + 2 * i).toInt() and 0xFFFF
            val idx = cp - ENCODE_BASE
            if (idx in 0 until ENCODE_RANGE) {
                encodeTable[idx] = (i + 1).toByte()
                decodeTable[i + 1] = cp.toChar()
            }
        }
    }

    // ------------------------------------------------------------ 符号化

    /** 読みに使える文字か。 */
    fun encodable(c: Char): Boolean {
        val idx = c.code - ENCODE_BASE
        return idx in 0 until ENCODE_RANGE && encodeTable[idx].toInt() != 0
    }

    /**
     * [s] の [from] から最大 [max] 文字を符号化して [out] へ書き、書けた長さを返す。
     * 辞書に無い字種が出たらそこで止める（部分一致は成立しうるので失敗にはしない）。
     */
    fun encodeInto(s: CharSequence, from: Int, max: Int, out: ByteArray): Int {
        var n = 0
        while (n < max && from + n < s.length && n < out.size) {
            val idx = s[from + n].code - ENCODE_BASE
            if (idx < 0 || idx >= ENCODE_RANGE) break
            val v = encodeTable[idx]
            if (v.toInt() == 0) break
            out[n] = v
            n++
        }
        return n
    }

    // ------------------------------------------------------------ 低レベル

    private fun keyBlobStart(key: Int): Int = buf.getInt(keyIndexOff + 8 * key)

    /** 鍵 [key] の読みの長さ（文字数＝バイト数）。 */
    fun keyLength(key: Int): Int = keyBlobStart(key + 1) - keyBlobStart(key)

    private fun keyByte(key: Int, at: Int): Int =
        buf.get(keyBlobOff + keyBlobStart(key) + at).toInt() and 0xFF

    /** 鍵 [key] に属する語の開始番号（終端は wordStart(key + 1)）。 */
    fun wordStart(key: Int): Int = buf.getInt(keyIndexOff + 8 * key + 4)

    fun wordCost(w: Int): Int = buf.getShort(wordArrayOff + 10 * w + 4).toInt() and 0xFFFF

    fun wordLeftGroup(w: Int): Int = buf.getShort(wordArrayOff + 10 * w + 6).toInt() and 0xFFFF

    fun wordRightGroup(w: Int): Int = buf.getShort(wordArrayOff + 10 * w + 8).toInt() and 0xFFFF

    fun wordSurface(w: Int): String {
        val from = buf.getInt(wordArrayOff + 10 * w)
        val to = buf.getInt(wordArrayOff + 10 * (w + 1))
        val bytes = ByteArray(to - from)
        // ByteBuffer の position を触らない（複数スレッドから読むため）
        for (i in bytes.indices) bytes[i] = buf.get(surfaceBlobOff + from + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** 鍵 [key] の読みを復元する（予測変換で読みを履歴に積むのに使う）。 */
    fun keyReading(key: Int): String {
        val start = keyBlobStart(key)
        val len = keyBlobStart(key + 1) - start
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val v = buf.get(keyBlobOff + start + i).toInt() and 0xFF
            sb.append(decodeTable[v])
        }
        return sb.toString()
    }

    fun groupPos(g: Int): Int = buf.get(groupTableOff + 2 * g).toInt() and 0xFF

    fun groupFlags(g: Int): Int = buf.get(groupTableOff + 2 * g + 1).toInt() and 0xFF

    /** Mozc の接続コスト。[left] は左語の右グループ、[right] は右語の左グループ。 */
    fun connection(left: Int, right: Int): Int =
        buf.getShort(matrixOff + 2 * (left * groupCount + right)).toInt()

    // ------------------------------------------------------ 共通接頭辞検索

    /**
     * [q] の先頭 [qlen] バイトについて、その接頭辞になっている読みを探す。
     * 長さ L の読みが辞書にあれば `out[L - 1]` にその鍵番号を、無ければ -1 を入れ、
     * 見つかった最大長を返す。[out] は maxKeyChars 以上の長さが要る。
     *
     * 範囲を 1 バイトずつ狭めていくので、探索範囲は深くなるほど小さくなる。
     * 途中で空になったらそれ以上長い一致は無いので即座に打ち切れる。
     */
    fun commonPrefixSearch(q: ByteArray, qlen: Int, out: IntArray): Int {
        for (i in 0 until minOf(out.size, maxKeyChars)) out[i] = -1
        var lo = 0
        var hi = keyCount
        var found = 0
        val depthMax = minOf(qlen, maxKeyChars, out.size)
        for (depth in 0 until depthMax) {
            val b = q[depth].toInt() and 0xFF
            val start = lowerBound(lo, hi, depth, b)
            val end = upperBound(start, hi, depth, b)
            if (start >= end) return found
            lo = start
            hi = end
            // 範囲の先頭に、ちょうどこの長さの読みが（あれば 1 つだけ）並ぶ
            if (keyLength(lo) == depth + 1) {
                out[depth] = lo
                found = depth + 1
            }
        }
        return found
    }

    /** 鍵 [key] の [depth] バイト目を [b] と比べる。読みが短ければ「小さい」。 */
    private fun compareAt(key: Int, depth: Int, b: Int): Int {
        if (keyLength(key) <= depth) return -1
        val v = keyByte(key, depth)
        return if (v < b) -1 else if (v == b) 0 else 1
    }

    private fun lowerBound(from: Int, to: Int, depth: Int, b: Int): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareAt(mid, depth, b) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(from: Int, to: Int, depth: Int, b: Int): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareAt(mid, depth, b) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * [prefix] で始まる読みの鍵番号の範囲 [開始, 終了) を返す。
     * 見つからなければ空の範囲（開始 == 終了）。予測変換に使う。
     */
    fun prefixRange(q: ByteArray, qlen: Int): IntArray {
        var lo = 0
        var hi = keyCount
        for (depth in 0 until minOf(qlen, maxKeyChars)) {
            val b = q[depth].toInt() and 0xFF
            val start = lowerBound(lo, hi, depth, b)
            val end = upperBound(start, hi, depth, b)
            if (start >= end) return intArrayOf(0, 0)
            lo = start
            hi = end
        }
        return intArrayOf(lo, hi)
    }

    companion object {
        /** APK に同梱するコア辞書（約 8 万語）。インストール直後から使える。 */
        const val ASSET_NAME = "ondevice.dic"

        /**
         * チュートリアルで取得する拡張辞書（約 24 万語）を置く場所（filesDir）。
         * 存在すればコア辞書より優先して使う。壊れていれば黙ってコアへ落ちる。
         */
        const val EXT_NAME = "ondevice-ext.dic"

        /** ヘッダ長（固定）。これ未満のファイルは辞書ではない。 */
        private const val HEADER_BYTES = 80

        /** 符号表を引くための文字コード範囲（ひらがな + 長音記号）。 */
        private const val ENCODE_BASE = 0x3041
        private const val ENCODE_RANGE = 0x30FD - 0x3041

        private val MAGIC = byteArrayOf(
            'U'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(),
            'I'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte(), 0,
        )

        /**
         * assets からメモリマップして開く。開けなければ null。
         *
         * assets が無圧縮で入っていれば APK を直接 mmap できる（build.gradle.kts の
         * androidResources.noCompress で .dic を指定している）。
         * 圧縮されていた場合は openFd が例外を投げるので、そのときだけ
         * キャッシュ領域へ一度展開してから mmap する。
         */
        fun open(context: Context): OnDeviceDictionary? {
            val app = context.applicationContext
            // 拡張辞書 -> コア辞書の順。拡張が壊れていても落ちずにコアで動き続ける。
            mapFromFile(extFile(app))?.let { return it }
            mapFromAssets(app)?.let { return it }
            return mapFromCache(app)
        }

        /** ダウンロードした拡張辞書の置き場所。 */
        fun extFile(context: Context): File =
            File(context.applicationContext.filesDir, EXT_NAME)

        /** 拡張辞書が入っていて、かつ辞書として読める状態か。 */
        fun hasValidExtension(context: Context): Boolean =
            mapFromFile(extFile(context)) != null

        /**
         * このファイルが辞書として開けるか。
         * ダウンロード直後、本番の位置へ移す前の検証に使う
         * （ハッシュが合っていても、こちらが読める形式とは限らないため）。
         */
        fun isReadableDictionary(file: File): Boolean = mapFromFile(file) != null

        private fun mapFromFile(file: File): OnDeviceDictionary? = runCatching {
            if (!file.exists() || file.length() < HEADER_BYTES) return@runCatching null
            FileInputStream(file).use { input ->
                verified(input.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
            }
        }.getOrNull()

        private fun mapFromAssets(context: Context): OnDeviceDictionary? = runCatching {
            context.assets.openFd(ASSET_NAME).use { afd ->
                FileInputStream(afd.fileDescriptor).use { input ->
                    val buf = input.channel.map(
                        FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength,
                    )
                    verified(buf)
                }
            }
        }.getOrNull()

        private fun mapFromCache(context: Context): OnDeviceDictionary? = runCatching {
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

        private fun verified(buf: ByteBuffer): OnDeviceDictionary? {
            if (buf.capacity() < HEADER_BYTES) return null
            for (i in MAGIC.indices) if (buf.get(i) != MAGIC[i]) return null
            return OnDeviceDictionary(buf)
        }
    }
}
