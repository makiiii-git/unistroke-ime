package com.unistroke.ime

import kotlin.math.min

/**
 * 入力パネルの配置計算。
 *
 * - 1 画面（通常のスマホ）: パネルは IME 全幅を使う
 * - 展開（Fold の内側画面など）: パネルは 1 画面時と同じ幅のまま、
 *   利き手側へ寄せる。残りは入力を受け付けない非アクティブ領域。
 *   ユーザーがドラッグで動かした場合は [update] の floatLeft で位置を指定する。
 * - 左利き: 縦ボタン列と英字/数字ゾーンの左右を入れ替える
 *
 * 縦方向は「パネル上端 = 0」の座標系で計算する。展開時にパネルを上下へ
 * 動かすぶんのオフセットは [UniStrokeView] 側が canvas の平行移動と
 * タッチ座標の補正で吸収するので、ここでは扱わない。
 *
 * Android 依存を持たない純粋な計算なので、そのままテストできる。
 */
class PanelLayout {

    // ---- 入力 ----
    var viewWidth = 0f
        private set

    /** パネル本体の高さ（候補バー込み）。ビュー自体の高さとは別物。 */
    var panelHeight = 0f
        private set

    /** 候補バーの高さ（出ていないときは 0 として扱う）。 */
    var contentTop = 0f
        private set

    // ---- 出力 ----
    var panelLeft = 0f
        private set
    var panelRight = 0f
        private set
    var columnLeft = 0f
        private set
    var columnRight = 0f
        private set

    /** 手書きゾーン全体（ボタン列を除いた部分）。 */
    var zoneLeft = 0f
        private set
    var zoneRight = 0f
        private set

    /** 英字ゾーンと数字ゾーンの境界。 */
    var dividerX = 0f
        private set

    var expanded = false
        private set
    var leftHanded = false
        private set

    /**
     * @param floatLeft 展開時のパネル左端。null なら利き手側へ寄せた既定位置。
     *                  ドラッグ中・移動後はビューが実際の位置を渡す。
     */
    fun update(
        viewWidth: Float,
        panelHeight: Float,
        candidateHeight: Float,
        candidateVisible: Boolean,
        columnWidth: Float,
        panelMaxWidth: Float,
        expanded: Boolean,
        leftHanded: Boolean,
        floatLeft: Float? = null,
    ) {
        this.viewWidth = viewWidth
        this.panelHeight = panelHeight
        this.expanded = expanded
        this.leftHanded = leftHanded
        contentTop = if (candidateVisible) candidateHeight else 0f

        // 展開時だけパネル幅を 1 画面ぶんに制限し、利き手側へ寄せる
        val panelW = if (expanded) min(viewWidth, panelMaxWidth) else viewWidth
        panelLeft = when {
            !expanded -> 0f
            floatLeft != null -> floatLeft.coerceIn(0f, viewWidth - panelW)
            leftHanded -> 0f
            else -> viewWidth - panelW
        }
        panelRight = panelLeft + panelW

        if (leftHanded) {
            columnRight = panelRight
            columnLeft = panelRight - columnWidth
            zoneLeft = panelLeft
            zoneRight = columnLeft
        } else {
            columnLeft = panelLeft
            columnRight = panelLeft + columnWidth
            zoneLeft = columnRight
            zoneRight = panelRight
        }

        val w = zoneRight - zoneLeft
        // 右利き: 左 2/3 が英字。左利き: 左 1/3 が数字（= 完全なミラー）
        dividerX = if (leftHanded) zoneLeft + w / 3f else zoneLeft + w * 2f / 3f
    }

    /** その X 座標がどちらのゾーンか。ミラー時は左右が反転する。 */
    fun zoneFor(x: Float): Zone =
        if (leftHanded) {
            if (x < dividerX) Zone.NUMBER else Zone.ALPHA
        } else {
            if (x >= dividerX) Zone.NUMBER else Zone.ALPHA
        }

    /** 英字ゾーンの範囲。 */
    val alphaLeft: Float get() = if (leftHanded) dividerX else zoneLeft
    val alphaRight: Float get() = if (leftHanded) zoneRight else dividerX

    /** 数字ゾーンの範囲。 */
    val numberLeft: Float get() = if (leftHanded) zoneLeft else dividerX
    val numberRight: Float get() = if (leftHanded) dividerX else zoneRight

    /** 手書き入力を受け付ける領域か（非アクティブ領域の誤タッチを弾く）。 */
    fun isInPanel(x: Float, y: Float): Boolean =
        y >= contentTop && y <= panelHeight && x >= panelLeft && x <= panelRight

    /** 展開時にできる非アクティブ領域があるか。 */
    val hasInactiveArea: Boolean get() = panelLeft > 0f || panelRight < viewWidth

    /**
     * 候補バーの左右。展開時はパネル幅に合わせる
     * （全幅の不透明な帯を出すと、せっかく透過させた背後のアプリが隠れてしまうため）。
     */
    val barLeft: Float get() = if (expanded) panelLeft else 0f
    val barRight: Float get() = if (expanded) panelRight else viewWidth

    // ---- タッチを通す領域（InputMethodService.onComputeInsets 用）----

    /** パネル本体の矩形。縦ボタン列と手書きゾーンを含む。 */
    val touchPanelLeft: Float get() = panelLeft
    val touchPanelTop: Float get() = contentTop
    val touchPanelRight: Float get() = panelRight
    val touchPanelBottom: Float get() = panelHeight

    /** 候補バーの矩形（表示中のみ有効）。 */
    val touchBarLeft: Float get() = barLeft
    val touchBarTop: Float get() = 0f
    val touchBarRight: Float get() = barRight
    val touchBarBottom: Float get() = contentTop

    /** 候補バーが高さを持っているか（= タッチ領域に加えるべきか）。 */
    val hasBar: Boolean get() = contentTop > 0f

    /** 縦ボタン列の [index] 番目（全 [count] 個）の上端 / 下端。 */
    fun buttonTop(index: Int, count: Int): Float =
        contentTop + (panelHeight - contentTop) * index / count

    fun buttonBottom(index: Int, count: Int): Float =
        contentTop + (panelHeight - contentTop) * (index + 1) / count

    /** X 座標がボタン列の上か。 */
    fun isInColumn(x: Float): Boolean = x >= columnLeft && x < columnRight
}
