package com.unistroke.ime

/**
 * 訂正パターンの検出。
 *
 * 「ストローク S が文字 X として入力された -> **直後に** その X を消した ->
 *   次のストロークが文字 Y (≠X) として入力され、消されずに残った」
 * というシーケンスを訂正イベントとみなし、S を Y の個人テンプレート候補として記録する。
 *
 * Android 依存を持たない純粋な状態機械なので、シーケンステストで直接検証できる。
 */
class StrokeLearner(private val store: PersonalTemplateStore) {

    /** 学習に使う 1 ストロークぶんの入力。 */
    class Input(val symbol: String, val stroke: List<Pt>, val set: String)

    private sealed class State {
        object Idle : State()

        /** 直前に文字が入力された（まだ消されていない）。 */
        class Typed(val input: Input, val at: Long) : State()

        /** 直前の文字が消された。次の文字が来たら訂正とみなす。 */
        class Armed(val stroke: List<Pt>, val removed: String, val set: String, val at: Long) : State()

        /** 訂正候補を掴んだが、その文字が残るかまだ分からない。 */
        class Provisional(val stroke: List<Pt>, val symbol: String, val set: String, val at: Long) : State()
    }

    private var state: State = State.Idle

    /** 衝突ガード用の認識器を差し込む（セット名 -> 認識器）。 */
    var guardProvider: ((String) -> StrokeRecognizer?)? = null

    /** 個人テンプレートが増えたときの通知。 */
    var onPromoted: ((symbol: String) -> Unit)? = null

    /** 文字が 1 つ入力された。 */
    fun onCharacter(input: Input, now: Long) {
        // ここまで来たということは、直前の暫定候補の文字は消されずに残った
        confirmProvisional(now)

        val s = state
        if (s is State.Armed &&
            input.symbol != s.removed &&
            input.set == s.set &&
            now - s.at <= CORRECTION_TIMEOUT_MS
        ) {
            // 訂正成立。元ストローク S を Y の候補として保留する。
            state = State.Provisional(s.stroke, input.symbol, s.set, now)
            return
        }
        state = State.Typed(input, now)
    }

    /**
     * 直前に入力した文字ちょうど 1 つを取り消すバックスペースが起きた。
     *
     * かなモードでは「ローマ字バッファから 1 文字消えた」場合のみ該当する。
     * かな 1 文字ぶん（= 複数のローマ字）が消えるケースは、どのストロークの訂正か
     * 特定できないので呼び出し側で [onOther] を使うこと。
     */
    fun onUndoLastCharacter(now: Long) {
        val s = state
        state = when {
            // 直前に確定した訂正結果が消された = 学習してはいけない
            s is State.Provisional -> State.Idle
            s is State.Typed && now - s.at <= CORRECTION_TIMEOUT_MS ->
                State.Armed(s.input.stroke, s.input.symbol, s.input.set, now)
            else -> State.Idle
        }
    }

    /** 文字入力・取り消し以外のことが起きた（スペース、確定、モード切替など）。 */
    fun onOther(now: Long) {
        confirmProvisional(now)
        state = State.Idle
    }

    /** 暫定候補を確定してストアへ渡す。 */
    private fun confirmProvisional(now: Long) {
        val s = state as? State.Provisional ?: return
        state = State.Idle
        val normalized = StrokeRecognizer.normalizeForTemplate(s.stroke) ?: return
        val guard = guardProvider?.invoke(s.set)
        if (store.addCandidate(s.set, s.symbol, normalized, now, guard)) {
            onPromoted?.invoke(s.symbol)
        }
    }

    companion object {
        /** これ以上間が空いた訂正は別の操作とみなす。 */
        const val CORRECTION_TIMEOUT_MS = 10_000L
    }
}
