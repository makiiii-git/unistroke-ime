package com.unistroke.ime

/**
 * 書き方トレーニング（方式1: 明示的トレーニング）の進行管理。
 *
 * Android 依存を持たない純粋な状態機械なので、遷移テストを直接書ける。
 * 各文字を [REQUIRED_SUCCESSES] 回正しく書けたら次の文字へ進む。
 */
class TrainingSession(val chars: List<String>) {

    /** 1 回の試行に対する結果。 */
    class Step(
        /** 目的の文字として認識されたか。 */
        val correct: Boolean,
        /** 個人テンプレートとして登録したか。 */
        val learned: Boolean,
        /** 衝突ガードで登録を見送ったか（= 見本に沿って書き直してほしい）。 */
        val rejected: Boolean,
        /** 現在の文字の成功回数。 */
        val successes: Int,
        /** この試行で現在の文字が完了したか。 */
        val charCompleted: Boolean,
        /** 全文字が終わったか。 */
        val finished: Boolean,
    )

    var index: Int = 0
        private set

    var successes: Int = 0
        private set

    /** トレーニングで個人テンプレートを登録できた文字。 */
    val learnedChars = LinkedHashSet<String>()

    val finished: Boolean get() = index >= chars.size

    val current: String? get() = chars.getOrNull(index)

    /** 進捗表示用（1 始まり）。 */
    fun position(): Int = (index + 1).coerceAtMost(chars.size)

    /**
     * 1 回の試行を処理する。
     *
     * @param recognized 認識結果（認識できなければ null）
     * @param score      認識スコア
     * @param save       個人テンプレートとして保存を試みる処理。成功なら true
     *                   （衝突ガードで弾かれたら false）を返すこと。
     */
    fun onAttempt(
        recognized: String?,
        score: Float,
        save: () -> Boolean,
    ): Step {
        val target = current ?: return Step(false, false, false, successes, false, true)

        val correct = recognized == target
        // 正しく出ていてもスコアが低いなら、その書き方を覚えておく価値がある
        val weak = correct && score < GOOD_SCORE
        var learned = false
        var rejected = false

        if (!correct || weak) {
            if (save()) {
                learned = true
                learnedChars.add(target)
            } else {
                rejected = true
            }
        }

        if (correct) successes++

        val charCompleted = successes >= REQUIRED_SUCCESSES
        if (charCompleted) {
            index++
            successes = 0
        }
        return Step(correct, learned, rejected, successes, charCompleted, finished)
    }

    /** 今の文字を飛ばす。 */
    fun skipCurrent() {
        if (finished) return
        index++
        successes = 0
    }

    /** チュートリアル全体を終了する。 */
    fun skipAll() {
        index = chars.size
        successes = 0
    }

    companion object {
        /** 1 文字あたり何回成功したら次へ進むか。 */
        const val REQUIRED_SUCCESSES = 2

        /** これ未満のスコアなら「認識はしたが不安定」とみなして書き方を覚える。 */
        const val GOOD_SCORE = 0.90f

        /**
         * 練習する文字。
         *
         * 必須 4 文字（A/K/X/Y）に加え、
         * 「元のリファレンスの字形が自然な手書きと乖離していて、実測で事故が多かった」
         * 5 文字を足した計 9 文字。
         * B は雑に書くと D へ流れやすく（ハーネス実測 13.5%）、
         * かつ左下から書き始める書き順が直感に反するので練習の価値が高い。
         */
        // 字形の系統が近いものを隣に並べる。
        // B / P は「左下から縦線を上へ -> 膨らみ」の同系統なので続けて練習する。
        val DEFAULT_CHARS = listOf("a", "k", "x", "y", "v", "e", "g", "p", "b")
    }
}
