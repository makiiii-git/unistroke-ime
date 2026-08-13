package com.unistroke.ime

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 画面座標・正規化座標に共通の点表現 (y は下向き)。 */
data class Pt(@JvmField val x: Float, @JvmField val y: Float)

/** 認識結果。[score] は -1..1 のコサイン類似度。 */
data class Recognition(val symbol: String, val score: Float)

/**
 * $1 Unistroke Recognizer (Protractor 最適化版) をベースにした一筆書き認識器。
 *
 * 本方式ではストロークの向きが意味を持つ（左->右の横線=スペース、右->左=バックスペース等）ため、
 * 回転不変にはしない。角度のゆらぎ吸収は候補側を ±15 度の範囲で数段階だけ回して
 * 最良スコアを採る、という限定的な形でのみ行う。
 */
class StrokeRecognizer(
    templates: List<StrokeTemplate>,
    personal: List<StrokeTemplate> = emptyList(),
) {

    private class Entry(
        val symbol: String,
        val vector: FloatArray,
        val bonus: Float,
        /** 直線 1 本の字形か（曲率ゲートの対象）。 */
        val line: Boolean,
        /** この字形が本来持つ横方向の折り返し回数（[SIMPLE_SYMBOLS] のみ意味を持つ）。 */
        val reversals: Int,
        /**
         * 折り返しゲートの許容差。0 ならゲート対象外。
         * ストロークの折り返しがテンプレートよりこの数以上多ければ候補から外す。
         */
        val reversalSlack: Int,
    )

    private val entries: List<Entry> =
        templates.mapNotNull { tpl ->
            vectorize(tpl.points, 0f)?.let {
                Entry(
                    tpl.symbol, it, 0f, tpl.symbol in LINE_SYMBOLS,
                    reversals(tpl.points), REVERSAL_GATE[tpl.symbol] ?: 0,
                )
            }
        } + personal.mapNotNull { tpl ->
            // 個人テンプレートはごく僅かな下駄だけを履かせる。標準字形を大きく上書きしない。
            vectorize(tpl.points, 0f)?.let {
                Entry(
                    tpl.symbol, it, PERSONAL_BONUS, tpl.symbol in LINE_SYMBOLS,
                    reversals(tpl.points), REVERSAL_GATE[tpl.symbol] ?: 0,
                )
            }
        }

    /**
     * @param points 画面座標のストローク（描かれた順）
     * @param minSizePx タップ・極小ストロークを除外する最小サイズ（px）
     * @param expected 文脈から次に来ると期待される文字（かなモードのローマ字文脈）。
     *                 空ならバイアス無し。
     * @param expectedBonus [expected] に含まれる文字へ加算する下駄。
     *                 タイブレーク程度の小ささに留めること（[CONTEXT_BONUS]）。
     */
    fun recognize(
        points: List<Pt>,
        minSizePx: Float,
        expected: Set<String> = emptySet(),
        expectedBonus: Float = 0f,
    ): Recognition? {
        if (points.size < MIN_POINTS || entries.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        // 単純タップ・極小ストロークの除外
        if (max(maxX - minX, maxY - minY) < minSizePx) return null
        if (pathLength(points) < minSizePx) return null

        val bias = if (expected.isEmpty()) 0f else expectedBonus
        // 曲がりの多いストロークは直線 1 本の字形と張り合わせない（下記 [isCurved] 参照）
        val curved = isCurved(points)
        val strokeReversals = reversals(points)
        val aspect = aspectOf(points)
        // 角度ゲートは直線的なストロークにだけ掛ける（曲線には意味が無い）
        val straight = straightness(points) >= STRAIGHT_GATE
        val slant = verticalSlant(points)
        val ambiguousSlant = straight &&
            slant > RETURN_MIN_SLANT && slant < VERTICAL_MAX_SLANT

        var bestSymbol: String? = null
        var bestScore = -1f
        // バイアスをかけない場合の最良も同時に持つ（コマンド保護の判定に使う）
        var plainSymbol: String? = null
        var plainScore = -1f
        for (rotation in ROTATIONS) {
            val v = vectorize(points, rotation) ?: continue
            for (e in entries) {
                if (curved && e.line) continue
                if (reversalGated(curved, strokeReversals, e, aspect)) continue
                if (straight && angleGated(e.symbol, slant)) continue
                var dot = 0f
                val tv = e.vector
                for (i in v.indices) dot += v[i] * tv[i]
                var raw = dot + e.bonus
                // 曖昧帯では、#return は縦線系を明確に上回らないと勝てない
                if (ambiguousSlant && e.symbol == RETURN_SYMBOL) raw -= AMBIGUOUS_RETURN_PENALTY
                if (raw > plainScore) {
                    plainScore = raw
                    plainSymbol = e.symbol
                }
                // 文脈バイアスは加算のみ。他の文字を減点しないので、
                // 明確に別の字形のほうが高スコアならそちらが勝つ（タイブレーク寄り）。
                val score = if (bias != 0f && e.symbol in expected) raw + bias else raw
                if (score > bestScore) {
                    bestScore = score
                    bestSymbol = e.symbol
                }
            }
        }

        // コマンド（スペース / バックスペース / リターン / 各シフト）は
        // 取り違えると破壊的なので、文脈バイアスで覆さない。
        // バイアス無しでコマンドが勝っているなら、そのままコマンドとして扱う。
        if (bias != 0f && plainSymbol != null && plainSymbol != bestSymbol &&
            plainScore >= SCORE_THRESHOLD && isCommand(plainSymbol)
        ) {
            return Recognition(plainSymbol, plainScore)
        }

        val symbol = bestSymbol ?: return null
        return if (bestScore >= SCORE_THRESHOLD) Recognition(symbol, bestScore) else null
    }

    /**
     * 診断用: スコア上位 [limit] 件を降順で返す。
     *
     * [recognize] と同じスコアリング（個人テンプレートの下駄・文脈バイアス込み）を使うが、
     * **採用閾値もサイズ下限も適用しない**。
     * 棄却されたストロークが「実際は何点で、何と紛らわしかったのか」を見るためのもの。
     *
     * [recognize] が null を返したときログに出せるのは「棄却された」という事実だけで、
     * ベストスコアが 0.77 だったのか 0.30 だったのかが分からない。
     * その死角を埋めるための API なので、デバッグログが有効なときだけ呼ぶこと
     * （回転数 x テンプレート数のループをもう一周するぶんのコストがかかる）。
     */
    fun topCandidates(
        points: List<Pt>,
        expected: Set<String> = emptySet(),
        expectedBonus: Float = 0f,
        limit: Int = 3,
    ): List<Recognition> {
        if (points.size < 2 || entries.isEmpty() || limit <= 0) return emptyList()
        val bias = if (expected.isEmpty()) 0f else expectedBonus
        val curved = isCurved(points)
        val strokeReversals = reversals(points)
        val aspect = aspectOf(points)
        val straight = straightness(points) >= STRAIGHT_GATE
        val slant = verticalSlant(points)
        val best = HashMap<String, Float>(entries.size)
        for (rotation in ROTATIONS) {
            val v = vectorize(points, rotation) ?: continue
            for (e in entries) {
                if (curved && e.line) continue
                if (reversalGated(curved, strokeReversals, e, aspect)) continue
                if (straight && angleGated(e.symbol, slant)) continue
                var dot = 0f
                val tv = e.vector
                for (i in v.indices) dot += v[i] * tv[i]
                var score = dot + e.bonus
                if (bias != 0f && e.symbol in expected) score += bias
                val prev = best[e.symbol]
                if (prev == null || score > prev) best[e.symbol] = score
            }
        }
        return best.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Recognition(it.key, it.value) }
    }

    /** コマンドストローク（文字ではない疑似シンボル）か。 */
    private fun isCommand(symbol: String): Boolean = symbol.startsWith(COMMAND_PREFIX)

    /**
     * 文字ごとの最良スコアを返す（学習時の衝突ガード用）。
     * 個人テンプレートのボーナスは加算しない生のスコア。
     */
    fun bestScorePerSymbol(points: List<Pt>): Map<String, Float> {
        val out = HashMap<String, Float>()
        if (points.size < MIN_POINTS) return out
        for (rotation in ROTATIONS) {
            val v = vectorize(points, rotation) ?: continue
            for (e in entries) {
                var dot = 0f
                val tv = e.vector
                for (i in v.indices) dot += v[i] * tv[i]
                val prev = out[e.symbol]
                if (prev == null || dot > prev) out[e.symbol] = dot
            }
        }
        return out
    }

    /**
     * 前処理: 64 点リサンプル -> 限定回転 -> スケール正規化 -> 重心を原点へ -> 単位ベクトル化。
     * 単位ベクトル化するため一様スケールの差は結果に影響しない。
     */
    private fun vectorize(src: List<Pt>, rotation: Float): FloatArray? {
        if (src.size < 2) return null
        if (pathLength(src) <= 0f) return null

        // 速書きで点が少ないストロークは、先に曲線として補間してから等間隔化する。
        // 折れ線のまま 64 点へ引き伸ばすと、点と点の間が直線で埋まって曲率が消え、
        // 「C を書いたのに < になる」たぐいの取り違えが起きる。
        var pts = interpolateIfSparse(src)
        // 直線らしさは元のストロークで測る（リサンプルしても端点と軌跡長は変わらない）
        val curved = isCurved(src)
        pts = resample(pts, RESAMPLE_COUNT, pathLength(pts))
        if (rotation != 0f) pts = rotate(pts, rotation)
        pts = normalizeScale(pts, curved)
        pts = translateToOrigin(pts)

        val v = FloatArray(RESAMPLE_COUNT * 2)
        for (i in pts.indices) {
            v[2 * i] = pts[i].x
            v[2 * i + 1] = pts[i].y
        }
        var sum = 0f
        for (f in v) sum += f * f
        val norm = sqrt(sum)
        if (norm < 1e-6f) return null
        for (i in v.indices) v[i] /= norm
        return v
    }

    /**
     * 点数が [SPARSE_POINTS] 未満のストロークを Catmull-Rom スプラインで補間する。
     *
     * 端末が速いストロークを数点しか配送できなかったときの救済。
     * 通過点は動かさない（スプラインが制御点を必ず通る）ので、
     * 十分な点数があるストロークには一切影響しないよう閾値で切っている。
     *
     * ハーネス実測（英字ゾーン・弧長比 0.10 の角丸め）:
     *   14 点 99.4% -> 99.6% / 8 点 93.4% -> 94.4%
     *   7 点  91.7% -> 93.6% / 6 点 89.5% -> 91.9%
     * どの条件でも悪化せず、点が少ないほど効く。
     */
    private fun interpolateIfSparse(src: List<Pt>): List<Pt> {
        val n = src.size
        if (n >= SPARSE_POINTS || n < 3) return src
        val perSegment = ((SPARSE_POINTS * 2) / n).coerceIn(2, 16)
        val out = ArrayList<Pt>((n - 1) * perSegment + 1)
        for (i in 0 until n - 1) {
            // 端は自分自身を折り返して制御点にする
            val p0 = src[if (i == 0) 0 else i - 1]
            val p1 = src[i]
            val p2 = src[i + 1]
            val p3 = src[if (i + 2 >= n) n - 1 else i + 2]
            for (k in 0 until perSegment) {
                val t = k.toFloat() / perSegment
                out += catmullRom(p0, p1, p2, p3, t)
            }
        }
        out += src[n - 1]
        return out
    }

    private fun catmullRom(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Float): Pt {
        val t2 = t * t
        val t3 = t2 * t
        val x = 0.5f * (
            2f * p1.x + (-p0.x + p2.x) * t +
                (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3
            )
        val y = 0.5f * (
            2f * p1.y + (-p0.y + p2.y) * t +
                (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
            )
        return Pt(x, y)
    }

    private fun resample(points: List<Pt>, n: Int, totalLength: Float): List<Pt> {
        val interval = totalLength / (n - 1)
        if (interval <= 0f) return List(n) { points.first() }

        val out = ArrayList<Pt>(n)
        out += points.first()
        var accumulated = 0f
        var prev = points.first()
        var i = 1
        while (i < points.size && out.size < n) {
            val cur = points[i]
            val seg = hypot(cur.x - prev.x, cur.y - prev.y)
            if (seg <= 0f) {
                prev = cur
                i++
                continue
            }
            if (accumulated + seg >= interval) {
                val t = (interval - accumulated) / seg
                val np = Pt(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out += np
                prev = np
                accumulated = 0f
            } else {
                accumulated += seg
                prev = cur
                i++
            }
        }
        while (out.size < n) out += points.last()
        return out
    }

    private fun rotate(points: List<Pt>, radians: Float): List<Pt> {
        val c = centroid(points)
        val cs = cos(radians)
        val sn = sin(radians)
        return points.map {
            val dx = it.x - c.x
            val dy = it.y - c.y
            Pt(dx * cs - dy * sn + c.x, dx * sn + dy * cs + c.y)
        }
    }

    /**
     * ストロークの「直線らしさ」= 始点と終点の距離 / 軌跡長。
     *
     * 直線なら 1.0、行き来のある字形ほど 0 に近づく（閉じた o は 0）。
     * 総回転角のような局所的な指標と違い、手ぶれで角度が細かく振れても値が動かない
     * （軌跡長がわずかに伸びるだけ）。実測でも、雑に書いた直線は 0.82 以上、
     * 幅の狭い ε でも 0.72 以下と、はっきり分かれる。
     */
    private fun straightness(points: List<Pt>): Float {
        val length = pathLength(points)
        if (length <= 0f) return 1f
        val a = points.first()
        val b = points.last()
        return hypot(b.x - a.x, b.y - a.y) / length
    }

    /**
     * 始点 -> 終点の弦が**垂直軸**となす角（度・0〜90）。**正規化前の生座標**で測る。
     *
     * 正規化後のスコアでは i と #return を分離できない。理由は測定済みで、
     *   1. 非一様スケールは縦横比が [ASPECT_LIMIT]（0.40）以上のときだけ掛かる
     *      = 傾き atan(0.40) = 21.8 度が境目
     *   2. ±15 度の回転探索が、10 度の縦線を 25 度まで持ち上げる
     *   3. すると縦横比が 0.466 になって非一様スケールが掛かり、**きっかり 45 度**へ化ける
     *   4. [recognize] は回転ごとの最大値を採るので、この 1.000 が必ず勝つ
     * つまり「形が似ている」のではなく正規化の副作用なので、スコアでは切れない。
     * 生の角度なら、この化けの影響を受けずに判定できる。
     */
    private fun verticalSlant(points: List<Pt>): Float {
        val a = points.first()
        val b = points.last()
        val dx = kotlin.math.abs(b.x - a.x)
        val dy = kotlin.math.abs(b.y - a.y)
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(kotlin.math.atan2(dx, dy).toDouble()).toFloat()
    }

    /**
     * 生の角度による直線系の絞り込み。除外するなら true。
     *
     *   [slant] <= [RETURN_MIN_SLANT]      縦線とみなす -> #return を外す
     *   [slant] >= [VERTICAL_MAX_SLANT]    斜線とみなす -> 縦線系（i / 1 / シフト）を外す
     *   その間（曖昧帯）                    どちらも残すが、#return は不利に扱う
     *
     * 曖昧帯で #return を不利にするのは、取り違えたときの被害が非対称だから。
     * 「i が出ない」は書き直せば済むが、「意図せず確定・改行が入る」は取り返しがつかない。
     */
    private fun angleGated(symbol: String, slant: Float): Boolean = when {
        symbol == RETURN_SYMBOL -> slant <= RETURN_MIN_SLANT
        symbol in VERTICAL_SYMBOLS -> slant >= VERTICAL_MAX_SLANT
        else -> false
    }

    /**
     * 始点と終点を結ぶ弦から見た「横揺れ」の回数。
     *
     * 各点の弦からの**符号付き垂直偏差**を見て、符号が反転した回数を数える。
     * 偏差がストロークの大きさの [SWING_DEADBAND] 未満のあいだは無視するので、
     * 手ぶれによる微小な往復は数えない（不感帯）。
     *
     * 直線らしさ（始点終点距離 / 軌跡長）だけでは、
     * **幅が狭くて縦に長い波**（「〜」を縦長に書いた e）が直線と区別できない。
     * 端から端までの距離は軌跡長に近いので直線らしさが 0.9 を超えてしまい、i と競合する。
     * 横揺れは、そういう字形でも「行って戻って」を素直に拾える。
     */
    private fun lateralSwings(points: List<Pt>): Int {
        if (points.size < 3) return 0
        val a = points.first()
        val b = points.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chord = hypot(dx, dy)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        // 弦が短い（大きく往復する）字形でも基準が 0 にならないよう、対角と大きいほうを使う
        val scale = max(chord, hypot(maxX - minX, maxY - minY))
        if (scale <= 0f) return 0
        val deadband = SWING_DEADBAND * scale
        // 弦に対する法線（弦が潰れている場合は横方向を使う）
        val ux: Float
        val uy: Float
        if (chord <= 1e-6f) {
            ux = 1f; uy = 0f
        } else {
            ux = dx / chord; uy = dy / chord
        }
        var count = 0
        var sign = 0
        for (p in points) {
            val deviation = (p.x - a.x) * -uy + (p.y - a.y) * ux
            if (kotlin.math.abs(deviation) < deadband) continue
            val s = if (deviation > 0f) 1 else -1
            if (sign == 0) {
                sign = s
            } else if (s != sign) {
                count++
                sign = s
            }
        }
        return count
    }

    /**
     * 直線 1 本の字形（[LINE_SYMBOLS]）と張り合わせてはいけないほど曲がっているか。
     *
     * 縦長で幅の狭い ε を書くと、線分の大半が下向きになるため
     * 方向ベクトル列のコサイン類似度では i とほとんど区別が付かない
     * （実機ログで i:0.952 / e:0.933 のような並びが出ていた）。
     * 「曲がっているストロークはそもそも直線ではない」という形状の事実で先に切る。
     */
    private fun isCurved(points: List<Pt>): Boolean =
        straightness(points) < STRAIGHT_GATE ||
            lateralSwings(points) >= SWING_GATE

    /**
     * 横方向の折り返し回数。
     *
     * ε は「左 -> 右 -> 左 -> 右」で 3 回折り返すのに対し、
     * f / l / t は 0〜1 回しか折り返さない。この回数は形そのものの性質なので、
     * 上弧の浅さや角の丸さといった書き癖の影響をほとんど受けない。
     *
     * 横幅に対して [REVERSAL_PROMINENCE] 未満の細かい揺れは折り返しと数えないので、
     * 手ぶれで回数が増えることはない。
     */
    private fun reversals(points: List<Pt>): Int {
        if (points.size < 3) return 0
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
        }
        val extent = maxX - minX
        if (extent <= 0f) return 0
        val threshold = REVERSAL_PROMINENCE * extent
        var count = 0
        var direction = 0
        var anchor = points[0].x
        for (i in 1 until points.size) {
            val d = points[i].x - anchor
            if (direction == 0) {
                if (kotlin.math.abs(d) >= threshold) {
                    direction = if (d > 0) 1 else -1
                    anchor = points[i].x
                }
            } else if (d * direction > 0) {
                anchor = points[i].x
            } else if (kotlin.math.abs(d) >= threshold) {
                count++
                direction = -direction
                anchor = points[i].x
            }
        }
        return count
    }

    /**
     * 折り返し回数が [strokeReversals] のストロークで、この字形を候補から外すか。
     *
     * ε（3 回）を f / l（0 回）と張り合わせないためのもの。
     * 許容差は字形ごとに [REVERSAL_GATE] で決める。
     *
     * 対象は [SIMPLE_SYMBOLS] だけ。全字形に効かせると、雑に書いた h や
     * 「細く下ろして戻す」記号（# : |）が自分自身の字形を除外してしまう
     * （実測で最大 10%）。過度に吸い込む数種類に絞るほうが副作用が読める。
     *
     * 直線に近いストロークには使わない（[curved] が false のときは常に false を返す）。
     * 斜めに倒れた縦線は横幅を持ってしまい、手ぶれで折り返し回数がいくらでも増えるため。
     * そちらは曲率ゲートの担当なので、二重に判定しない。
     */
    private fun reversalGated(
        curved: Boolean,
        strokeReversals: Int,
        entry: Entry,
        aspect: Float,
    ): Boolean = entry.reversalSlack > 0 && curved && aspect >= ASPECT_FLOOR &&
        strokeReversals - entry.reversals >= entry.reversalSlack

    private fun aspectOf(points: List<Pt>): Float {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val w = maxX - minX
        val h = maxY - minY
        val longSide = max(w, h)
        return if (longSide <= 0f) 1f else min(w, h) / longSide
    }

    /**
     * バウンディングボックスを単位正方形へ非一様スケールする。
     *
     * 横線・縦線のように縦横比が極端なストロークは、潰れ・ノイズ増幅を避けるため
     * 非一様スケールを行わない（後段の単位ベクトル化が一様スケールを吸収する）。
     * ただし**曲がりの多い字形は細長くても非一様スケールを適用する** ――
     * 幅の狭い ε がここでスキップされると、幅の情報が均されないまま
     * 縦成分だけが支配的になり、正方形に近い e テンプレートと形が合わなくなる。
     */
    private fun normalizeScale(points: List<Pt>, curved: Boolean): List<Pt> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val w = maxX - minX
        val h = maxY - minY
        val longSide = max(w, h)
        if (longSide <= 0f) return points
        val aspect = min(w, h) / longSide
        // 細長いストロークは基本そのまま。ただし曲がりの多い字形（幅の狭い ε など）は
        // 引き伸ばして正方形に近いテンプレートと形を合わせる。
        // ただし「細く下ろして戻す」記号（: | _ ' ! など・縦横比 0.07 以下）まで
        // 引き伸ばすと幅方向のノイズが何倍にも増幅されるので、下限を設ける。
        if (aspect < ASPECT_LIMIT && !(curved && aspect >= ASPECT_FLOOR)) return points
        return points.map { Pt((it.x - minX) / w, (it.y - minY) / h) }
    }

    private fun translateToOrigin(points: List<Pt>): List<Pt> {
        val c = centroid(points)
        return points.map { Pt(it.x - c.x, it.y - c.y) }
    }

    private fun centroid(points: List<Pt>): Pt {
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        return Pt(sx / points.size, sy / points.size)
    }

    private fun pathLength(points: List<Pt>): Float {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return sum
    }

    companion object {
        private const val RESAMPLE_COUNT = 64
        /** これ未満の点数のストロークは認識にかけない。 */
        const val MIN_POINTS = 4

        /**
         * これ未満の点数なら曲線補間してから正規化する（[interpolateIfSparse]）。
         * ハーネスでは 8 点以下で効き、14 点以上では差が出ない。
         */
        const val SPARSE_POINTS = 16

        /**
         * これ未満の縦横比のストロークは非一様スケールしない。
         * 0.40 にすることで、多少傾いた横線（雑なスペース/バックスペース）が
         * 斜線に引き伸ばされてリターンと衝突するのを防ぐ。
         */
        private const val ASPECT_LIMIT = 0.40f

        /** 採用に必要なコサイン類似度。 */
        private const val SCORE_THRESHOLD = 0.78f

        /**
         * これ未満の「直線らしさ」なら、直線 1 本の字形（[LINE_SYMBOLS]）を候補から外す。
         *
         * Python ハーネス実測（相関のある手ぶれモデル・通常/雑の両方）:
         *   直線系ストロークの直線らしさ  最小 0.82
         *   幅の狭い ε（実機の誤認ケース）最大 0.72
         * その間を取る。直線側を誤って落とすとスペース・バックスペース・リターンが
         * 効かなくなって被害が大きいので、直線側に余裕を持たせた値にしてある。
         */
        const val STRAIGHT_GATE = 0.76f

        /**
         * 横揺れとして数えはじめる偏差（ストロークの大きさに対する割合）。不感帯。
         *
         * ハーネス実測（直線系 8 種 x 通常/雑/かなり雑）:
         *   自己除外率  0.05 -> 0.67% / 0.06 -> 0.33% / 0.07 -> 0.17%
         *   波形 e の認識  0.05 -> 82% / 0.06 -> 77% / 0.07 -> 73%
         * 直線系の**認識率**への影響はどの値でも測定限界以下だが、
         * 0.05 では #shift が 1 ポイント落ちたので 0.06 を採る。
         * シフトやスペースの取り違えは被害が大きいので、感度より安全側を優先する。
         */
        const val SWING_DEADBAND = 0.06f

        /** これ以上の横揺れがあれば直線系の字形を候補から外す。 */
        const val SWING_GATE = 2

        /**
         * これ以下の傾き（垂直から・度）なら縦線とみなし、#return を候補から外す。
         */
        const val RETURN_MIN_SLANT = 15f

        /**
         * これ以上の傾きなら斜線とみなし、縦線系（[VERTICAL_SYMBOLS]）を候補から外す。
         * #return テンプレートの実角度は 47 度なので、本来の書き方は余裕を持って通る。
         */
        const val VERTICAL_MAX_SLANT = 20f

        /** 曖昧帯（15〜20 度）で #return に課す不利。縦線系をこれ以上上回らないと勝てない。 */
        const val AMBIGUOUS_RETURN_PENALTY = 0.05f

        /** 縦線 1 本の字形。傾いたストロークでは候補から外す。 */
        val VERTICAL_SYMBOLS: Set<String> = setOf("i", "1", SHIFT_SYMBOL)

        /**
         * 曲がっていても、これ未満に細いストロークは非一様スケールしない。
         *
         * 実測の縦横比: 「細く下ろして戻す」系の記号（: | _ ' ! -）が 0.00〜0.07、
         * 実機で問題になった幅の狭い ε が 0.19〜0.47。その間を取る。
         */
        const val ASPECT_FLOOR = 0.15f

        /** 横幅に対してこの割合未満の揺れは折り返しと数えない。 */
        const val REVERSAL_PROMINENCE = 0.18f

        /** 既定の許容差。1 回ぶんの数え違いでは発動しない。 */
        const val REVERSAL_SLACK = 2

        /** z 専用の許容差（下記 [REVERSAL_GATE] の説明を参照）。 */
        const val REVERSAL_SLACK_TIGHT = 1

        /**
         * 折り返しゲートの対象にする字形。
         *
         * どれも「折り返しをほとんど持たない単純な形」なのに、
         * ε のような折り返しの多いストロークを高いスコアで吸ってしまう
         * （実機ログで f:0.989 / j:0.884 / l:0.870 など）。
         *
         * 逆にこれらを雑に書いても折り返し回数はテンプレートからほとんど増えない。
         * ハーネス実測（通常 / 雑 / かなり雑 で各 360 本）の自己除外率:
         *   c / f / l / t / y … 0 / 360
         *   j                … 1 / 360（最も雑なプロファイルでのみ）
         * h や「細く下ろして戻す」記号（# : |）は 5〜10% 自己除外されるので入れない。
         *
         * z だけ許容差を 1 にしている。z の折り返しは実測で 746/750 がちょうど 2 回、
         * **3 回に達したものが 1 本も無い**ので、差 1 で切っても自分自身は落ちない
         * （自己除外 0/750）。これで ε（3 回）の 91% が z を候補から外せる。
         * 他の字形は数え違いの幅があるので既定の 2 のままにする。
         */
        val REVERSAL_GATE: Map<String, Int> = mapOf(
            "c" to REVERSAL_SLACK,
            "f" to REVERSAL_SLACK,
            "j" to REVERSAL_SLACK,
            "l" to REVERSAL_SLACK,
            "t" to REVERSAL_SLACK,
            "y" to REVERSAL_SLACK,
            "z" to REVERSAL_SLACK_TIGHT,
        )

        /**
         * 直線 1 本の字形。理想字形の直線らしさがちょうど 1.0 になるものだけを入れる
         * （次に直線的な l / f / t でも 0.71 なので、境界は明確）。
         */
        val LINE_SYMBOLS: Set<String> = setOf(
            "i", "1", SPACE_SYMBOL, BACKSPACE_SYMBOL, RETURN_SYMBOL,
            SHIFT_SYMBOL, EXT_SYMBOL, EXT_ALT_SYMBOL,
        )

        // StrokeTemplates の定数をここで参照すると循環するので、値だけ持つ
        private const val SPACE_SYMBOL = "#space"
        private const val BACKSPACE_SYMBOL = "#backspace"
        private const val RETURN_SYMBOL = "#return"
        private const val SHIFT_SYMBOL = "#shift"
        private const val EXT_SYMBOL = "#ext"
        private const val EXT_ALT_SYMBOL = "#ext_slash"

        /** コマンド疑似シンボルの接頭辞（[StrokeTemplates] の定数はすべてこれで始まる）。 */
        private const val COMMAND_PREFIX = "#"

        /** 度 -> ラジアン。 */
        private const val DEG = 0.017453292f

        /** 許容する回転補正は ±15 度まで。 */
        private val ROTATIONS = floatArrayOf(-15f * DEG, -7.5f * DEG, 0f, 7.5f * DEG, 15f * DEG)

        /** 個人テンプレートに与える下駄。 */
        const val PERSONAL_BONUS = 0.02f

        /**
         * かなモードの文脈バイアス（ローマ字の続きとして期待される文字への下駄）。
         *
         * 値は Python ハーネス（`test_context_bias.py --sweep`）で
         * 「誤認識の削減量 vs 新規の誤爆」を掃引して決めた。
         * 実測（雑書きコーパス 336 ストローク）:
         *   0.02 → 救済 2 / 誤爆 0
         *   0.04 → 救済 7 / 誤爆 0   ← 採用
         *   0.05 → 救済 7 / 誤爆 0
         *   0.06 → 救済 8 / 誤爆 1
         *   0.15 → 救済 8 / 誤爆 3
         * 0.05 までは誤爆ゼロのまま取りこぼしだけが減り、0.06 以上で誤爆が出始める。
         * 余裕を見て 0.04 を採る。
         */
        const val CONTEXT_BONUS = 0.04f

        /** 学習用: ストロークを正規化ポリラインへ（テンプレートと同じ表現にする）。 */
        fun normalizeForTemplate(points: List<Pt>, count: Int = 32): List<Pt>? {
            if (points.size < 2) return null
            val len = pathLengthOf(points)
            if (len <= 0f) return null
            val pts = resampleTo(points, count, len)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            val side = max(maxX - minX, maxY - minY)
            if (side <= 0f) return null
            // 縦横比は保ったまま長辺を 1.0 にする（細長い字形を潰さない）
            return pts.map { Pt((it.x - minX) / side, (it.y - minY) / side) }
        }

        private fun pathLengthOf(points: List<Pt>): Float {
            var sum = 0f
            for (i in 1 until points.size) {
                sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            }
            return sum
        }

        private fun resampleTo(points: List<Pt>, n: Int, totalLength: Float): List<Pt> {
            val interval = totalLength / (n - 1)
            if (interval <= 0f) return List(n) { points.first() }
            val out = ArrayList<Pt>(n)
            out += points.first()
            var accumulated = 0f
            var prev = points.first()
            var i = 1
            while (i < points.size && out.size < n) {
                val cur = points[i]
                val seg = hypot(cur.x - prev.x, cur.y - prev.y)
                if (seg <= 0f) { prev = cur; i++; continue }
                if (accumulated + seg >= interval) {
                    val t = (interval - accumulated) / seg
                    val np = Pt(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                    out += np
                    prev = np
                    accumulated = 0f
                } else {
                    accumulated += seg
                    prev = cur
                    i++
                }
            }
            while (out.size < n) out += points.last()
            return out
        }
    }
}
