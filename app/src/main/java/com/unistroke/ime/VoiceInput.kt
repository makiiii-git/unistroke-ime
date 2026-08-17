package com.unistroke.ime

// 定数を直接取り込むのは、android.Manifest が配布マニフェスト
// （[DictionaryUpdater.Manifest]）と名前でぶつかるため。
import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 音声入力。[SpeechRecognizer] を IME サービスから直接使う薄いラッパ。
 *
 * この IME の建て付けは「既定では入力内容を外へ出さない」。音声も同じ扱いにする。
 *
 *   既定（[Prefs.VOICE_ONDEVICE]）… Android 13 以降の**端末内音声認識**だけを使う。
 *                                    音声も認識結果も端末から出ない。
 *                                    使えない端末では音声入力は動かない（黙って
 *                                    クラウドへ切り替えたりはしない）。
 *   [Prefs.VOICE_AUTO]            … 端末内が使えればそれを使い、無理なときだけ
 *                                    端末の音声認識サービスへ渡す。この場合は
 *                                    サービスの実装次第で**音声が外部へ送られる**。
 *                                    設定でユーザーが明示的に選んだときだけ通る。
 *
 * 話し終わりは認識器が判定する（onEndOfSpeech -> onResults）。利用者に「終わりました」の
 * 操作をさせないので、[Callback.onVoiceResult] が来た時点で確定してよい。
 * 途中でやめる操作（[cancel]）だけを UI 側に用意すればよい。
 *
 * 録音は「開始したセッションのあいだ」だけ。結果・エラー・取り消しのいずれでも
 * 認識器を破棄してマイクを手放す（IME は常駐するので、掴んだままにしない）。
 *
 * すべてメインスレッドから呼ぶこと（[SpeechRecognizer] の制約）。
 */
class VoiceInput(private val context: Context) {

    interface Callback {
        /** マイクが開いた（ここから声を拾う）。 */
        fun onVoiceReady()

        /**
         * セッションの途中でエンジンが変わった（端末内 -> 端末の音声認識サービス）。
         * 表示が「端末内」のままだと、実際には外へ出ているのに嘘の表示になるので必ず伝える。
         */
        fun onVoiceEngineChanged(onDevice: Boolean)

        /** 入力レベル（dB）。バナーのメーター用。 */
        fun onVoiceLevel(rms: Float)

        /** 認識途中の文字列。確定ではない。 */
        fun onVoicePartial(text: String)

        /** 発話が終わり、認識結果を待っている。 */
        fun onVoiceWorking()

        /** 認識できた。[text] は空でない。 */
        fun onVoiceResult(text: String)

        /**
         * 認識できなかった／始められなかった。[message] はそのまま表示できる日本語。
         *
         * @param silent 「何も話されなかった」だけか。連続入力では、これが true の
         *               あいだは黙って聞き直してよい（エラー表示を出さない）。
         */
        fun onVoiceFailed(message: String, silent: Boolean)
    }

    /** 開始できるか。[start] の前に必ず確かめる。 */
    enum class Ready {
        /** 端末内認識で始められる（音声は端末から出ない）。 */
        ON_DEVICE,

        /** 端末の音声認識サービスで始められる（外部へ送られる場合がある）。 */
        SERVICE,

        /** マイクの許可がない。 */
        NEED_PERMISSION,

        /** 端末内認識が使えない（設定は「端末内のみ」）。 */
        NO_ON_DEVICE,

        /** 音声認識サービスそのものが無い。 */
        NO_SERVICE,
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null

    /** いま端末内認識でセッションを張っているか（フォールバック判定に使う）。 */
    private var onDeviceSession = false

    /** このセッションで一度フォールバックしたか（無限に往復させない）。 */
    private var fellBack = false

    var isListening: Boolean = false
        private set

    // ------------------------------------------------------------- 可否判定

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 端末内音声認識が使えるか（Android 13 以降かつ端末が持っている場合のみ）。 */
    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun serviceAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * いまの設定と端末の状態で始められるか。
     * 端末内が使えるならエンジン設定によらず端末内を優先する（外へ出さない側に倒す）。
     */
    fun check(): Ready {
        if (!hasMicPermission()) return Ready.NEED_PERMISSION
        if (onDeviceAvailable()) return Ready.ON_DEVICE
        if (Prefs.isVoiceOnDeviceOnly(context)) return Ready.NO_ON_DEVICE
        return if (serviceAvailable()) Ready.SERVICE else Ready.NO_SERVICE
    }

    // --------------------------------------------------------------- 開始/終了

