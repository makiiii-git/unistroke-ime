package com.unistroke.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 学習データ（個人テンプレート）を文字単位で選んでリセットする画面。
 * 設定の「学習データをリセット」から入る。全文字リセットもここから行う。
 */
class ResetLearningActivity : Activity() {

    private lateinit var store: PersonalTemplateStore
    private lateinit var listView: LinearLayout
    private lateinit var selectAll: CheckBox
    private lateinit var resetSelectedBtn: Button
    private lateinit var resetAllBtn: Button
    private lateinit var emptyText: TextView

    /** 表示中の行（文字 -> チェックボックス）。チェック状態はビューが持つ。 */
    private val rows = ArrayList<Pair<String, CheckBox>>()

    /** 「全て選択」を機械的に切り替えている最中の逆流ガード */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reset_learning)

        store = PersonalTemplateStore.get(this)
        listView = findViewById(R.id.list_symbols)
        selectAll = findViewById(R.id.check_select_all)
        resetSelectedBtn = findViewById(R.id.btn_reset_selected)
        resetAllBtn = findViewById(R.id.btn_reset_all)
        emptyText = findViewById(R.id.text_reset_empty)

        selectAll.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            syncing = true
            for ((_, box) in rows) box.isChecked = on
            syncing = false
            updateButtons()
        }
        resetSelectedBtn.setOnClickListener { confirmResetSelected() }
        resetAllBtn.setOnClickListener { confirmResetAll() }
    }

    override fun onResume() {
        super.onResume()
        store.reloadIfChanged()
        rebuild()
    }

    /** 学習済み文字の一覧から行を組み立て直す。 */
    private fun rebuild() {
        rows.clear()
        listView.removeAllViews()
        val summary = store.summary()
        for ((symbol, count, trained) in summary) {
            val box = CheckBox(this)
            // 設定画面の学習一覧と同じ表記（★ = トレーニングで登録）
            box.text = (if (trained) "★" else "") + symbol + " x" + count
            box.setTextColor(getColor(R.color.app_text))
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            box.minHeight = dp(48)
            box.setOnCheckedChangeListener { _, _ ->
                if (!syncing) updateButtons()
            }
            listView.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            rows.add(symbol to box)
        }

        val empty = rows.isEmpty()
        emptyText.visibility = if (empty) TextView.VISIBLE else TextView.GONE
        selectAll.visibility = if (empty) TextView.GONE else TextView.VISIBLE
        resetAllBtn.isEnabled = !empty
        updateButtons()
    }

    private fun selectedSymbols(): List<String> =
        rows.filter { it.second.isChecked }.map { it.first }

    private fun updateButtons() {
        val selected = selectedSymbols().size
        resetSelectedBtn.isEnabled = selected > 0
        // 全行チェックと「全て選択」の表示を揃える（機械的な変更は逆流させない）
        syncing = true
        selectAll.isChecked = rows.isNotEmpty() && selected == rows.size
        syncing = false
    }

    /** 選択した文字だけを消す。全文字ぶん選ばれていたら全リセットに格上げする。 */
    private fun confirmResetSelected() {
        val symbols = selectedSymbols()
        if (symbols.isEmpty()) return
        if (symbols.size == rows.size) {
            // 昇格待ちの候補も含めて全部消したいはずなので、全リセットと同じ扱いにする
            confirmResetAll()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_selected_title)
            .setMessage(
                getString(
                    R.string.reset_selected_body,
                    symbols.joinToString("・"),
                    symbols.size,
                ),
            )
            // 破壊的な操作なので、既定の位置（positive）にはキャンセルを置く
            .setPositiveButton(R.string.reset_learning_cancel, null)
            .setNegativeButton(R.string.reset_learning_ok) { _, _ ->
                store.resetSymbols(symbols)
                Toast.makeText(this, R.string.reset_selected_done, Toast.LENGTH_LONG).show()
                rebuild()
            }
            .show()
    }

    /** 全文字リセット。従来どおり、消した後はトレーニングへの復旧導線を出す。 */
    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_learning_title)
            .setMessage(R.string.reset_learning_body)
            .setPositiveButton(R.string.reset_learning_cancel, null)
            .setNegativeButton(R.string.reset_learning_ok) { _, _ ->
                store.reset()
                rebuild()
                promptTrainingAfterReset()
            }
            .show()
    }

    /** リセット後の復旧導線。ここから直接トレーニングへ入れる。 */
    private fun promptTrainingAfterReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_done_title)
            .setMessage(R.string.reset_done_body)
            .setPositiveButton(R.string.reset_done_training) { _, _ ->
                startActivity(Intent(this, TrainingActivity::class.java))
            }
            .setNegativeButton(R.string.reset_done_later, null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
