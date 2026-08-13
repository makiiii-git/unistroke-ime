package com.unistroke.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView

/**
 * IME の設定・管理をまとめた画面。
 * IME サービスの [?] 長押しからも、MainActivity からも同じものを開く。
 */
class SettingsActivity : Activity() {

    private lateinit var store: PersonalTemplateStore
    private lateinit var prediction: PredictionEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = PersonalTemplateStore.get(this)
        prediction = PredictionEngine.get(this)

        val right = findViewById<RadioButton>(R.id.hand_right)
        val left = findViewById<RadioButton>(R.id.hand_left)
        val leftHanded = Prefs.isLeftHanded(this)
        right.isChecked = !leftHanded
        left.isChecked = leftHanded
        // 設定は即座に保存する。IME 側は変更を購読していてすぐ反映される。
        right.setOnClickListener { Prefs.setLeftHanded(this, false) }
        left.setOnClickListener { Prefs.setLeftHanded(this, true) }

        // ネット変換のオプトイン。既定はオフ（通信しない）。
        val netOff = findViewById<RadioButton>(R.id.net_off)
        val netOn = findViewById<RadioButton>(R.id.net_on)
        val netEnabled = Prefs.isNetworkConvertEnabled(this)
        netOff.isChecked = !netEnabled
        netOn.isChecked = netEnabled
        netOff.setOnClickListener { Prefs.setNetworkConvertEnabled(this, false) }
        netOn.setOnClickListener { Prefs.setNetworkConvertEnabled(this, true) }

        // 変換エンジン。ネット変換がオフのときは、どちらを選んでも端末内で変換する。
        val engineAuto = findViewById<RadioButton>(R.id.engine_auto)
        val engineOnDevice = findViewById<RadioButton>(R.id.engine_ondevice)
        val onDeviceOnly = Prefs.isOnDeviceOnly(this)
        engineAuto.isChecked = !onDeviceOnly
        engineOnDevice.isChecked = onDeviceOnly
        engineAuto.setOnClickListener { Prefs.setConvertEngine(this, Prefs.ENGINE_AUTO) }
        engineOnDevice.setOnClickListener { Prefs.setConvertEngine(this, Prefs.ENGINE_ONDEVICE) }

        // 速書き調査用のログ（既定オフ）。入力内容は出さない。
        val debug = findViewById<CheckBox>(R.id.debug_strokes)
        debug.isChecked = Prefs.isDebugStrokes(this)
        debug.setOnCheckedChangeListener { _, on -> Prefs.setDebugStrokes(this, on) }

        findViewById<Button>(R.id.btn_license).setOnClickListener {
            startActivity(Intent(this, LicenseActivity::class.java))
        }

        findViewById<Button>(R.id.btn_training).setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }
        // 学習リセットは取り返しがつかないうえ、直後に認識精度が落ちて
        // 「IME が壊れた」と誤解されやすい。何が失われるかを出してから消す。
        findViewById<Button>(R.id.btn_reset_learning).setOnClickListener {
            confirmResetLearning()
        }
        findViewById<Button>(R.id.btn_reset_history).setOnClickListener {
            prediction.reset()
            refresh()
        }
    }

    /**
     * 学習リセットの確認 -> 実行 -> トレーニングへの誘導。
     *
     * リセット直後は個人テンプレートが無くなるぶん認識精度が確実に落ちる。
     * それを事前に伝え、終わったらその場で復旧手段（トレーニング）へ繋ぐ。
     */
    private fun confirmResetLearning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_learning_title)
            .setMessage(R.string.reset_learning_body)
            // 破壊的な操作なので、既定の位置（positive）にはキャンセルを置く
            .setPositiveButton(R.string.reset_learning_cancel, null)
            .setNegativeButton(R.string.reset_learning_ok) { _, _ ->
                store.reset()
                refresh()
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

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        store.reloadIfChanged()
        // どのビルドが端末に入っているか（古い APK が残っていないかの確認用）
        findViewById<TextView>(R.id.text_build).text = BuildInfo.label(this)
        prediction.reloadIfChanged()

        findViewById<TextView>(R.id.text_history).text =
            getString(R.string.history_count, prediction.size())

        val summary = store.summary()
        findViewById<TextView>(R.id.text_learning).text = if (summary.isEmpty()) {
            getString(R.string.learning_empty)
        } else {
            // ★ = トレーニングで登録したもの
            summary.joinToString("   ") { (symbol, count, trained) ->
                (if (trained) "★" else "") + symbol + " x" + count
            }
        }
    }
}
