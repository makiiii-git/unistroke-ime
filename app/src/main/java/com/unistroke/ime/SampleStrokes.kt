package com.unistroke.ime

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「見本表示専用」のストローク。
 *
 * K / X の「∝」は、認識テンプレートの点数（23 点）のままでも折れ角は出ないが、
 * 見本は大きく描かれる（トレーニング画面は 200dp 超）ので、
 * 円弧をより細かく刻み、ループもひとまわり大きく取った専用の字形を使う。
 *
 * 字形は [loopGlyph] がパラメトリックに生成する。認識テンプレート
 * （[StrokeTemplates] の "k" / "x" / "+"）も **同じ式** を r=0.2071 / arm=0.9142 で
 * 離散化したものなので、見本と認識のループの回り方は必ず一致する。
 *
 * **認識には一切使わない。**
 */
object SampleStrokes {

    /** 見本のループ半径（[loopGlyph] のローカル座標系）。認識テンプレートより気持ち大きい。 */
    private const val SAMPLE_LOOP_R = 0.26f

    /** 見本の腕（進入・退出の直線）の長さ。 */
    private const val SAMPLE_ARM = 0.95f

    /** 見本の円弧の分割数。大きく描いても折れが見えない程度に細かく取る。 */
    private const val SAMPLE_ARC_STEPS = 48

    /** 度 -> ラジアン（[StrokeRecognizer] と同じ値）。 */
    private const val DEG = 0.017453292f

    /** cos45° = sin45°。 */
    private val SQ = sqrt(0.5f)

    /**
     * 「∝」字形（K の向き）をパラメトリックに生成する。
     *
     * 構成はすべて G1 連続（接線が繋がる = 折れ角ゼロ）:
     *
     *   1. 右上から進行方向 135°（左下がり）の直線で接点 T1 へ入る
     *   2. 半径 [r] の円を **時計回りに 270 度** 回る（位置角 45° -> 315°）
     *   3. 接点 T2 から進行方向 45°（右下がり）の直線で右下へ抜ける
     *
     * 円は進入直線・退出直線の双方に接するので、継ぎ目で向きが跳ばない。
     * 交差するのは進入直線と退出直線だけで、そこがループの根元になる。
     *
     * 局所座標（T1 を原点）で組み立ててから、縦横比を保ったまま [0,1]^2 の中央へ収める。
     *
     * @param mirrored true なら x を反転する（= X の字形。ループは反時計回りになる）
     */
    fun loopGlyph(
        r: Float,
        arm: Float,
        steps: Int = SAMPLE_ARC_STEPS,
        mirrored: Boolean = false,
    ): List<Pt> {
        require(r > 0f && arm > 0f && steps >= 4) { "invalid loop glyph parameters" }

        // 円の中心は T1(0,0) の左上（進行方向 135° の右手側）
        val cx = -r * SQ
        val cy = -r * SQ

        val pts = ArrayList<Pt>(steps + 3)
        // 1) 進入始点（T1 から右上へ arm だけ戻した点）
        pts += Pt(arm * SQ, -arm * SQ)
        // 2) 円弧 45° -> 315°（画面座標では角度の増加 = 時計回り）
        for (i in 0..steps) {
            val th = (45f + 270f * i / steps) * DEG
            pts += Pt(cx + r * cos(th), cy + r * sin(th))
        }
        // 3) 退出終点（T2 から右下へ arm）
        val t2 = pts[pts.size - 1]
        pts += Pt(t2.x + arm * SQ, t2.y + arm * SQ)

        val fitted = fitUnitBox(pts)
        return if (mirrored) fitted.map { Pt(1f - it.x, it.y) } else fitted
    }

    /** 縦横比を保ったまま [0,1]^2 へ収め、中央へ寄せる。 */
    private fun fitUnitBox(pts: List<Pt>): List<Pt> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val w = maxX - minX
        val h = maxY - minY
        val span = max(w, h)
        if (span <= 0f) return pts
        val s = 1f / span
        val ox = (1f - w * s) / 2f
        val oy = (1f - h * s) / 2f
        return pts.map { Pt((it.x - minX) * s + ox, (it.y - minY) * s + oy) }
    }

    /** K: 右上から入り、折れ角なしで時計回りにループして右下へ抜ける。 */
    private val K: List<Pt> = loopGlyph(SAMPLE_LOOP_R, SAMPLE_ARM)

    /** X: K の左右反転（ループは反時計回り）。 */
    private val X: List<Pt> = loopGlyph(SAMPLE_LOOP_R, SAMPLE_ARM, mirrored = true)

    /** Punctuation / Extended の「+」も K と同じ字形なので同じ見本を使う。 */
    /**
     * 見本用の B。
     *
     * 認識テンプレートの B は左下で始まって左下へ**戻る**ので、
     * 書き始めの ● と書き終わりの矢印がぴったり重なってしまい、
     * 「どこから書くのか」が見本から読み取れない。
     * この系統（B / D / P / R）は書き順が直感に反するぶんトレーニングの価値が高いので、
     * 見本では最後をわずかに手前で止めて、● と矢印が別々に見えるようにする。
     * 形そのもの（通る場所と向き）は認識テンプレートと同じ。
     */
    private val B: List<Pt> = listOf(
        Pt(0.28f, 0.95f), Pt(0.28f, 0.50f), Pt(0.28f, 0.05f), Pt(0.60f, 0.09f),
        Pt(0.74f, 0.26f), Pt(0.64f, 0.44f), Pt(0.32f, 0.50f), Pt(0.68f, 0.56f),
        Pt(0.76f, 0.74f), Pt(0.62f, 0.92f), Pt(0.40f, 0.96f),
    )

    private val OVERRIDES: Map<String, List<Pt>> =
        mapOf("k" to K, "x" to X, "+" to K, "b" to B)

    /** 見本として描くべきポリライン。専用のものが無ければ [fallback] をそのまま使う。 */
    fun forSymbol(symbol: String, fallback: List<Pt>): List<Pt> =
        OVERRIDES[symbol] ?: fallback

    /** 見本用に誇張した字形を持つ文字か（説明文の出し分けに使う）。 */
    fun isExaggerated(symbol: String): Boolean = symbol in OVERRIDES
}
