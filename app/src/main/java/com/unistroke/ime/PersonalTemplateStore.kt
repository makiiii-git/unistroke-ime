package com.unistroke.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 書き手の癖を暗黙学習して溜める個人テンプレート置き場。
 *
 * 訂正イベント（誤認識 -> バックスペース -> 書き直し）で得たストロークをまず「候補」として貯め、
 * 同じ文字について**似た候補が 2 回**現れたら初めて「個人テンプレート」に昇格させる。
 * 1 回きりの書き損じを覚えてしまわないための二段構え。
 *
 * 保存先はアプリ内部ストレージの JSON 1 ファイル。
 */
class PersonalTemplateStore internal constructor(private val file: File) {

    /** load() / save() した時点のファイル更新時刻。外部からの書き換え検出に使う。 */
    private var loadedStamp = 0L

    /** 正規化済みポリライン 1 本。 */
    class Entry(
        val points: List<Pt>,
        var uses: Int = 0,
        var updatedAt: Long = 0L,
        /** [SOURCE_TRAINING] = 明示的トレーニング由来 / [SOURCE_IMPLICIT] = 訂正からの暗黙学習 */
        var source: String = SOURCE_IMPLICIT,
    )

    /** セット名（"alpha" / "number"）-> 文字 -> ストローク群 */
    private val templates = HashMap<String, HashMap<String, MutableList<Entry>>>()
    private val candidates = HashMap<String, HashMap<String, MutableList<Entry>>>()

    private fun bucket(
        map: HashMap<String, HashMap<String, MutableList<Entry>>>,
        set: String,
        symbol: String,
    ): MutableList<Entry> = map.getOrPut(set) { HashMap() }.getOrPut(symbol) { ArrayList() }

    // ------------------------------------------------------------------ 参照

    /** 認識器に渡す個人テンプレート。 */
    fun templatesFor(set: String): List<StrokeTemplate> {
        val bySymbol = templates[set] ?: return emptyList()
        val out = ArrayList<StrokeTemplate>()
        for ((symbol, list) in bySymbol) {
            for (e in list) out.add(StrokeTemplate(symbol, e.points))
        }
        return out
    }

    /** 学習済み文字の一覧（文字, 個数, トレーニング由来を含むか）。表示用にソート済み。 */
    fun summary(): List<Triple<String, Int, Boolean>> {
        val count = HashMap<String, Int>()
        val trained = HashSet<String>()
        for ((_, bySymbol) in templates) {
            for ((symbol, list) in bySymbol) {
                if (list.isEmpty()) continue
                count[symbol] = (count[symbol] ?: 0) + list.size
                if (list.any { it.source == SOURCE_TRAINING }) trained.add(symbol)
            }
        }
        return count.entries.sortedBy { it.key }
            .map { Triple(it.key, it.value, it.key in trained) }
    }

    /** 個人テンプレートを持っている文字（見本オーバーレイのマーク用）。 */
    fun learnedSymbols(): Set<String> {
        val out = HashSet<String>()
        for ((_, bySymbol) in templates) {
            for ((symbol, list) in bySymbol) if (list.isNotEmpty()) out.add(symbol)
        }
        return out
    }

    fun isEmpty(): Boolean = templates.all { it.value.all { e -> e.value.isEmpty() } }

    // ------------------------------------------------------------------ 学習

    /**
     * 訂正イベントで得たストロークを候補として追加する。
     *
     * @param normalized [StrokeRecognizer.normalizeForTemplate] 済みのポリライン
     * @param guard      衝突ガード用。null なら判定を省略する
     * @return 個人テンプレートへ昇格したら true
     */
    fun addCandidate(
        set: String,
        symbol: String,
        normalized: List<Pt>,
        now: Long,
        guard: StrokeRecognizer?,
    ): Boolean {
        val fresh = Entry(normalized, 0, now)

        // すでに同じ字形の個人テンプレートがあるなら、使用実績だけ更新して終わり
        val promoted = bucket(templates, set, symbol)
        val dup = promoted.firstOrNull { similarity(it.points, normalized) >= SIMILAR_THRESHOLD }
        if (dup != null) {
            dup.uses++
            dup.updatedAt = now
            save()
            return false
        }

        val pending = bucket(candidates, set, symbol)
        val match = pending.firstOrNull { similarity(it.points, normalized) >= SIMILAR_THRESHOLD }
        if (match == null) {
            // 1 回目。まだ覚えない。
            pending.add(fresh)
            if (pending.size > MAX_CANDIDATES_PER_SYMBOL) pending.removeAt(0)
            save()
            return false
        }

        // 2 回目。昇格の可否を衝突ガードで判定する。
        if (guard != null && !passesCollisionGuard(guard, symbol, normalized)) {
            // 紛らわしいので昇格させない。候補も捨てて溜め直す。
            pending.remove(match)
            save()
            return false
        }

        pending.remove(match)
        insert(promoted, Entry(normalized, 1, now, SOURCE_IMPLICIT))
        save()
        return true
    }

