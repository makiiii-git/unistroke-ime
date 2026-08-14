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

        runSetupChain()
        maybeAutoCheckDictionary()
        maybeAutoCheckAppUpdate()
    }

    /**
     * 拡張辞書の更新を「ついでに」確認する。
     *
     * 自動確認を有効にしていて、前回から十分に間が空いているときだけ。
     * 定期実行（WorkManager など）は使わない。使うと利用者が何もしていないのに
     * 通信が起き続けることになり、この IME の建て付けと合わない。
     * 結果は静かに反映し、失敗しても黙って諦める（今の辞書のまま動く）。
     */
    /**
     * アプリ本体の更新確認（自動）。
     *
     * 既定オン。1 日 1 回を上限に、この画面を開いたときだけ便乗する。
     * **IME サービスからは呼ばない**（入力中に通信やダイアログが走らないように）。
     * 自動なので、更新が無いときも失敗したときも何も出さない。
     */
    private fun maybeAutoCheckAppUpdate() {
        if (!AppUpdater.shouldAutoCheck(this, System.currentTimeMillis())) return
        AppUpdateUi.checkAndOffer(this, manual = false)
    }

    private fun maybeAutoCheckDictionary() {
        if (!DictionaryUpdater.shouldAutoCheck(this, System.currentTimeMillis())) return
        DictionaryUpdater.checkAndUpdate(this, autoDownload = true) { p ->
            if (isFinishing || isDestroyed) return@checkAndUpdate
            // 成功したときだけ知らせる。失敗や「最新です」は黙っておく。
            if (p is DictionaryUpdater.Progress.Done) DictionaryStatus.toast(this, p)
        }
    }

    override fun onDestroy() {
        progress?.dismiss()
        progress = null
        super.onDestroy()
    }

    /** ダウンロード中の進捗ダイアログ。画面が終わるときに必ず閉じる。 */
    private var progress: AlertDialog? = null

    /**
     * 初回起動時の案内を順に出す。
     *
     *   1. ネット変換の可否（入力内容を外へ送るか）
     *   2. 拡張辞書の取得（辞書ファイルを受け取るか）
     *   3. 書き方トレーニング
     *
     * 1 と 2 は別物なので、それぞれ独立に尋ねる。1 を断った人でも 2 は選べる
     * （2 は入力内容を送らず、ファイルを受け取るだけなので）。
     * すべて既定は「しない」側で、無視して使い始めても通信は起きない。
     */
    private fun runSetupChain() {
        if (!Prefs.wasNetworkConvertAsked(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.net_consent_title)
                .setMessage(R.string.net_consent_body)
                .setCancelable(false)
                // 通信しない側を positive（強調される位置）に置く
                .setPositiveButton(R.string.net_consent_keep_offline) { _, _ ->
                    Prefs.setNetworkConvertEnabled(this, false)
                    promptDictionaryThenTraining()
                }
                .setNegativeButton(R.string.net_consent_enable) { _, _ ->
                    Prefs.setNetworkConvertEnabled(this, true)
                    promptDictionaryThenTraining()
                }
                .show()
            return
        }
        promptDictionaryThenTraining()
    }

    /**
     * 拡張辞書の取得を一度だけ案内する。
     * 断っても、既に入っていても、そのまま次（トレーニング）へ進む。
     */
    private fun promptDictionaryThenTraining() {
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.KEY_DICT_PROMPTED, false) ||
            DictionaryUpdater.isExtensionInstalled(this)
        ) {
            maybePromptTraining()
            return
        }
        prefs.edit().putBoolean(Prefs.KEY_DICT_PROMPTED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.dict_prompt_title)
            .setMessage(R.string.dict_prompt_body)
            .setPositiveButton(R.string.dict_prompt_yes) { _, _ -> downloadDictionary() }
            .setNegativeButton(R.string.dict_prompt_later) { _, _ -> maybePromptTraining() }
            .setOnCancelListener { maybePromptTraining() }
            .show()
    }

    /** 拡張辞書を取得する。終わったら（成否によらず）トレーニングの案内へ進む。 */
    private fun downloadDictionary() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dict_title)
            .setMessage(R.string.dict_checking)
            .setCancelable(false)
            .create()
        progress = dialog
        dialog.show()

        DictionaryUpdater.checkAndUpdate(this, autoDownload = true) { p ->
            if (isFinishing || isDestroyed) return@checkAndUpdate
            when (p) {
                is DictionaryUpdater.Progress.Done,
                is DictionaryUpdater.Progress.Failed,
                DictionaryUpdater.Progress.UpToDate,
                -> {
                    dialog.dismiss()
                    progress = null
                    DictionaryStatus.toast(this, p)
                    maybePromptTraining()
                }

                else -> dialog.setMessage(DictionaryStatus.message(this, p))
            }
        }
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
