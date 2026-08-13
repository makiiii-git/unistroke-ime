package com.unistroke.ime

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * 書き方トレーニング（方式1: 明示的トレーニング）。
 *
 * 見本を見ながら練習パッドに書き、認識結果を即座に返す。
 * うまく認識されなかったストロークは、衝突ガードを通れば
 * その場で個人テンプレートとして登録する（方式2 と同じ [PersonalTemplateStore]）。
 */
class TrainingActivity : Activity() {

    private lateinit var store: PersonalTemplateStore
    private lateinit var session: TrainingSession
    private lateinit var guard: StrokeRecognizer

    private lateinit var sampleView: StrokeSampleView
    private lateinit var padView: PracticePadView
    private lateinit var titleText: TextView
    private lateinit var instructionText: TextView
    private lateinit var progressText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var skipButton: Button
    private lateinit var finishButton: Button

    /** 文字 -> 見本に使うテンプレート（最初のバリアント）。 */
    private val samples: Map<String, List<Pt>> by lazy {
        val m = LinkedHashMap<String, List<Pt>>()
        for (t in StrokeTemplates.letters) if (!m.containsKey(t.symbol)) m[t.symbol] = t.points
        m
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_training)

        // IME と同じ実体を使う。ここで登録した字形はそのまま IME 側の認識に効く。
        store = PersonalTemplateStore.get(this)
        guard = StrokeRecognizer(StrokeTemplates.templatesFor(Zone.ALPHA, SymbolMode.NORMAL))
        session = TrainingSession(TrainingSession.DEFAULT_CHARS)

        sampleView = findViewById(R.id.training_sample)
        padView = findViewById(R.id.training_pad)
        titleText = findViewById(R.id.training_title)
        instructionText = findViewById(R.id.training_instruction)
        progressText = findViewById(R.id.training_progress)
        feedbackText = findViewById(R.id.training_feedback)
        skipButton = findViewById(R.id.training_skip)
        finishButton = findViewById(R.id.training_finish)

        padView.personalTemplates = { store.templatesFor(PersonalTemplateStore.SET_ALPHA) }
        padView.listener = PracticePadView.Listener { symbol, score, points ->
            onAttempt(symbol, score, points)
        }

        skipButton.setOnClickListener {
            session.skipCurrent()
            showCurrent()
        }
        finishButton.setOnClickListener {
            if (session.finished) finish() else { session.skipAll(); showCurrent() }
        }

        showCurrent()
    }

    private fun onAttempt(symbol: String?, score: Float, points: List<Pt>) {
        val target = session.current ?: return

        val step = session.onAttempt(symbol, score) {
            val normalized = StrokeRecognizer.normalizeForTemplate(points)
            if (normalized == null) false
            else store.addTrainingTemplate(
                PersonalTemplateStore.SET_ALPHA, target, normalized,
                System.currentTimeMillis(), guard,
            )
        }
        // テンプレートが増えたら認識器を作り直す
        if (step.learned) padView.refresh()

        // 何が起きたのかを具体的に、成功か要リトライかは色でも分かるように出す
        val required = TrainingSession.REQUIRED_SUCCESSES
        setFeedback(
            when {
                step.correct && step.learned ->
                    getString(R.string.training_ok_learned, step.successes, required)
                step.correct ->
                    getString(R.string.training_ok, step.successes, required)
                step.learned ->
                    getString(R.string.training_learned)
                step.rejected ->
                    getString(R.string.training_retry)
                else ->
                    getString(R.string.training_wrong, symbol?.uppercase() ?: "?")
            },
            ok = step.correct,
        )

        if (step.charCompleted) {
            showCurrent(keepFeedback = true)
        } else {
            progressText.text = progressLabel()
        }
    }

    /** 結果メッセージ。成功は緑、書き直しはオレンジ。 */
    private fun setFeedback(text: String, ok: Boolean) {
        feedbackText.text = text
        feedbackText.setTextColor(
            getColor(if (ok) R.color.train_ok else R.color.train_retry),
        )
    }

    private fun progressLabel(): String = getString(
        R.string.training_progress,
        session.position(),
        session.chars.size,
        session.successes,
        TrainingSession.REQUIRED_SUCCESSES,
    )

    private fun showCurrent(keepFeedback: Boolean = false) {
        if (session.finished) {
            showSummary()
            return
        }
        val c = session.current ?: return
        titleText.text = getString(R.string.training_write, c.uppercase())
        sampleView.setStroke(c, samples[c].orEmpty())
        sampleView.visibility = View.VISIBLE
        padView.visibility = View.VISIBLE
        padView.setHint(getString(R.string.training_pad_hint))
        skipButton.visibility = View.VISIBLE
        skipButton.text = getString(R.string.training_skip_char)
        finishButton.text = getString(R.string.training_skip_all)
        instructionText.visibility = View.VISIBLE
        instructionText.setText(R.string.training_instruction)
        progressText.text = progressLabel()
        if (!keepFeedback) setFeedback("", ok = true)
    }

    private fun showSummary() {
        store.save()
        val learned = session.learnedChars
        titleText.text = ""
        instructionText.setText(R.string.training_done_title)
        instructionText.visibility = View.VISIBLE
        sampleView.visibility = View.GONE
        padView.visibility = View.GONE
        skipButton.visibility = View.GONE
        finishButton.text = getString(R.string.training_close)
        progressText.text = ""
        setFeedback(
            if (learned.isEmpty()) {
                getString(R.string.training_done_none)
            } else {
                getString(
                    R.string.training_done_learned,
                    learned.joinToString(" ") { it.uppercase() },
                )
            },
            ok = true,
        )
    }
}