    /**
     * トレーニング画面から直接登録する（2 回の訂正を待たない）。
     * 衝突ガードは暗黙学習と同じものを通す。
     *
     * @return 登録できたら true、ガードで弾かれたら false
     */
    fun addTrainingTemplate(
        set: String,
        symbol: String,
        normalized: List<Pt>,
        now: Long,
        guard: StrokeRecognizer?,
    ): Boolean {
        val promoted = bucket(templates, set, symbol)
        val dup = promoted.firstOrNull { similarity(it.points, normalized) >= SIMILAR_THRESHOLD }
        if (dup != null) {
            dup.uses++
            dup.updatedAt = now
            // 暗黙学習で入っていたものはトレーニング由来へ格上げして保護する
            dup.source = SOURCE_TRAINING
            save()
            return true
        }
        if (guard != null && !passesCollisionGuard(guard, symbol, normalized)) return false
        insert(promoted, Entry(normalized, 1, now, SOURCE_TRAINING))
        save()
        return true
    }

    /**
     * 上限を守りつつ追加する。方式1・方式2 合算で 1 文字 [MAX_TEMPLATES_PER_SYMBOL] 個。
     * 追い出す順は「暗黙学習のもの -> 使用回数が少ないもの -> 古いもの」。
     * 明示的に練習したトレーニング由来を優先して残す。
     */
    private fun insert(list: MutableList<Entry>, entry: Entry) {
        list.add(entry)
        while (list.size > MAX_TEMPLATES_PER_SYMBOL) {
            val victim = list.minWithOrNull(
                compareBy<Entry> { if (it.source == SOURCE_TRAINING) 1 else 0 }
                    .thenBy { it.uses }
                    .thenBy { it.updatedAt },
            ) ?: break
            list.remove(victim)
        }
    }

    /**
     * 昇格ガード。
     *
     * 訂正で拾ったストロークは「今は別の文字 X として認識されてしまう形」なので、
     * X のスコアが高いこと自体は当たり前（それが訂正の理由）。
     * ここで防ぎたいのは「他の文字の標準字形そのものを奪ってしまう」ケースだけなので、
     * 他文字のスコアが極端に高い（= ほぼその文字の字形と同一）ときにだけ見送る。
     */
    private fun passesCollisionGuard(
        guard: StrokeRecognizer,
        symbol: String,
        normalized: List<Pt>,
    ): Boolean {
        val scaled = normalized.map { Pt(it.x * 300f, it.y * 300f) }
        val scores = guard.bestScorePerSymbol(scaled)
        var bestOther = -1f
        for ((sym, sc) in scores) {
            if (sym == symbol) continue
            if (sc > bestOther) bestOther = sc
        }
        return bestOther < CONFLICT_LIMIT
    }

    /** 正規化済みポリライン同士のコサイン類似度。 */
    private fun similarity(a: List<Pt>, b: List<Pt>): Float {
        val va = vector(a) ?: return -1f
        val vb = vector(b) ?: return -1f
        var dot = 0f
        for (i in va.indices) dot += va[i] * vb[i]
        return dot
    }

    private fun vector(points: List<Pt>): FloatArray? {
        if (points.size < 2) return null
        var cx = 0f
        var cy = 0f
        for (p in points) { cx += p.x; cy += p.y }
        cx /= points.size
        cy /= points.size
        val v = FloatArray(points.size * 2)
        for (i in points.indices) {
            v[2 * i] = points[i].x - cx
            v[2 * i + 1] = points[i].y - cy
        }
        var sum = 0f
        for (f in v) sum += f * f
        val norm = kotlin.math.sqrt(sum)
        if (norm < 1e-6f) return null
        for (i in v.indices) v[i] /= norm
        return v
    }

