package com.unistroke.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ストローク入力エリア。
 *
 * レイアウト
 *   - 最上段: ツールバー（モード表示 / 記号シフト / 文節インジケータ）
 *   - 左端:   縦ボタン列（モードトグル / ◀ / ▶ / 選択 / ?）
 *   - その右: 変換候補バー（変換中のみ）＋ 手書きゾーン（左 2/3 英字・右 1/3 数字）
 *   - [?] でジェスチャー見本オーバーレイ（テンプレートから直接描画）
 */
class UniStrokeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 認識結果・UI 操作の通知先。 */
    interface Listener {
        /**
         * 認識されたシンボル（1 文字 or StrokeTemplates のコマンド定数 or TAP / UNRECOGNIZED）。
         * @param stroke 認識に使った生のポイント列（癖の学習に使う）
         * @param zone   書かれたゾーン
         */
        fun onSymbol(symbol: String, stroke: List<Pt>, zone: Zone)

        /** モードトグル（abc ⇄ かな）。 */
        fun onModeToggle()

        /** カーソル移動。[forward] = true で右。 */
        fun onCursorMove(forward: Boolean)

        /** 全選択。 */
        fun onSelectAll()

        /**
         * 全選択ボタンの長押し。直前に確定した文字列だけを選択する。
         * 直前の確定が残っていなければ実装側で全選択へ落とす。
         */
        fun onSelectLastCommit()

        /** 候補バーの [index] 番目の候補が選ばれた。 */
        fun onCandidateSelected(index: Int)

        /** 候補バー端の 《 / 》（文節送り）。 */
        fun onSegmentStep(forward: Boolean)

        /** [?] の長押し。設定画面を開く。 */
        fun onOpenSettings()

        /**
         * これから書かれる 1 ストロークについて、文脈から期待される文字の集合。
         * かなモードのローマ字文脈（子音 pending -> 母音 など）で使う。
         * 空集合ならバイアスをかけない。認識の直前に毎回問い合わせる。
         */
        fun expectedSymbols(): Set<String> = emptySet()
    }

    var listener: Listener? = null

    /** 現在の入力モード表示（abc / Abc / ABC / かな / カナ）。トグルボタンにも出す。 */
    var modeLabel: String = MODE_LOWER
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 描画エリア右上に小さく出す状態インジケータ。
     * シフト（⇧ / ⇧⇧）・記号シフト（• / ＼）・変換中の文節番号をまとめて IME 側が組み立てる。
     */
    var statusLabel: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 画面が展開状態（Fold の内側など）か。パネルを利き手側へ寄せる。 */
    var expandedScreen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    /** 左利きレイアウト（ボタン列とゾーンを左右反転）。 */
    var leftHanded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val layout = PanelLayout()

    /** 認識に使うテンプレートセット。 */
    var symbolMode: SymbolMode = SymbolMode.NORMAL
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // ------------------------------------------------------------ candidates

    private var candidates: List<String> = emptyList()
    private var candidateIndex: Int = 0
    private var candidateScroll = 0f
    private val candidateRects = ArrayList<RectF>()

    /** 文節送り 《 》 を出すか（明示変換モードのみ true）。 */
    private var segmentNav = false

    /** 速書きデバッグログを logcat へ出すか（設定の隠しフラグ）。 */
    var debugStrokes: Boolean = false

    /**
     * 候補が端末内辞書だけで作られたことを示すチップを、候補バーの右端に出すか。
     * 通信していない（できなかった）ことがひと目で分かるようにするためのもので、
     * 候補の並びや操作には一切影響しない。文節編集モード中は 》 と場所が競合するので
     * 代わりに右上のインジケータへ出す（[statusLabel]）。
     */
    var onDeviceChip: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 候補バーの内容を差し替える。
     * @param selected ハイライトする添字。-1 でハイライトなし（自動候補）。
     * @param withSegmentNav 両端に文節送りボタンを出すか。
     */
    fun setCandidates(items: List<String>, selected: Int, withSegmentNav: Boolean) {
        val wasVisible = candidates.isNotEmpty()
        candidates = items
        candidateIndex = selected
        segmentNav = withSegmentNav
        candidateScroll = 0f
        ensureCandidateVisible()
        // バーの有無で入力ビュー全体の高さと touchableRegion が変わる
        if (wasVisible != items.isNotEmpty()) requestLayout()
        invalidate()
    }

    fun setCandidateSelection(index: Int) {
        candidateIndex = index
        ensureCandidateVisible()
        invalidate()
    }

    fun clearCandidates() {
        if (candidates.isEmpty()) return
        candidates = emptyList()
        candidateIndex = 0
        candidateScroll = 0f
        segmentNav = false
        requestLayout()
        invalidate()
    }

    // ------------------------------------------------------------- 寸法/塗り

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val columnWidth = dp(64f)
    private val candidateHeight = dp(42f)
    private val segArrowWidth = dp(30f)
    /**
     * 記号シフト中の最小ストロークサイズ。
     * 「細く下ろして戻す」ような小さい記号があるので低いままにする。
     */
    private val minSymbolStrokeSize = dp(14f)

    /**
     * 英字・数字ゾーン（通常モード）の最小ストロークサイズ。
     *
     * 実機で 17.4dp の偶発接触が o として入力されたため引き上げた。
     * ハーネス実測（雑書き）では、意図した文字の大きさが 40dp 以上なら
     * 20dp で棄却されるものは 0%、30dp でも 3.3% に留まる。
     * 実機ログの意図的な文字は 52〜113dp だったので実害は無い。
     */
    private val minLetterStrokeSize = dp(20f)

    /** いまのモードで採用する最小サイズ。 */
    private val minStrokeSize: Float
        get() = if (symbolMode == SymbolMode.NORMAL) minLetterStrokeSize else minSymbolStrokeSize
    private val tapMaxSize = dp(10f)
    private val moveTolerance = dp(1.2f)
    private val scrollSlop = dp(8f)

    private val recognizers = HashMap<Any, StrokeRecognizer>()

    /** 個人テンプレートの供給元（セット名 -> テンプレート）。IME が差し込む。 */
    var personalTemplates: ((String) -> List<StrokeTemplate>)? = null
        set(value) {
            field = value
            recognizers.clear()
        }

    /** 学習で個人テンプレートが増減したら呼ぶ。認識器を作り直す。 */
    fun refreshPersonalTemplates() {
        recognizers.clear()
        invalidate()
    }

    /**
     * IME にタッチを届けるべき領域を [region] へ書き込む。
     * ここに含まれない部分のタッチは背後のアプリへそのまま抜ける。
     * （[UniStrokeIME.onComputeInsets] から呼ばれる）
     *
     * パネル本体（縦ボタン列＋手書きゾーン）と、候補バー表示中はその矩形を union する。
     * 候補バーはパネルより幅が広い場合があるので、外接矩形ではなく 2 矩形の和で持つ。
     */
    fun fillTouchableRegion(region: Region) {
        if (width == 0 || height == 0) return
        // 最新のジオメトリで計算する（候補バーの出入り直後でも取りこぼさない）
        relayout()
        region.setEmpty()
        region.op(
            floor(layout.touchPanelLeft).toInt(),
            floor(layout.touchPanelTop).toInt(),
            ceil(layout.touchPanelRight).toInt(),
            ceil(layout.touchPanelBottom).toInt(),
            Region.Op.UNION,
        )
        if (layout.hasBar) {
            region.op(
                floor(layout.touchBarLeft).toInt(),
                floor(layout.touchBarTop).toInt(),
                ceil(layout.touchBarRight).toInt(),
                ceil(layout.touchBarBottom).toInt(),
                Region.Op.UNION,
            )
        }
    }

    /** 見本オーバーレイでマークする、個人テンプレートを持つ文字。 */
    var learnedSymbols: Set<String> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    private fun setNameFor(zone: Zone, mode: SymbolMode): String? = when {
        mode != SymbolMode.NORMAL -> null
        zone == Zone.NUMBER -> PersonalTemplateStore.SET_NUMBER
        else -> PersonalTemplateStore.SET_ALPHA
    }

    fun recognizerFor(zone: Zone, mode: SymbolMode): StrokeRecognizer {
        val key: Any = if (mode == SymbolMode.NORMAL) zone else mode
        return recognizers.getOrPut(key) {
            val setName = setNameFor(zone, mode)
            val personal = if (setName == null) emptyList()
            else personalTemplates?.invoke(setName).orEmpty()
            StrokeRecognizer(StrokeTemplates.templatesFor(zone, mode), personal)
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_divider)
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(7f)), 0f)
    }

    private val solidLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_divider)
        strokeWidth = dp(1f)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_ink)
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fadePaint = Paint(strokePaint)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_text)
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_text)
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** 「端末内」チップの文字。候補より一段小さく、目立ちすぎないようにする。 */
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_text)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val candidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_text)
        textSize = dp(17f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_hint)
        textSize = dp(26f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    // 見本オーバーレイ用
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.pad_ink)
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sampleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pad_ink)
    }
    private val sampleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_text)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val sampleTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_hint)
        textSize = dp(12f)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** ジェスチャー見本の隅に出すビルド印（古い APK が残っていないかの確認用）。 */
    private val buildStampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pad_hint)
        textSize = dp(9f)
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val buildStamp: String = BuildInfo.shortLabel(context)

    // ------------------------------------------------------------------ state

    private val activePath = Path()
    private val fadingPath = Path()
    private val points = ArrayList<Pt>(256)
    private val samplePath = Path()

    private var tracking = false
    private var zone = Zone.ALPHA
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f

    private enum class UiTarget {
        NONE, BTN_MODE, BTN_LEFT, BTN_RIGHT, BTN_SELECT, BTN_HELP,
        CANDIDATES, SEG_PREV, SEG_NEXT, OVERLAY,
    }

    private var uiTarget = UiTarget.NONE
    private var pressed: UiTarget = UiTarget.NONE

    private var strokeAlpha = 1f

    /** 縦ボタン列。上から モードトグル / ◀ / ▶ / 選択 / ? */
    private val buttonRects = Array(BUTTON_COUNT) { RectF() }

    /** [?] 長押しで設定画面。押している間に発火したら通常タップは無効化する。 */
    private var helpLongPressFired = false

    /** 全選択ボタンの長押しが発火したか（発火したら離しても全選択しない）。 */
    private var selectLongPressFired = false

    private val helpLongPressRunnable = Runnable {
        helpLongPressFired = true
        setPressedTarget(UiTarget.NONE)
        listener?.onOpenSettings()
    }

    private val selectLongPressRunnable = Runnable {
        selectLongPressFired = true
        setPressedTarget(UiTarget.NONE)
        listener?.onSelectLastCommit()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            when (pressed) {
                UiTarget.BTN_LEFT -> listener?.onCursorMove(false)
                UiTarget.BTN_RIGHT -> listener?.onCursorMove(true)
                else -> return
            }
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val strokeFade = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = STROKE_FADE_MS
        addUpdateListener {
            strokeAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    // ------------------------------------------------------------- ジオメトリ

    /**
     * 候補バーは描画エリアの「外」・上側に積む独立したバー。
     * 出現時は IME 全体の高さが上に伸びるだけで、
     * 描画ゾーンと縦ボタン列の高さ・レイアウトは常に一定に保たれる。
     */
    private val candidateBarVisible: Boolean get() = candidates.isNotEmpty()
    private val candidateTop: Float get() = 0f
    private val candidateBottom: Float get() = candidateHeight

    private val contentTop: Float get() = if (candidateBarVisible) candidateHeight else 0f

    private val zoneTop: Float get() = contentTop + dp(4f)

    private val barInset: Float get() = if (segmentNav) segArrowWidth else dp(4f)

    private fun relayout() {
        layout.update(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            candidateHeight = candidateHeight,
            candidateVisible = candidateBarVisible,
            columnWidth = columnWidth,
            panelMaxWidth = dp(PANEL_MAX_WIDTH_DP),
            expanded = expandedScreen,
            leftHanded = leftHanded,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 入力窓の高さは固定。候補バーが出たぶんだけ上へ伸ばす。
        val h = dp(HEIGHT_DP) + if (candidateBarVisible) candidateHeight else 0f
        setMeasuredDimension(width, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        layoutButtons()
        clearStroke()
    }

    private fun layoutButtons() {
        val pad = dp(2.5f)
        for (i in 0 until BUTTON_COUNT) {
            buttonRects[i].set(
                layout.columnLeft + pad,
                layout.buttonTop(i, BUTTON_COUNT) + pad,
                layout.columnRight - pad,
                layout.buttonBottom(i, BUTTON_COUNT) - pad,
            )
        }
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        relayout()
        layoutButtons()

        // 展開時はパネル以外を塗らない。背後のアプリがそのまま透けて見える。
        fillPaint.color = context.getColor(R.color.pad_bezel)
        if (layout.hasInactiveArea) {
            canvas.drawRect(layout.panelLeft, contentTop, layout.panelRight, h, fillPaint)
            if (candidateBarVisible) {
                canvas.drawRect(layout.barLeft, 0f, layout.barRight, contentTop, fillPaint)
            }
        } else {
            canvas.drawRect(0f, 0f, w, h, fillPaint)
        }

        drawButtonColumn(canvas, h)

        if (overlayVisible) {
            drawOverlay(canvas, w, h)
            return
        }

        if (candidateBarVisible) drawCandidateBar(canvas, w)

        val top = zoneTop
        val bottom = h - dp(4f)
        val zoneH = bottom - top

        fillPaint.color = context.getColor(R.color.pad_zone_alpha)
        canvas.drawRect(layout.alphaLeft, top, layout.alphaRight, bottom, fillPaint)
        fillPaint.color = context.getColor(R.color.pad_zone_number)
        canvas.drawRect(layout.numberLeft, top, layout.numberRight, bottom, fillPaint)

        val dx = layout.dividerX
        canvas.drawLine(dx, top + dp(8f), dx, bottom - dp(8f), dividerPaint)

        // 透かし文字はゾーンの高さに追従させる
        hintPaint.textSize = min(dp(34f), zoneH * 0.20f)

        val hintY = (top + bottom) / 2f - (hintPaint.descent() + hintPaint.ascent()) / 2f
        canvas.drawText(zoneHint(), (layout.alphaLeft + layout.alphaRight) / 2f, hintY, hintPaint)
        canvas.drawText("123", (layout.numberLeft + layout.numberRight) / 2f, hintY, hintPaint)

        drawStatusIndicator(canvas, layout.zoneRight, top)

        if (!fadingPath.isEmpty && strokeAlpha > 0f) {
            fadePaint.alpha = (strokeAlpha * 255f).toInt()
            canvas.drawPath(fadingPath, fadePaint)
        }
        if (!activePath.isEmpty) {
            canvas.drawPath(activePath, strokePaint)
        }
    }

    private fun zoneHint(): String = when (symbolMode) {
        SymbolMode.PUNCTUATION -> "•?!"
        SymbolMode.EXTENDED -> "€°×"
        SymbolMode.NORMAL -> when (modeLabel) {
            MODE_HIRAGANA -> "かな"
            MODE_KATAKANA -> "カナ"
            else -> "abc"
        }
    }

    /** 描画エリア右上の状態インジケータ（⇧ / • / ＼ / 文節番号）。 */
    private fun drawStatusIndicator(canvas: Canvas, rightEdge: Float, zoneTopY: Float) {
        if (statusLabel.isEmpty()) return
        labelPaint.textSize = dp(13f)
        val tw = labelPaint.measureText(statusLabel)
        val padH = dp(7f)
        val padV = dp(3f)
        val h = labelPaint.descent() - labelPaint.ascent() + padV * 2
        val right = rightEdge - dp(8f)
        val topY = zoneTopY + dp(5f)
        val chip = RectF(right - tw - padH * 2, topY, right, topY + h)
        fillPaint.color = context.getColor(R.color.pad_status)
        canvas.drawRoundRect(chip, dp(4f), dp(4f), fillPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            statusLabel,
            chip.centerX(),
            chip.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f,
            labelPaint,
        )
    }

    private fun buttonLabel(i: Int): String = when (i) {
        0 -> modeLabel
        1 -> LABEL_LEFT
        2 -> LABEL_RIGHT
        3 -> LABEL_SELECT
        else -> LABEL_HELP
    }

    private fun buttonTarget(i: Int): UiTarget = when (i) {
        0 -> UiTarget.BTN_MODE
        1 -> UiTarget.BTN_LEFT
        2 -> UiTarget.BTN_RIGHT
        3 -> UiTarget.BTN_SELECT
        else -> UiTarget.BTN_HELP
    }

    private fun drawButtonColumn(canvas: Canvas, h: Float) {
        fillPaint.color = context.getColor(R.color.pad_status)
        canvas.drawRect(layout.columnLeft, contentTop, layout.columnRight, h, fillPaint)

        for (i in 0 until BUTTON_COUNT) {
            val r = buttonRects[i]
            val target = buttonTarget(i)
            // ? ボタンはオーバーレイ表示中もアクティブ表示にする
            val active = pressed == target || (i == BUTTON_COUNT - 1 && overlayVisible)
            fillPaint.color = context.getColor(
                if (active) R.color.pad_divider else R.color.pad_zone_alpha,
            )
            canvas.drawRoundRect(r, dp(6f), dp(6f), fillPaint)

            val text = buttonLabel(i)
            // 64dp 幅に収まるよう、長いラベル（全選択）だけ少し小さくする
            buttonPaint.textSize = if (text.length >= 3) dp(14f) else dp(17f)
            val baseline = r.centerY() - (buttonPaint.descent() + buttonPaint.ascent()) / 2f
            canvas.drawText(text, r.centerX(), baseline, buttonPaint)
        }

        val edge = if (leftHanded) layout.columnLeft else layout.columnRight
        canvas.drawLine(edge, contentTop, edge, h, solidLinePaint)
    }

    private fun drawCandidateBar(canvas: Canvas, w: Float) {
        val top = candidateTop
        val bottom = candidateBottom
        fillPaint.color = context.getColor(R.color.pad_zone_number)
        canvas.drawRect(layout.barLeft, top, layout.barRight, bottom, fillPaint)

        layoutCandidates(w)

        val baseline = (top + bottom) / 2f -
            (candidateTextPaint.descent() + candidateTextPaint.ascent()) / 2f

        val left = layout.barLeft + barInset
        val right = layout.barRight - barInset - chipWidth()
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (i in candidateRects.indices) {
            val r = candidateRects[i]
            if (r.right < left || r.left > right) continue
            if (i == candidateIndex) {
                fillPaint.color = context.getColor(R.color.pad_zone_alpha)
                canvas.drawRoundRect(
                    RectF(r.left + dp(2f), top + dp(4f), r.right - dp(2f), bottom - dp(4f)),
                    dp(5f), dp(5f), fillPaint,
                )
            }
            canvas.drawText(candidates[i], r.centerX(), baseline, candidateTextPaint)
        }
        canvas.restore()

        if (segmentNav) {
            fillPaint.color = context.getColor(
                if (pressed == UiTarget.SEG_PREV) R.color.pad_divider else R.color.pad_status,
            )
            canvas.drawRect(layout.barLeft, top, layout.barLeft + segArrowWidth, bottom, fillPaint)
            fillPaint.color = context.getColor(
                if (pressed == UiTarget.SEG_NEXT) R.color.pad_divider else R.color.pad_status,
            )
            canvas.drawRect(layout.barRight - segArrowWidth, top, layout.barRight, bottom, fillPaint)
            canvas.drawText(LABEL_SEG_PREV, layout.barLeft + segArrowWidth / 2f, baseline, candidateTextPaint)
            canvas.drawText(LABEL_SEG_NEXT, layout.barRight - segArrowWidth / 2f, baseline, candidateTextPaint)
        }

        drawOnDeviceChip(canvas, top, bottom)

        canvas.drawLine(layout.barLeft, bottom, layout.barRight, bottom, solidLinePaint)
    }

    /** チップが占める幅（出していないときは 0）。 */
    private fun chipWidth(): Float {
        if (!onDeviceChip || segmentNav) return 0f
        return chipTextPaint.measureText(LABEL_ON_DEVICE) + dp(14f)
    }

    private fun drawOnDeviceChip(canvas: Canvas, top: Float, bottom: Float) {
        val cw = chipWidth()
        if (cw <= 0f) return
        val right = layout.barRight - barInset - dp(2f)
        val rect = RectF(right - cw + dp(4f), top + dp(7f), right, bottom - dp(7f))
        fillPaint.color = context.getColor(R.color.pad_status)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), fillPaint)
        val baseline = rect.centerY() -
            (chipTextPaint.descent() + chipTextPaint.ascent()) / 2f
        canvas.drawText(LABEL_ON_DEVICE, rect.centerX(), baseline, chipTextPaint)
    }

    private fun layoutCandidates(w: Float) {
        candidateRects.clear()
        val left = layout.barLeft + barInset
        var x = left + dp(4f) - candidateScroll
        val top = candidateTop
        val bottom = candidateBottom
        for (c in candidates) {
            val cw = max(dp(48f), candidateTextPaint.measureText(c) + dp(24f))
            candidateRects.add(RectF(x, top, x + cw, bottom))
            x += cw
        }
        val contentEnd = x + candidateScroll
        val maxScroll =
            max(0f, contentEnd - (layout.barRight - barInset - chipWidth()) + dp(4f))
        if (candidateScroll > maxScroll) {
            val delta = candidateScroll - maxScroll
            candidateScroll = maxScroll
            for (r in candidateRects) r.offset(delta, 0f)
        }
    }

    private fun ensureCandidateVisible() {
        if (candidates.isEmpty() || width == 0) return
        layoutCandidates(width.toFloat())
        if (candidateIndex !in candidateRects.indices) return
        val r = candidateRects[candidateIndex]
        val leftEdge = layout.barLeft + barInset + dp(4f)
        val rightEdge = layout.barRight - barInset - chipWidth() - dp(4f)
        if (r.left < leftEdge) candidateScroll = max(0f, candidateScroll - (leftEdge - r.left))
        else if (r.right > rightEdge) candidateScroll += (r.right - rightEdge)
    }

    // ------------------------------------------------------ 見本オーバーレイ

    private class SampleCell(val label: String, val points: List<Pt>)
    private class SampleSection(val title: String, val cells: List<SampleCell>)

    private var overlayVisible = false
    private var overlayScroll = 0f
    private var overlayContentHeight = 0f
    private var sampleSections: List<SampleSection>? = null

    fun toggleOverlay() {
        overlayVisible = !overlayVisible
        overlayScroll = 0f
        if (overlayVisible) {
            clearStroke()
            if (sampleSections == null) sampleSections = buildSamples()
        }
        invalidate()
    }

    fun hideOverlay() {
        if (!overlayVisible) return
        overlayVisible = false
        invalidate()
    }

    val isOverlayVisible: Boolean get() = overlayVisible

    /** コマンド定数を読める名前に。 */
    private fun sampleLabel(symbol: String): String = when (symbol) {
        StrokeTemplates.SPACE -> "space"
        StrokeTemplates.BACKSPACE -> "b.sp"
        StrokeTemplates.RETURN -> "enter"
        StrokeTemplates.SHIFT -> "shift/A"
        StrokeTemplates.EXT_SHIFT -> "ext ＼"
        StrokeTemplates.EXT_SHIFT_ALT -> "カナ/ext"
        StrokeTemplates.TAB -> "tab"
        else -> symbol
    }

    /** テンプレートそのものから見本セルを作る（字形と見本が常に一致する）。 */
    private fun buildSamples(): List<SampleSection> {
        fun section(title: String, tpls: List<StrokeTemplate>): SampleSection {
            val seen = LinkedHashMap<String, List<Pt>>()
            for (t in tpls) if (!seen.containsKey(t.symbol)) seen[t.symbol] = t.points
            return SampleSection(
                title,
                // K/X などは見本専用の（ループを大きく取った）字形で描く
                seen.map { SampleCell(sampleLabel(it.key), SampleStrokes.forSymbol(it.key, it.value)) },
            )
        }
        return listOf(
            section("英字ゾーン", StrokeTemplates.letters),
            section("共通コマンド", StrokeTemplates.commands),
            section("数字ゾーン", StrokeTemplates.digits),
            section("Punctuation（タップ後）", StrokeTemplates.punctuation),
            section("Extended（＼ の後）", StrokeTemplates.extended),
        )
    }

    private fun drawOverlay(canvas: Canvas, w: Float, h: Float) {
        val left = layout.zoneLeft
        val top = contentTop
        fillPaint.color = OVERLAY_BG
        canvas.drawRect(left, top, layout.zoneRight, h, fillPaint)

        // ビルド印は右下の隅に固定で出す（スクロールしても、見本の組み立てに
        // 失敗しても常に見える）。IME だけ起動している状況でも
        // 「端末に入っているのがどのビルドか」を確認できるようにするためのもの。
        canvas.drawText(buildStamp, layout.zoneRight - dp(6f), h - dp(5f), buildStampPaint)

        val sections = sampleSections ?: return
        // K/X のループが潰れないだけのセルサイズを確保する
        val cellW = dp(62f)
        val cellH = dp(66f)
        val padL = left + dp(6f)
        val cols = max(1, ((layout.zoneRight - padL - dp(6f)) / cellW).toInt())

        canvas.save()
        canvas.clipRect(left, top, layout.zoneRight, h)
        canvas.translate(0f, -overlayScroll)

        var y = top + dp(4f)
        y += dp(13f)
        canvas.drawText(HELP_HINT, padL, y, sampleTitlePaint)
        y += dp(4f)
        for (sec in sections) {
            y += dp(13f)
            canvas.drawText(sec.title, padL, y, sampleTitlePaint)
            y += dp(5f)
            var i = 0
            while (i < sec.cells.size) {
                val row = min(cols, sec.cells.size - i)
                for (c in 0 until row) {
                    drawSampleCell(sec.cells[i + c], canvas, padL + c * cellW, y, cellW, cellH)
                }
                i += row
                y += cellH
            }
            y += dp(6f)
        }
        overlayContentHeight = y - top
        canvas.restore()
    }

    private fun drawSampleCell(cell: SampleCell, canvas: Canvas, x: Float, y: Float, cw: Float, ch: Float) {
        val glyphH = ch - dp(14f)
        val pad = dp(7f)
        val side = min(cw - pad * 2, glyphH - pad)
        StrokeArt.draw(
            canvas, cell.points,
            x + (cw - side) / 2f, y + pad / 2f, side,
            samplePaint, sampleDotPaint, samplePath, dp(3f), dp(6f),
        )
        canvas.drawText(cell.label, x + cw / 2f, y + ch - dp(2f), sampleLabelPaint)
        // 個人テンプレートを持つ文字には印を付ける
        if (cell.label in learnedSymbols) {
            canvas.drawCircle(x + cw - dp(8f), y + dp(7f), dp(2.6f), sampleDotPaint)
        }
    }

    // ----------------------------------------------------------------- touch

    private fun setPressedTarget(target: UiTarget) {
        if (pressed != target) {
            pressed = target
            invalidate()
        }
    }

    private fun cancelLongPresses() {
        removeCallbacks(helpLongPressRunnable)
        removeCallbacks(selectLongPressRunnable)
    }

    private fun stopRepeat() {
        removeCallbacks(repeatRunnable)
        cancelLongPresses()
    }

    private fun buttonAt(x: Float, y: Float): Int {
        for (i in 0 until BUTTON_COUNT) if (buttonRects[i].contains(x, y)) return i
        return -1
    }

    /**
     * 手書き中の点を 1 つ足す。[moveTolerance] 未満の移動は捨てる。
     * @return 実際に足したか
     */
    private fun addStrokePoint(x: Float, y: Float): Boolean {
        val last = points.lastOrNull() ?: return false
        if (hypot(x - last.x, y - last.y) < moveTolerance) return false
        points += Pt(x, y)
        activePath.lineTo(x, y)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            // 2 本目以降の指。手書き中なら「そこでペンを上げた」ものとして即座に確定する。
            // 速書きで前のストロークを離す前に次を触ってしまっても、
            // 1 本目のストロークが 2 本目の座標で汚れたり丸ごと消えたりしない。
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (tracking) {
                    finishStroke()
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                downTime = SystemClock.uptimeMillis()
                uiTarget = UiTarget.NONE

                // 候補バーは入力窓の外・上側なので最初に判定する
                if (candidateBarVisible && y < candidateBottom &&
                    x >= layout.barLeft && x <= layout.barRight
                ) {
                    uiTarget = when {
                        segmentNav && x < layout.barLeft + segArrowWidth -> UiTarget.SEG_PREV
                        segmentNav && x > layout.barRight - segArrowWidth -> UiTarget.SEG_NEXT
                        else -> UiTarget.CANDIDATES
                    }
                    setPressedTarget(uiTarget)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // 縦ボタン列（左利き時は右端）
                if (layout.isInColumn(x)) {
                    val idx = buttonAt(x, y)
                    if (idx < 0) return false
                    uiTarget = buttonTarget(idx)
                    setPressedTarget(uiTarget)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (uiTarget == UiTarget.BTN_LEFT || uiTarget == UiTarget.BTN_RIGHT) {
                        listener?.onCursorMove(uiTarget == UiTarget.BTN_RIGHT)
                        postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                    }
                    if (uiTarget == UiTarget.BTN_HELP) {
                        helpLongPressFired = false
                        postDelayed(helpLongPressRunnable, LONG_PRESS_MS)
                    }
                    if (uiTarget == UiTarget.BTN_SELECT) {
                        selectLongPressFired = false
                        postDelayed(selectLongPressRunnable, LONG_PRESS_MS)
                    }
                    return true
                }

                // オーバーレイ表示中は手書きを受け付けず、スクロールのみ
                if (overlayVisible) {
                    uiTarget = UiTarget.OVERLAY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // 展開時のパネル外（非アクティブ領域）では手書きを受け付けない
                if (!layout.isInPanel(x, y)) return false

                // 本方式は完全な一筆書きで、離した時点で認識まで済んでいる。
                // したがって前のストロークのフェードが残っていても待つ必要はない。
                // 抑制時間は一切設けず、消えかけの軌跡だけ捨てて即座に書き始める。
                parent?.requestDisallowInterceptTouchEvent(true)
                // Android は既定でタッチイベントを VSync に合わせてまとめて配送する。
                // 速いストロークだと 1 本ぶんが数イベントに畳まれ、点数が一桁まで落ちる。
                // ハーネス実測では点数が 8 を切ると認識率が崩れる（10点97.6% → 5点72.9%）ので、
                // 手書きの間だけタッチセンサのレートで直接配送するよう要求する。
                requestUnbufferedDispatch(event)
                tracking = true
                zone = layout.zoneFor(x)
                points.clear()
                points += Pt(x, y)
                activePath.reset()
                activePath.moveTo(x, y)
                strokeFade.cancel()
                strokeAlpha = 0f
                fadingPath.reset()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (uiTarget) {
                    UiTarget.OVERLAY -> {
                        val maxScroll = max(
                            0f,
                            overlayContentHeight - (height - contentTop) + dp(8f),
                        )
                        overlayScroll = (overlayScroll - (y - downY)).coerceIn(0f, maxScroll)
                        downY = y
                        invalidate()
                        return true
                    }
                    UiTarget.CANDIDATES -> {
                        candidateScroll = max(0f, candidateScroll - (x - downX))
                        downX = x
                        invalidate()
                        return true
                    }
                    UiTarget.NONE -> {
                        if (!tracking) return true
                        // 速く書くと 1 フレーム分のサンプルが 1 つの MOVE にまとめられる。
                        // 履歴サンプルを取り込まないと軌跡が粗くなり、認識率が落ちる
                        // （= 速書きで「取りこぼした」ように見える）。必ず先に消化する。
                        var added = false
                        val history = event.historySize
                        for (h in 0 until history) {
                            if (addStrokePoint(event.getHistoricalX(h), event.getHistoricalY(h))) {
                                added = true
                            }
                        }
                        if (addStrokePoint(x, y)) added = true
                        if (added) invalidate()
                        return true
                    }
                    else -> return true
                }
            }

            MotionEvent.ACTION_UP -> {
                stopRepeat()
                when (uiTarget) {
                    UiTarget.BTN_MODE -> if (buttonRects[0].contains(x, y)) {
                        hideOverlay()
                        listener?.onModeToggle()
                        performClick()
                    }
                    // 押下時に 1 回発火済みなので UP では何もしない
                    UiTarget.BTN_LEFT, UiTarget.BTN_RIGHT -> performClick()
                    UiTarget.BTN_SELECT -> {
                        removeCallbacks(selectLongPressRunnable)
                        // 長押しで直前確定を選択済みなら、離しても全選択はしない
                        if (!selectLongPressFired && buttonRects[3].contains(x, y)) {
                            listener?.onSelectAll()
                            performClick()
                        }
                        selectLongPressFired = false
                    }
                    UiTarget.BTN_HELP -> {
                        removeCallbacks(helpLongPressRunnable)
                        // 長押しで設定を開いた場合は見本の開閉をしない
                        if (!helpLongPressFired && buttonRects[4].contains(x, y)) {
                            toggleOverlay()
                            performClick()
                        }
                        helpLongPressFired = false
                    }
                    UiTarget.SEG_PREV -> if (x < layout.barLeft + segArrowWidth) {
                        listener?.onSegmentStep(false); performClick()
                    }
                    UiTarget.SEG_NEXT -> if (x > layout.barRight - segArrowWidth) {
                        listener?.onSegmentStep(true); performClick()
                    }
                    UiTarget.CANDIDATES -> {
                        if (abs(x - downX) < scrollSlop && abs(y - downY) < scrollSlop) {
                            val idx = candidateRects.indexOfFirst { it.contains(x, y) }
                            if (idx >= 0) {
                                listener?.onCandidateSelected(idx)
                                performClick()
                            }
                        }
                    }
                    UiTarget.OVERLAY -> Unit
                    UiTarget.NONE -> {
                        if (tracking) {
                            // UP イベントにも履歴サンプルが付くことがある。
                            // 終端の形が変わるので、認識前に必ず取り込む。
                            for (h in 0 until event.historySize) {
                                addStrokePoint(event.getHistoricalX(h), event.getHistoricalY(h))
                            }
                            addStrokePoint(x, y)
                            finishStroke()
                            performClick()
                        }
                    }
                }
                uiTarget = UiTarget.NONE
                setPressedTarget(UiTarget.NONE)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                uiTarget = UiTarget.NONE
                setPressedTarget(UiTarget.NONE)
                clearStroke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun finishStroke() {
        tracking = false
        // デバッグログ用に、点を捨てる前に控えておく
        val pointCount = points.size

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val extent = if (points.isEmpty()) 0f else max(maxX - minX, maxY - minY)
        val elapsed = SystemClock.uptimeMillis() - downTime

        // 文脈バイアスは英字ゾーンの通常モードだけ。
        // 数字ゾーン・記号シフト中はローマ字文脈と無関係なのでかけない。
        val expected =
            if (zone == Zone.ALPHA && symbolMode == SymbolMode.NORMAL) {
                listener?.expectedSymbols().orEmpty()
            } else {
                emptySet()
            }

        val symbol: String = if (extent < tapMaxSize && elapsed < TAP_MAX_MS) {
            // タップ = Punctuation Shift（記号モード中は「.」「•」）
            if (debugStrokes) logStroke(null, points, expected, pointCount, elapsed, extent, tap = true)
            StrokeTemplates.TAP
        } else {
            val r = recognizerFor(zone, symbolMode)
                .recognize(points, minStrokeSize, expected, StrokeRecognizer.CONTEXT_BONUS)
            if (debugStrokes) logStroke(r, points, expected, pointCount, elapsed, extent)
            r?.symbol ?: UNRECOGNIZED
        }

        val raw = ArrayList<Pt>(points)
        points.clear()
        fadingPath.set(activePath)
        activePath.reset()
        strokeAlpha = 1f
        strokeFade.cancel()
        strokeFade.start()

        listener?.onSymbol(symbol, raw, zone)
        invalidate()
    }

    /**
     * 速書きの実機ログ。既定では出さない（設定の隠しフラグで有効化する）。
     * 点数・所要時間・大きさ・スコア・棄却理由を 1 行にまとめる。
     * 実機で速く書いてもらい `adb logcat -s UniStroke` で拾う想定。
     */
    private fun logStroke(
        r: Recognition?,
        points: List<Pt>,
        expected: Set<String>,
        pointCount: Int,
        strokeMs: Long,
        extent: Float,
        tap: Boolean = false,
    ) {
        val reason = when {
            tap -> "tap"
            r != null -> "ok"
            pointCount < StrokeRecognizer.MIN_POINTS -> "too_few_points"
            extent < minStrokeSize -> "too_small"
            else -> "below_threshold"
        }
        // 棄却時は recognize() がスコアを返さないので、ここで採点し直して実際の点数を出す。
        // これをやらないと「なぜ落ちたのか」がログから一切分からない。
        val top = recognizerFor(zone, symbolMode)
            .topCandidates(points, expected, StrokeRecognizer.CONTEXT_BONUS, TOP_N)
        val bestScore = r?.score ?: top.firstOrNull()?.score ?: Float.NaN
        val margin = if (top.size >= 2) top[0].score - top[1].score else Float.NaN
        val topText = top.joinToString(", ", "[", "]") { "%s:%.3f".format(it.symbol, it.score) }

        Log.d(
            LOG_TAG,
            ("stroke pts=%d ms=%d extent=%.0fpx (%.1fdp) zone=%s mode=%s " +
                "sym=%s score=%s margin=%s top%d=%s why=%s%s%s").format(
                pointCount, strokeMs, extent, extent / density, zone, symbolMode,
                r?.symbol ?: "-",
                if (bestScore.isNaN()) "-" else "%.3f".format(bestScore),
                if (margin.isNaN()) "-" else "%.3f".format(margin),
                TOP_N, topText, reason,
                if (pointCount < StrokeRecognizer.SPARSE_POINTS) " [sparse:補間済み]" else "",
                if (expected.isEmpty()) "" else " expected=%d".format(expected.size),
            ),
        )
    }

    private fun clearStroke() {
        tracking = false
        points.clear()
        activePath.reset()
        fadingPath.reset()
        strokeFade.cancel()
        strokeAlpha = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        helpLongPressFired = false
        selectLongPressFired = false
        strokeFade.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val MODE_LOWER = "abc"
        const val MODE_SHIFT = "Abc"
        const val MODE_CAPS = "ABC"
        const val MODE_HIRAGANA = "かな"
        const val MODE_KATAKANA = "カナ"

        /** 認識に失敗したことを IME に伝える擬似シンボル。 */
        const val UNRECOGNIZED = "#none"

        private const val BUTTON_COUNT = 5
        private const val LABEL_LEFT = "◀"
        private const val LABEL_RIGHT = "▶"
        private const val LABEL_SELECT = "全選択"
        private const val LABEL_HELP = "?"
        private const val LABEL_SEG_PREV = "《"
        private const val LABEL_SEG_NEXT = "》"

        /** 候補が端末内辞書だけで作られたことを示すチップの文字。 */
        private const val LABEL_ON_DEVICE = "端末内"
        private const val HELP_HINT = "ジェスチャー見本 — [?] で閉じる／上下スクロール"

        private val OVERLAY_BG = Color.argb(246, 22, 28, 24)

        /** 展開時にパネルへ与える最大幅（1 画面のスマホと同じ書き心地にする）。 */
        private const val PANEL_MAX_WIDTH_DP = 420f

        /**
         * 入力ビュー全体の高さ。上部バーは持たない。
         * 書きやすさ優先で広めに取っている。
         * 縦ボタン列が全高を 5 等分（1 ボタン約 47dp = 押しやすい大きさ）。
         * 描画ゾーンは候補バー無しで約 227dp、候補バー表示中でも約 185dp。
         * 最小ストローク/タップ判定は dp 基準の絶対値（14dp / 10dp）なので高さの影響を受けない。
         */
        private const val HEIGHT_DP = 235f

        /**
         * 認識済みストロークが消えるまでの時間。
         * 次のストロークはこのフェード中でも待たされずに開始できる（抑制時間は無い）。
         */
        /** 速書きデバッグログのタグ（`adb logcat -s UniStroke`）。 */
        private const val LOG_TAG = "UniStroke"

        /** デバッグログに出す候補の件数。 */
        private const val TOP_N = 3

        private const val STROKE_FADE_MS = 220L

        /** これより短い接触はタップ扱い。 */
        private const val TAP_MAX_MS = 600L

        /** [?] の長押し判定。 */
        private const val LONG_PRESS_MS = 500L

        /** カーソルボタンの長押しリピート。 */
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
