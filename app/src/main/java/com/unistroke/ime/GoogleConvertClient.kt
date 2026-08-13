package com.unistroke.ime

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/**
 * Google 日本語入力 CGI API（キー不要）でひらがな -> 漢字かな交じり文へ変換するクライアント。
 *
 *   GET https://www.google.com/transliterate?langpair=ja-Hira|ja&text=<ひらがな>
 *   -> [["よみ1",["候補1","候補2",...]], ["よみ2",[...]], ...]
 *
 * 入力した読みが Google のサーバへ送信される点に注意（オフラインでは変換されない）。
 * 外部ライブラリを使わず HttpURLConnection + 単一スレッドの Executor で実装している。
 */
class GoogleConvertClient {

    /** 1 文節ぶんの変換結果。 */
    data class Segment(val reading: String, val candidates: List<String>)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unistroke-convert").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * 最新リクエストだけを採用するための世代番号（メインスレッドからのみ更新）。
     * 変換とサジェストは別チャネルなので互いを打ち消さないよう分けて持つ。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var suggestGeneration = 0

    /**
     * 直近の読み -> 変換結果の LRU キャッシュ。
     * 予測変換で 1 ストロークごとにリクエストが飛ぶため、
     * バックスペースでの往復や同じ読みの再入力で通信しないようにする。
     */
    private val cache = object : LinkedHashMap<String, List<Segment>>(CACHE_SIZE * 2, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Segment>>,
        ): Boolean = size > CACHE_SIZE
    }

    private val suggestCache = object : LinkedHashMap<String, List<String>>(CACHE_SIZE * 2, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>,
        ): Boolean = size > CACHE_SIZE
    }

    /**
     * [reading]（ひらがな）を変換する。結果はメインスレッドで [onResult] に渡す。
     * 失敗・タイムアウト時は null。古いリクエストの結果は破棄される。
     * キャッシュヒット時は通信せずその場で [onResult] を呼ぶ。
     */
    fun convert(reading: String, onResult: (List<Segment>?) -> Unit) {
        val seq = ++generation
        synchronized(cache) { cache[reading] }?.let {
            onResult(it)
            return
        }
        executor.execute {
            val result = try {
                request(reading)
            } catch (t: Throwable) {
                null
            }
            if (result != null) synchronized(cache) { cache[reading] = result }
            main.post { if (seq == generation) onResult(result) }
        }
    }

    /** 実行中リクエストの結果を無視する。 */
    fun cancel() {
        generation++
        suggestGeneration++
    }

    fun shutdown() {
        generation++
        suggestGeneration++
        executor.shutdownNow()
    }

    /**
     * Google Suggest（日本語サジェスト）で前方一致の予測候補を取る。
     *   GET https://www.google.com/complete/search?client=firefox&hl=ja&q=<読み>
     *   -> ["<読み>", ["候補1", "候補2", ...]]
     *
     * 検索クエリ向けなのでノイズを含む。呼び出し側で最下位に置くこと。
     */
    fun suggest(reading: String, onResult: (List<String>?) -> Unit) {
        val seq = ++suggestGeneration
        synchronized(suggestCache) { suggestCache[reading] }?.let {
            onResult(it)
            return
        }
        executor.execute {
            val result = try {
                requestSuggest(reading)
            } catch (t: Throwable) {
                null
            }
            if (result != null) synchronized(suggestCache) { suggestCache[reading] = result }
            main.post { if (seq == suggestGeneration) onResult(result) }
        }
    }

    private fun requestSuggest(reading: String): List<String>? {
        // ie / oe を明示しないと日本語が UTF-8 で返る保証が無く、候補バーが「◆」だらけになる。
        val url = URL(
            SUGGEST_ENDPOINT + "?client=firefox&hl=ja&ie=utf-8&oe=utf-8&q=" +
                URLEncoder.encode(reading, "UTF-8"),
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Charset", "utf-8")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val root = JSONArray(readBody(conn))
            val arr = root.optJSONArray(1) ?: return null
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optString(i, "")
                // 空白入りのフレーズ検索は入力候補として使いにくいので落とす
                if (isUsable(c) && !c.contains(' ') && c !in out) out.add(c)
            }
            return if (out.isEmpty()) null else out
        } finally {
            conn.disconnect()
        }
    }

    private fun request(reading: String): List<Segment>? {
        val url = URL(
            ENDPOINT + "?langpair=ja-Hira%7Cja&text=" + URLEncoder.encode(reading, "UTF-8"),
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Charset", "utf-8")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return parse(readBody(conn))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ デコード

    /**
     * レスポンス本文を文字化けさせずに読む。
     *
     * 1. BOM があればそれに従う
     * 2. Content-Type の charset があれば、その文字コードで **厳密に** デコードしてみる
     * 3. 駄目なら UTF-8 -> Windows-31J(Shift_JIS) -> EUC-JP の順で厳密デコードを試す
     * 4. どれも通らなければ UTF-8 の置換つきデコード（従来動作）
     *
     * 「厳密に」= 不正バイト列があれば例外にする（U+FFFD に置換させない）。
     * 置換されてしまうと候補バーに「◆」が並ぶので、置換前に別の文字コードを試す。
     */
    private fun readBody(conn: HttpURLConnection): String =
        decode(conn.inputStream.use { readAll(it) }, charsetOf(conn.contentType))

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(BODY_BUFFER)
        val buf = ByteArray(BODY_BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) break
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String?): Charset? {
        val ct = contentType ?: return null
        val m = CHARSET_RE.find(ct) ?: return null
        val name = m.groupValues[1].trim().trim('"', '\'')
        if (name.isEmpty()) return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun decode(bytes: ByteArray, declared: Charset?): String {
        if (bytes.isEmpty()) return ""
        decodeBom(bytes)?.let { return it }
        if (declared != null) strictDecode(bytes, 0, declared)?.let { return it }
        for (cs in fallbacks) strictDecode(bytes, 0, cs)?.let { return it }
        return String(bytes, Charsets.UTF_8)
    }

    /** BOM 付きならそれに従ってデコードする。 */
    private fun decodeBom(b: ByteArray): String? {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return strictDecode(b, 3, Charsets.UTF_8) ?: String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        return null
    }

    /** 不正バイト列があれば null を返す厳密デコード。 */
    private fun strictDecode(bytes: ByteArray, offset: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (t: CharacterCodingException) {
        null
    }

    /**
     * 候補として使える文字列か。
     * 万一デコードに失敗して U+FFFD（表示上の「◆」）が混ざった候補は捨てる。
     */
    private fun isUsable(s: String): Boolean =
        s.isNotEmpty() && s.indexOf(REPLACEMENT_CHAR) < 0

    /** `[["よみ",["候補",...]],...]` を [Segment] のリストへ。 */
    private fun parse(body: String): List<Segment>? {
        val root = JSONArray(body)
        if (root.length() == 0) return null
        val out = ArrayList<Segment>(root.length())
        for (i in 0 until root.length()) {
            val pair = root.optJSONArray(i) ?: continue
            val reading = pair.optString(0, "")
            val arr = pair.optJSONArray(1) ?: continue
            val cands = ArrayList<String>(arr.length())
            for (j in 0 until arr.length()) {
                val c = arr.optString(j, "")
                if (isUsable(c) && c !in cands) cands.add(c)
            }
            if (isUsable(reading) && cands.isNotEmpty()) {
                // 読みそのもの（ひらがな）も候補の末尾に加えておく
                if (reading !in cands) cands.add(reading)
                out.add(Segment(reading, cands))
            }
        }
        return if (out.isEmpty()) null else out
    }

    /**
     * charset 無指定・かつ宣言どおりに読めなかったときに試す文字コード。
     * 端末に無い文字コードは黙って外す。
     */
    private val fallbacks: List<Charset> =
        FALLBACK_NAMES.mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .ifEmpty { listOf(Charsets.UTF_8) }

    private companion object {
        const val ENDPOINT = "https://www.google.com/transliterate"
        const val SUGGEST_ENDPOINT = "https://www.google.com/complete/search"
        const val TIMEOUT_MS = 3000

        /** 直近いくつの読みをキャッシュするか。 */
        const val CACHE_SIZE = 24

        /** Content-Type から charset を拾う。 */
        val CHARSET_RE = Regex("""charset\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)

        /** 厳密デコードを試す順。UTF-8 は他の文字コードのテキストをまず通さない。 */
        val FALLBACK_NAMES = listOf("UTF-8", "Windows-31J", "EUC-JP")

        const val BODY_BUFFER = 8 * 1024

        /** 応答が壊れて延々流れてきた場合の保険。 */
        const val MAX_BODY_BYTES = 512 * 1024

        /** デコード失敗を表す置換文字（端末によっては「◆」に見える）。 */
        const val REPLACEMENT_CHAR = '\uFFFD'
    }
}
