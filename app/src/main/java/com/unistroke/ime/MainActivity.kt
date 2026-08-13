package com.unistroke.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView

/** デモ / 入口画面。学習データの管理は [SettingsActivity] に集約している。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // どのビルドが端末に入っているか（古い APK が残っていないかの確認用）
        findViewById<TextView>(R.id.text_build).text = BuildInfo.label(this)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        findViewById<Button>(R.id.btn_switch).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        promptNetworkConvertThenTraining()
    }

    /**
     * 初回起動時だけ、ネット変換の可否 -> 書き方トレーニング の順に尋ねる。
     *
     * ネット変換は既定オフなので、この画面を無視して使い始めても通信は起きない。
     * どちらのボタンにも同意の既定値を持たせず、明示的に選ばせる。
     */
    private fun promptNetworkConvertThenTraining() {
        if (Prefs.wasNetworkConvertAsked(this)) {
            maybePromptTraining()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.net_consent_title)
            .setMessage(R.string.net_consent_body)
            .setCancelable(false)
            // 通信しない側を positive（強調される位置）に置く
            .setPositiveButton(R.string.net_consent_keep_offline) { _, _ ->
                Prefs.setNetworkConvertEnabled(this, false)
                maybePromptTraining()
            }
            .setNegativeButton(R.string.net_consent_enable) { _, _ ->
                Prefs.setNetworkConvertEnabled(this, true)
                maybePromptTraining()
            }
            .show()
    }

    /** 初回起動時だけトレーニングへ誘導する。 */
    private fun maybePromptTraining() {
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.KEY_TRAINING_PROMPTED, false)) return
        prefs.edit().putBoolean(Prefs.KEY_TRAINING_PROMPTED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.training_prompt_title)
            .setMessage(R.string.training_prompt_body)
            .setPositiveButton(R.string.training_prompt_yes) { _, _ ->
                startActivity(Intent(this, TrainingActivity::class.java))
            }
            .setNegativeButton(R.string.training_prompt_later, null)
            .show()
    }
}