    /**
     * 認識を始める。[check] が [Ready.ON_DEVICE] / [Ready.SERVICE] を返したときだけ呼ぶこと。
     * 前のセッションが残っていれば破棄してから始める。
     */
    fun start(callback: Callback) {
        cancel()
        this.callback = callback
        fellBack = false
        beginSession(useOnDevice = onDeviceAvailable())
    }

    private fun beginSession(useOnDevice: Boolean) {
        val r = createRecognizer(useOnDevice)
        if (r == null) {
            finish()
            callback?.onVoiceFailed(
                context.getString(R.string.voice_error_unavailable),
                silent = false,
            )
            this.callback = null
            return
        }
        onDeviceSession = useOnDevice
        recognizer = r
        isListening = true
        r.setRecognitionListener(listener)
        runCatching { r.startListening(recognizerIntent()) }.onFailure {
            finish()
            callback?.onVoiceFailed(
                context.getString(R.string.voice_error_client),
                silent = false,
            )
            this.callback = null
        }
    }

    private fun createRecognizer(useOnDevice: Boolean): SpeechRecognizer? = runCatching {
        if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()

    /**
     * 認識のパラメータ。言語は端末の言語設定に従う。
     *
     * EXTRA_PARTIAL_RESULTS を立てているのは、聞き取り中の文字列を未確定表示で
     * 見せるため。確定するのは onResults の内容だけ。
     */
    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun language(): String {
        val locale = context.resources.configuration.locales.let {
            if (it.isEmpty) null else it[0]
        }
        return locale?.toLanguageTag() ?: "ja-JP"
    }

    /** やめる。聞き取り中の内容は捨て、マイクを手放す。 */
    fun cancel() {
        callback = null
        val r = recognizer ?: run { isListening = false; return }
        runCatching { r.cancel() }
        runCatching { r.destroy() }
        recognizer = null
        isListening = false
    }

    /** IME を畳むときに呼ぶ。 */
    fun release() = cancel()

    /** セッションを終える（コールバックは呼び出し側が続けて出す）。 */
    private fun finish() {
        val r = recognizer
        recognizer = null
        isListening = false
        runCatching { r?.destroy() }
    }

    // ------------------------------------------------------------ listener

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            callback?.onVoiceReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            callback?.onVoiceLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            callback?.onVoiceWorking()
        }

        override fun onError(error: Int) {
            // 端末内認識でモデルが無いだけなら、設定が許すときに限り
            // 端末の音声認識サービスへ 1 度だけ切り替える（既定では切り替えない）。
            if (shouldFallBack(error)) {
                fellBack = true
                finish()
                beginSession(useOnDevice = false)
                // 切り替わったことを隠さない（表示が「端末内」のままにならないように）
                if (isListening) callback?.onVoiceEngineChanged(false)
                return
            }
            if (onDeviceSession && isModelMissing(error)) triggerModelDownload()
            val cb = callback
            finish()
            callback = null
            cb?.onVoiceFailed(context.getString(messageFor(error)), silent = isSilence(error))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstText(partialResults)
            if (text.isNotEmpty()) callback?.onVoicePartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = firstText(results)
            val cb = callback
            finish()
            callback = null
            if (text.isEmpty()) {
                cb?.onVoiceFailed(context.getString(R.string.voice_error_no_match), silent = true)
            } else {
                cb?.onVoiceResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    /**
     * 「何も話されなかった」だけのエラーか。
     * 連続入力では、これだけなら黙って聞き直す（エラーを出して止めない）。
     */
    private fun isSilence(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /**
     * 端末内モデルが無い系のエラーか。
     * 定数は Android 13 で増えたので、値の存在する版でだけ見る。
     */
    private fun isModelMissing(error: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

    /** 端末内 -> サービスへ落としてよいか（「端末内のみ」設定では絶対に落とさない）。 */
    private fun shouldFallBack(error: Int): Boolean =
        onDeviceSession &&
            !fellBack &&
            isModelMissing(error) &&
            !Prefs.isVoiceOnDeviceOnly(context) &&
            serviceAvailable()

    /**
     * 端末内音声モデルの取得を促す。落としてくるのはシステム側で、
     * 完了を待たずに戻る（次に長押ししたときには使えるようになっている想定）。
     */
    private fun triggerModelDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.triggerModelDownload(recognizerIntent())
            // ダウンロードはシステム側の処理として続く。こちらは掴み続けない。
            r.destroy()
        }
    }

    private fun messageFor(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> R.string.voice_error_audio
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> R.string.voice_error_network

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> R.string.voice_error_no_match

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
        SpeechRecognizer.ERROR_SERVER -> R.string.voice_error_server
        else -> if (isModelMissing(error)) {
            R.string.voice_error_model
        } else {
            R.string.voice_error_client
        }
    }
}
