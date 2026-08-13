package com.unistroke.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 前方一致の予測変換エンジン。
 *
 * Google transliterate API は「完全な読み」の変換しか返さないので、
 * 「おは」から「おはようございます」を出すには別系統の予測が要る。
 * 次の 3 ソースをマージして候補を作る。
 *
 *   1. ユーザーの確定履歴（読み -> 確定文字列）… 最優先・完全オフライン・個人化
 *   2. 内蔵フレーズ辞書 [PhraseDictionary] … 履歴が空の初回から効く
 *   3. Google Suggest（呼び出し側が非同期で足す）… ノイズがあるので最下位
 */
class PredictionEngine internal constructor(private val file: File) {

    /** load() / save() した時点のファイル更新時刻。 */
    private var loadedStamp = 0L

    /**
     * 候補の出どころ。並び順の優先度でもある。
     * [ONDEVICE] は端末内辞書（[OnDeviceConverter]）由来で、通信していないことを表す。
     */
    enum class Source { HISTORY, DICTIONARY, ONDEVICE, CONVERSION, SUGGEST, RAW }

    /** 候補 1 件。[reading] はその表記に対応する読み（履歴に記録し直すのに使う）。 */
    class Candidate(val reading: String, val surface: String, val source: Source)

    private class HistoryEntry(
        val reading: String,
        val surface: String,
        var uses: Int,
        var lastUsed: Long,
    )

    /** "読み + KEY_SEP + 表記" -> エントリ */
    private val history = LinkedHashMap<String, HistoryEntry>()

    // ------------------------------------------------------------------ 記録

    /**
     * 確定した (読み, 表記) を履歴に記録する。
     * 読みが 2 文字未満のものはノイズになるので覚えない。
     */
    fun record(reading: String, surface: String, now: Long) {
        if (reading.length < MIN_READING || surface.isEmpty()) return
        if (!looksLikeReading(reading)) return
        val key = reading + KEY_SEP + surface
        val e = history[key]
        if (e == null) {
            history[key] = HistoryEntry(reading, surface, 1, now)
            trim()
        } else {
            e.uses++
            e.lastUsed = now
            // LRU の末尾へ持っていく
            history.remove(key)
            history[key] = e
        }
        save()
    }

    private fun looksLikeReading(s: String): Boolean =
        s.all { it in 'ぁ'..'ゖ' || it == 'ー' || it == '　' }

    private fun trim() {
        while (history.size > MAX_HISTORY) {
            val oldest = history.entries.minByOrNull { it.value.lastUsed } ?: break
            history.remove(oldest.key)
        }
    }

    // ------------------------------------------------------------------ 予測

    /**
     * オフラインで即座に返せる予測（履歴 -> 辞書の順）。
     * [prefix] は現在の確定済みかな。
     */
    fun predictLocal(prefix: String, now: Long, limit: Int = 8): List<Candidate> {
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()

        // 1) 履歴。頻度 + 新しさでランク付けし、同点なら補完量が少ない順。
        history.values
            .filter { it.reading.startsWith(prefix) && it.surface != prefix }
            .sortedWith(
                compareByDescending<HistoryEntry> { score(it, now) }
                    .thenBy { it.reading.length },
            )
            .forEach {
                if (out.size < limit && seen.add(it.surface)) {
                    out.add(Candidate(it.reading, it.surface, Source.HISTORY))
                }
            }

        // 2) 内蔵辞書
        for ((reading, surface) in PhraseDictionary.startingWith(prefix, limit)) {
            if (out.size >= limit) break
            if (surface == prefix) continue
            if (seen.add(surface)) out.add(Candidate(reading, surface, Source.DICTIONARY))
        }
        return out
    }

    /** 頻度と最近使ったかの複合スコア。 */
    private fun score(e: HistoryEntry, now: Long): Double {
        val age = now - e.lastUsed
        val recency = when {
            age < HOUR -> 3.0
            age < DAY -> 2.0
            age < WEEK -> 1.0
            else -> 0.0
        }
        // 完全一致の読みは強めに押し上げる
        return e.uses.toDouble() + recency
    }

    /** 学習済み履歴の件数（設定画面表示用）。 */
    fun size(): Int = history.size

    // ------------------------------------------------------------------ 永続化

    fun reset() {
        history.clear()
        runCatching { if (file.exists()) file.delete() }
        loadedStamp = 0L
    }

    /** 外部から書き換わっていたら読み直す。 */
    fun reloadIfChanged(): Boolean {
        val stamp = if (file.exists()) file.lastModified() else 0L
        if (stamp == loadedStamp) return false
        load()
        return true
    }

    fun load() {
        history.clear()
        loadedStamp = if (file.exists()) file.lastModified() else 0L
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("history") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = o.optString("r", "")
                val s = o.optString("s", "")
                if (r.isEmpty() || s.isEmpty()) continue
                history[r + KEY_SEP + s] =
                    HistoryEntry(r, s, o.optInt("n", 1), o.optLong("t", 0L))
            }
        }
    }

    fun save() {
        runCatching {
            val arr = JSONArray()
            for (e in history.values) {
                arr.put(
                    JSONObject()
                        .put("r", e.reading)
                        .put("s", e.surface)
                        .put("n", e.uses)
                        .put("t", e.lastUsed),
                )
            }
            val root = JSONObject().put("version", VERSION).put("history", arr)
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
            loadedStamp = file.lastModified()
        }
    }

    companion object {
        const val FILE_NAME = "prediction_history.json"

        /**
         * 履歴キーの区切り。読みにも表記にも現れない文字を使う。
         * ソースをテキストのまま保つため、生のヌル文字ではなくエスケープで書く。
         */
        private const val KEY_SEP = "\u0000"

        @Volatile
        private var instance: PredictionEngine? = null

        /** プロセス内で共有する唯一のインスタンス（[PersonalTemplateStore.get] と同じ理由）。 */
        fun get(context: Context): PredictionEngine {
            return instance ?: synchronized(this) {
                instance ?: PredictionEngine(
                    File(context.applicationContext.filesDir, FILE_NAME),
                ).also {
                    it.load()
                    instance = it
                }
            }
        }
        private const val VERSION = 1
        private const val MAX_HISTORY = 500
        private const val MIN_READING = 2

        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR
        private const val WEEK = 7 * DAY
    }
}