    // ------------------------------------------------------------------ 永続化

    fun reset() {
        templates.clear()
        candidates.clear()
        runCatching { if (file.exists()) file.delete() }
        loadedStamp = 0L
    }

    /**
     * ファイルが自分の知らないうちに書き換わっていたら読み直す。
     * @return 読み直したら true
     */
    fun reloadIfChanged(): Boolean {
        val stamp = if (file.exists()) file.lastModified() else 0L
        if (stamp == loadedStamp) return false
        load()
        return true
    }

    fun load() {
        templates.clear()
        candidates.clear()
        loadedStamp = if (file.exists()) file.lastModified() else 0L
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            readSection(root.optJSONObject("templates"), templates)
            readSection(root.optJSONObject("candidates"), candidates)
        }
    }

    fun save() {
        runCatching {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("templates", writeSection(templates))
            root.put("candidates", writeSection(candidates))
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
            loadedStamp = file.lastModified()
        }
    }

    private fun readSection(
        obj: JSONObject?,
        into: HashMap<String, HashMap<String, MutableList<Entry>>>,
    ) {
        if (obj == null) return
        for (set in obj.keys()) {
            val bySymbol = obj.optJSONObject(set) ?: continue
            for (symbol in bySymbol.keys()) {
                val arr = bySymbol.optJSONArray(symbol) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val pts = e.optJSONArray("pts") ?: continue
                    val list = ArrayList<Pt>(pts.length() / 2)
                    var k = 0
                    while (k + 1 < pts.length()) {
                        list.add(Pt(pts.getDouble(k).toFloat(), pts.getDouble(k + 1).toFloat()))
                        k += 2
                    }
                    if (list.size >= 2) {
                        bucket(into, set, symbol).add(
                            Entry(
                                list,
                                e.optInt("uses", 0),
                                e.optLong("ts", 0L),
                                e.optString("src", SOURCE_IMPLICIT),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun writeSection(
        from: HashMap<String, HashMap<String, MutableList<Entry>>>,
    ): JSONObject {
        val out = JSONObject()
        for ((set, bySymbol) in from) {
            val setObj = JSONObject()
            for ((symbol, list) in bySymbol) {
                if (list.isEmpty()) continue
                val arr = JSONArray()
                for (e in list) {
                    val pts = JSONArray()
                    for (p in e.points) {
                        pts.put(Math.round(p.x * 1000f) / 1000.0)
                        pts.put(Math.round(p.y * 1000f) / 1000.0)
                    }
                    arr.put(
                        JSONObject()
                            .put("pts", pts)
                            .put("uses", e.uses)
                            .put("ts", e.updatedAt)
                            .put("src", e.source),
                    )
                }
                if (arr.length() > 0) setObj.put(symbol, arr)
            }
            if (setObj.length() > 0) out.put(set, setObj)
        }
        return out
    }

    companion object {
        const val FILE_NAME = "personal_strokes.json"

        @Volatile
        private var instance: PersonalTemplateStore? = null

        /**
         * プロセス内で共有する唯一のインスタンス。
         *
         * IME サービスとトレーニング画面は同一プロセスで動くので、別インスタンスを持つと
         * 「トレーニングで覚えた字形が IME に反映されない」「IME 終了時に古い内容で
         * ファイルを上書きしてしまう」という不整合が起きる。必ずここから取ること。
         */
        fun get(context: Context): PersonalTemplateStore {
            return instance ?: synchronized(this) {
                instance ?: PersonalTemplateStore(
                    File(context.applicationContext.filesDir, FILE_NAME),
                ).also {
                    it.load()
                    instance = it
                }
            }
        }
        private const val VERSION = 1

        /** 同じ書き方とみなすコサイン類似度。 */
        const val SIMILAR_THRESHOLD = 0.88f

        /**
         * 他文字の標準字形とこれ以上似ていたら昇格させない。
         * 「別の文字そのもの」を覚えて既存字形を壊すのを防ぐ。
         */
        const val CONFLICT_LIMIT = 0.95f

        const val MAX_TEMPLATES_PER_SYMBOL = 2
        private const val MAX_CANDIDATES_PER_SYMBOL = 4

        const val SOURCE_TRAINING = "training"
        const val SOURCE_IMPLICIT = "implicit"

        const val SET_ALPHA = "alpha"
        const val SET_NUMBER = "number"
    }
}
