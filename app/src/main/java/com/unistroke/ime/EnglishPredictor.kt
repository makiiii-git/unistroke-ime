package com.unistroke.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 英語版の単語予測。日本語版の [PredictionEngine] に当たるもの。
 *
 * 打っている途中の綴りから、続きを補った単語を候補バーへ出す。
 * 次の 2 つをマージする。どちらも端末内で完結し、通信はしない。
 *
 *   1. ユーザーが確定した単語の履歴 … 個人化。よく使う語・辞書に無い語（名前など）
 *   2. 内蔵の単語辞書 [EnglishDictionary] … 頻度順。履歴が空の初回から効く
 *
 * 鍵はアポストロフィを落とした小文字（[EnglishText.key]）なので、
 * "dont" から "don't"、"im" から "I'm" が出る。
 */
class EnglishPredictor internal constructor(
    private val file: File,
    private val dictionary: EnglishDictionary?,
) {

    /** load() / save() した時点のファイル更新時刻。 */
    private var loadedStamp = 0L

    private class Entry(
        val word: String,
        val key: String,
        var uses: Int,
        var lastUsed: Long,
        /** 辞書にある語か。無い語は書き損じかもしれないので、繰り返し使われるまで出さない。 */
        val known: Boolean,
    )

    /** 表記 -> エントリ */
    private val history = LinkedHashMap<String, Entry>()

    // ------------------------------------------------------------------ 予測

    /**
     * [typed]（いま打っている綴り）に続く単語の候補。
     *
     * 並び順は
     *   [アポストロフィを補った別表記] -> [履歴] -> [辞書の補完]
     * 先頭の「別表記」は its に対する it's、im に対する I'm のようなもの。
     * 記号を省いて書いたのだから、いちばん欲しいのはそれである見込みが高い。
     *
     * 大文字・小文字だけが違う別表記（i に対する I、ok に対する OK、pr に対する PR）は
     * 先頭に固定せず、頻度に下駄（[EXACT_BONUS]）を履かせて補完と同じ列に並べる。
     * 固定すると、pr と書いただけで毎回 PR が先頭を塞ぎ、present が押し出される。
     *
     * 打った綴りそのものは候補にしない（スペースを書けばそのまま確定できる）。
     * 候補の大文字・小文字は打った綴りに合わせる（[EnglishText.applyCase]）。
     */
    fun predict(typed: String, now: Long, limit: Int = MAX_CANDIDATES): List<String> {
        val key = EnglishText.key(typed)
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>()
        fun add(word: String) {
            if (out.size >= limit) return
            val shown = EnglishText.applyCase(word, typed)
            if (shown == typed) return
            if (seen.add(shown)) out.add(shown)
        }

        // 余分に取っておく（打った綴りそのものや履歴と重なったぶんが抜けるため）
        val hits = dictionary?.complete(key, limit + EXTRA_HITS).orEmpty()

        // 1) アポストロフィを補った別表記
        for (hit in hits) if (isContraction(hit)) add(hit.word)

        // 2) 履歴。頻度 + 新しさでランク付けし、同点なら短い語を先に。
        history.values
            .filter { it.key.startsWith(key) && (it.known || it.uses >= MIN_USES_UNKNOWN) }
            .sortedWith(
                compareByDescending<Entry> { score(it, now) }.thenBy { it.word.length },
            )
            .take(MAX_FROM_HISTORY)
            .forEach { add(it.word) }

        // 3) 辞書の補完（頻度順。綴りが完全に一致する語は少しだけ優遇する）
        hits.filterNot { isContraction(it) }
            .sortedByDescending { it.frequency + if (it.exact) EXACT_BONUS else 0 }
            .forEach { add(it.word) }
        return out
    }

    /** 打った綴りにアポストロフィを補っただけの語か（dont に対する don't）。 */
    private fun isContraction(hit: EnglishDictionary.Hit): Boolean =
        hit.exact && hit.word.any { EnglishText.isApostrophe(it) }

    /** 頻度と最近使ったかの複合スコア（[PredictionEngine] と同じ考え方）。 */
    private fun score(e: Entry, now: Long): Double {
        val age = now - e.lastUsed
        val recency = when {
            age < HOUR -> 3.0
            age < DAY -> 2.0
            age < WEEK -> 1.0
            else -> 0.0
        }
        return e.uses.toDouble() + recency
    }

    // ------------------------------------------------------------------ 記録

    /**
     * 確定した単語を履歴に記録する。
     * 英字とアポストロフィだけの、2 文字以上の語に限る（[EnglishText.isLearnable]）。
     */
    fun record(word: String, now: Long) {
        if (!EnglishText.isLearnable(word)) return
        val canonical = canonical(word)
        val e = history[canonical]
        if (e == null) {
            history[canonical] = Entry(
                canonical, EnglishText.key(canonical), 1, now, isKnown(canonical),
            )
            trim()
        } else {
            e.uses++
            e.lastUsed = now
            // LRU の末尾へ持っていく
            history.remove(canonical)
            history[canonical] = e
        }
        save()
    }

    /**
     * 履歴に載せる表記を決める。
     *
     * 文頭の "Hello" や CapsLock の "HELLO" をそのまま覚えると、次から文の途中でも
     * その大文字のまま候補に出てしまう。辞書に小文字の語として載っているなら
     * 小文字で覚える。固有名詞（London）や略語（NASA）は辞書の表記のまま残る。
     */
    private fun canonical(word: String): String {
        val dict = dictionary ?: return word
        val lower = word.lowercase()
        return when {
            EnglishText.isCapitalized(word) -> if (dict.hasWord(lower)) lower else word
            EnglishText.isAllCaps(word) ->
                if (!dict.hasWord(word) && dict.hasWord(lower)) lower else word

            else -> word
        }
    }

    /** 辞書が無いときは区別できないので、すべて既知として扱う。 */
    private fun isKnown(word: String): Boolean = dictionary?.hasWord(word) ?: true

    private fun trim() {
        while (history.size > MAX_HISTORY) {
            val oldest = history.entries.minByOrNull { it.value.lastUsed } ?: break
            history.remove(oldest.key)
        }
    }

    /** 覚えている単語の数（設定画面表示用）。 */
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
            val arr = root.optJSONArray("words") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("w", "")
                if (!EnglishText.isLearnable(w)) continue
                history[w] = Entry(
                    w, EnglishText.key(w), o.optInt("n", 1), o.optLong("t", 0L), isKnown(w),
                )
            }
        }
    }

    fun save() {
        runCatching {
            val arr = JSONArray()
            for (e in history.values) {
                arr.put(
                    JSONObject()
                        .put("w", e.word)
                        .put("n", e.uses)
                        .put("t", e.lastUsed),
                )
            }
            val root = JSONObject().put("version", VERSION).put("words", arr)
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
            loadedStamp = file.lastModified()
        }
    }

    companion object {
        const val FILE_NAME = "english_history.json"

        /** 候補バーに並べる候補の最大数。 */
        const val MAX_CANDIDATES = 10

        /** そのうち履歴から出す数の上限（辞書の候補を押し出しきらないように）。 */
        const val MAX_FROM_HISTORY = 3

        /** 辞書に無い語を候補に出し始める使用回数（1 回きりの書き損じは出さない）。 */
        const val MIN_USES_UNKNOWN = 2

        /** 辞書から余分に取る件数。 */
        private const val EXTRA_HITS = 4

        /**
         * 綴りが完全に一致する語（大文字・小文字だけが違う別表記）に足す頻度の下駄。
         * i と書いたら I が in / is より先に出るが、pr と書いても PR が present を
         * 追い越さない程度の大きさにしてある（頻度は 0〜255 の対数目盛り）。
         */
        private const val EXACT_BONUS = 30

        @Volatile
        private var instance: EnglishPredictor? = null

        /** プロセス内で共有する唯一のインスタンス（[PredictionEngine.get] と同じ理由）。 */
        fun get(context: Context): EnglishPredictor {
            return instance ?: synchronized(this) {
                instance ?: EnglishPredictor(
                    File(context.applicationContext.filesDir, FILE_NAME),
                    EnglishDictionary.get(context),
                ).also {
                    it.load()
                    instance = it
                }
            }
        }

        private const val VERSION = 1
        private const val MAX_HISTORY = 1000

        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR
        private const val WEEK = 7 * DAY
    }
}
