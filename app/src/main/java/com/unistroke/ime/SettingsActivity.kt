package com.unistroke.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

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

        // 音声入力。既定オンだが、実際に録音するのは手書きゾーンを長押ししたときだけ。
        findViewById<CheckBox>(R.id.check_voice_input).apply {
            isChecked = Prefs.isVoiceInputEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setVoiceInputEnabled(this@SettingsActivity, on)
                refreshVoiceState()
            }
        }

        // ボイスコマンドと連続音声入力（ハンズフリー）。どちらも既定オン。
        findViewById<CheckBox>(R.id.check_voice_commands).apply {
            isChecked = Prefs.isVoiceCommandsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setVoiceCommandsEnabled(this@SettingsActivity, on)
            }
        }
        findViewById<CheckBox>(R.id.check_voice_continuous).apply {
            isChecked = Prefs.isVoiceContinuous(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setVoiceContinuous(this@SettingsActivity, on)
            }
        }

        // 音声認識エンジン。既定は端末内のみ（音声を端末から出さない）。
        val voiceOnDevice = findViewById<RadioButton>(R.id.voice_engine_ondevice)
        val voiceAuto = findViewById<RadioButton>(R.id.voice_engine_auto)
        val voiceOnDeviceOnly = Prefs.isVoiceOnDeviceOnly(this)
        voiceOnDevice.isChecked = voiceOnDeviceOnly
        voiceAuto.isChecked = !voiceOnDeviceOnly
        voiceOnDevice.setOnClickListener {
            Prefs.setVoiceEngine(this, Prefs.VOICE_ONDEVICE)
            refreshVoiceState()
        }
        voiceAuto.setOnClickListener {
            Prefs.setVoiceEngine(this, Prefs.VOICE_AUTO)
            refreshVoiceState()
        }

        // 変換エンジン。ネット変換がオフのときは、どちらを選んでも端末内で変換する。
        val engineAuto = findViewById<RadioButton>(R.id.engine_auto)
        val engineOnDevice = findViewById<RadioButton>(R.id.engine_ondevice)
        val onDeviceOnly = Prefs.isOnDeviceOnly(this)
        engineAuto.isChecked = !onDeviceOnly
        engineOnDevice.isChecked = onDeviceOnly
        engineAuto.setOnClickListener { Prefs.setConvertEngine(this, Prefs.ENGINE_AUTO) }
        engineOnDevice.setOnClickListener { Prefs.setConvertEngine(this, Prefs.ENGINE_ONDEVICE) }

        // 拡張辞書。入っていなければ取得、入っていれば更新確認のボタンになる。
        findViewById<Button>(R.id.btn_dict_action).setOnClickListener { runDictionaryUpdate() }
        findViewById<Button>(R.id.btn_dict_remove).setOnClickListener {
            if (DictionaryUpdater.removeExtension(this)) {
                Toast.makeText(this, R.string.dict_removed, Toast.LENGTH_LONG).show()
            }
            refresh()
        }
        findViewById<CheckBox>(R.id.check_dict_auto).apply {
            isChecked = Prefs.isDictAutoUpdate(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setDictAutoUpdate(this@SettingsActivity, on)
            }
        }

        // アプリの更新確認（既定オン）。IME からは走らせない。
        findViewById<CheckBox>(R.id.check_app_auto_update).apply {
            isChecked = Prefs.isAppAutoUpdate(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setAppAutoUpdate(this@SettingsActivity, on)
            }
        }
        findViewById<Button>(R.id.btn_update_check).setOnClickListener { runAppUpdateCheck() }

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
        // 学習リセットは文字単位で選べる専用画面で行う（全文字リセットもそこから）
        findViewById<Button>(R.id.btn_reset_learning).setOnClickListener {
            startActivity(Intent(this, ResetLearningActivity::class.java))
        }
        findViewById<Button>(R.id.btn_reset_history).setOnClickListener {
            prediction.reset()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * アプリ本体の更新確認（手動）。
     * 手動なので「最新です」も失敗も画面に出す。
     */
    private fun runAppUpdateCheck() {
        val button = findViewById<Button>(R.id.btn_update_check)
        button.isEnabled = false
        button.setText(R.string.update_checking)
        AppUpdateUi.checkAndOffer(this, manual = true) {
            if (isFinishing || isDestroyed) return@checkAndOffer
            button.isEnabled = true
            button.setText(R.string.update_check_now)
        }
    }

    /**
     * 拡張辞書の取得・更新。
     * 進捗はボタンのラベルに出す（別ダイアログを重ねない）。
     */
    private fun runDictionaryUpdate() {
        val button = findViewById<Button>(R.id.btn_dict_action)
        button.isEnabled = false
        DictionaryUpdater.checkAndUpdate(this, autoDownload = true) { p ->
            if (isFinishing || isDestroyed) return@checkAndUpdate
            when (p) {
                is DictionaryUpdater.Progress.Done,
                is DictionaryUpdater.Progress.Failed,
                DictionaryUpdater.Progress.UpToDate,
                -> {
                    DictionaryStatus.toast(this, p)
                    button.isEnabled = true
                    refresh()
                }

                else -> button.text = DictionaryStatus.message(this, p)
            }
        }
    }

    /**
     * この端末で音声入力が使えるかを、選んだエンジンに即して出す。
     *
     * 端末内認識は Android 13 以降の対応端末だけなので、
     * 「設定はオンなのに長押ししても何も起きない」を画面上で説明できるようにする。
     */
    private fun refreshVoiceState() {
        val voice = VoiceInput(this)
        val state = when {
            voice.onDeviceAvailable() -> R.string.voice_state_ondevice
            !voice.serviceAvailable() -> R.string.voice_state_none
            Prefs.isVoiceOnDeviceOnly(this) -> R.string.voice_state_blocked
            else -> R.string.voice_state_service
        }
        findViewById<TextView>(R.id.text_voice_state).setText(state)
    }

    private fun refresh() {
        store.reloadIfChanged()
        refreshVoiceState()

        // 拡張辞書の状態。入っていれば版を出し、無ければコア辞書である旨を出す。
        val version = DictionaryUpdater.installedVersion(this)
        val hasExt = version > 0
        findViewById<TextView>(R.id.text_dict_state).text = if (hasExt) {
            getString(R.string.dict_state_ext, version)
        } else {
            getString(R.string.dict_state_core)
        }
        findViewById<Button>(R.id.btn_dict_action).setText(
            if (hasExt) R.string.dict_btn_check else R.string.dict_btn_download,
        )
        findViewById<Button>(R.id.btn_dict_remove).isEnabled = hasExt

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
