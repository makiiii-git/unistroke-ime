package com.unistroke.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 見本の字形を 1 つだけ大きく表示するビュー。
 *
 * 書き順が一目で分かるよう、ストロークが描かれていく様子を繰り返し再生する。
 * K/X のように認識テンプレートのままではループが視認しにくい字は、
 * [SampleStrokes] の見本専用ストローク（ループを大きく取り腕を交差させたもの）で描く。
 */
class StrokeSampleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 表示する字形。[symbol] を渡すと見本専用ストロークがあればそちらを使う。 */
    fun setStroke(symbol: String, fallback: List<Pt>) {
        points = SampleStrokes.forSymbol(symbol, fallback)
    }

    var points: List<Pt> = emptyList()
        set(value) {
            field = value
            rebuild()
            restart()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pad_zone_alpha)
    }
    /** 全体をうっすら出しておくガイド。 */
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_divider)
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 90
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_ink)
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pad_ink)
    }

    private val full = Path()
    private val partial = Path()
    private val measure = PathMeasure()
    private var pathLen = 0f
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    /** 0..1 で描画済みの割合。1 を超えた分は「描き終わりの間」。 */
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f + HOLD).apply {
        duration = DRAW_MS + (HOLD * DRAW_MS).toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private fun rebuild() {
        if (width == 0 || height == 0 || points.size < 2) {
            pathLen = 0f
            return
        }
        val pad = dp(20f)
        val side = min(width, height) - pad * 2
        StrokeArt.buildPath(points, (width - side) / 2f, (height - side) / 2f, side, full)
        measure.setPath(full, false)
        pathLen = measure.length
    }

    private fun restart() {
        if (!isAttachedToWindow || pathLen <= 0f) return
        animator.cancel()
        progress = 0f
        animator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
        restart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(8f), dp(8f), bg)
        if (pathLen <= 0f) return

        // 完成形をうっすら重ねて、どこへ向かうか分かるようにする
        canvas.drawPath(full, ghost)

        val t = min(1f, progress)
        val drawn = pathLen * t
        partial.reset()
        measure.getSegment(0f, drawn, partial, true)
        canvas.drawPath(partial, stroke)

        // 書き始め
        if (measure.getPosTan(0f, pos, tan)) {
            canvas.drawCircle(pos[0], pos[1], dp(5.5f), dot)
        }
        // 進行方向（ペン先）
        if (t > 0.02f && t < 1f) {
            StrokeArt.drawChevron(canvas, measure, drawn, stroke, dp(13f), pos, tan)
        }
        // 書き終わり
        if (t >= 1f) {
            StrokeArt.drawChevron(canvas, measure, pathLen, stroke, dp(14f), pos, tan)
        }
    }

    private companion object {
        const val DRAW_MS = 1700L
        /** 描き終わってからの余韻（DRAW_MS に対する比）。 */
        const val HOLD = 0.45f
    }
}

/**
 * 練習用の手書きパッド。
 * UniStrokeView と同じ認識器（英字ゾーン + 個人テンプレート）を使う。
 */
class PracticePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** @param symbol 認識結果（できなければ null） */
    fun interface Listener {
        fun onStroke(symbol: String?, score: Float, points: List<Pt>)
    }

    var listener: Listener? = null

    /** 個人テンプレートの供給元。 */
    var personalTemplates: (() -> List<StrokeTemplate>)? = null
        set(value) {
            field = value
            recognizer = null
        }

    private var recognizer: StrokeRecognizer? = null

    private fun recognizer(): StrokeRecognizer = recognizer ?: StrokeRecognizer(
        StrokeTemplates.templatesFor(Zone.ALPHA, SymbolMode.NORMAL),
        personalTemplates?.invoke().orEmpty(),
    ).also { recognizer = it }

    /** 学習でテンプレートが増えたら作り直す。 */
    fun refresh() {
        recognizer = null
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val minStrokeSize = dp(14f)
    private val moveTolerance = dp(1.2f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pad_zone_number)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_ink)
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_hint)
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val path = Path()
    private val points = ArrayList<Pt>(256)
    private var alpha = 1f
    private var hint: String? = null

    fun setHint(text: String?) {
        hint = text
        invalidate()
    }

    private val fade = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 400L
        addUpdateListener {
            alpha = it.animatedValue as Float
            if (alpha <= 0f) path.reset()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(8f), dp(8f), bg)
        val h = hint
        if (h != null && path.isEmpty) {
            canvas.drawText(
                h, width / 2f,
                height / 2f - (hintPaint.descent() + hintPaint.ascent()) / 2f,
                hintPaint,
            )
        }
        if (!path.isEmpty) {
            inkPaint.alpha = (alpha * 255f).toInt()
            canvas.drawPath(path, inkPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fade.cancel()
                alpha = 1f
                points.clear()
                points += Pt(x, y)
                path.reset()
                path.moveTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = points.lastOrNull() ?: return true
                if (hypot(x - last.x, y - last.y) < moveTolerance) return true
                points += Pt(x, y)
                path.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val raw = ArrayList<Pt>(points)
                points.clear()
                performClick()
                if (raw.size >= 2) {
                    val r = recognizer().recognize(raw, minStrokeSize)
                    listener?.onStroke(r?.symbol, r?.score ?: 0f, raw)
                }
                fade.cancel()
                fade.start()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                path.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, if (h > 0) h else max(w, dp(180f).toInt()))
    }
}
