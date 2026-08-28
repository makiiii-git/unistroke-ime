package com.unistroke.ime

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.inputmethodservice.InputMethodService.Insets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * 一筆書きストローク入力の InputMethodService。
 *
 * シフト状態（Caps / Punctuation / Extended）、入力モード（abc / かな / カナ）、
 * ローマ字合成と漢字変換の状態は、すべてここに集約する。
 */
class UniStrokeIME : InputMethodService(), UniStrokeView.Listener {

    private enum class Shift { OFF, ONCE, LOCK }

    private enum class InputMode { LATIN, HIRAGANA, KATAKANA }

    /**
     * かなモード中の「一時アルファベット入力」。
     *
     * 上スワイプ（下 -> 上の縦線）で NONE -> UPPER -> LOWER -> NONE と回る。
     * UPPER / LOWER のときは次の 1 文字だけを Latin として直接確定し、
     * 確定したら自動で NONE（= かな入力）へ戻る。
     */
    private enum class TempLatin { NONE, UPPER, LOWER }

    private var shift = Shift.OFF

    private var tempLatin = TempLatin.NONE

    /** 既定はかな。実際の初期値は onStartInputView で入力欄に応じて決める。 */
    private var inputMode = InputMode.HIRAGANA

    private val prefs: SharedPreferences by lazy { Prefs.of(this) }

    /** 設定画面での変更を即座に反映する（入力欄を開き直さなくても効く）。 */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_HANDEDNESS -> applyLayoutPrefs()
                Prefs.KEY_NET_CONVERT -> netConvertOptedIn = Prefs.isNetworkConvertEnabled(this)
                Prefs.KEY_CONVERT_ENGINE -> onDeviceOnly = Prefs.isOnDeviceOnly(this)
                Prefs.KEY_DEBUG_STROKES ->
                    inputView?.debugStrokes = Prefs.isDebugStrokes(this)
                Prefs.KEY_VOICE_INPUT, Prefs.KEY_VOICE_ENGINE -> syncVoiceAvailability()
            }
        }
    private var symbolMode = SymbolMode.NORMAL

    private var inputView: UniStrokeView? = null

    /** 未確定ローマ字（例: "k"）。 */
    private val romaji = StringBuilder()

    /** かなに確定済みだが、まだアプリへコミットしていない文字列。 */
    private val kana = StringBuilder()

    /**
     * いまアプリ側に合成領域（下線付きの未確定表示）を出しているか。
     *
     * [clearComposing] が選択範囲を巻き込んで消さないようにするために持つ。
     * setComposingText は合成領域が無いと選択範囲の置換として働くので、
     * 「出していないのに空文字で消しに行く」ことがあってはいけない。
     */
    private var composingShown = false

    /**
     * [composingShown] が「どの InputConnection に対しての話か」。
     *
     * 入力欄が切り替わると currentInputConnection は別インスタンスになる。
     * 記録した接続と違うものが返ってきたときに合成領域を出しているつもりで
     * setComposingText("") を投げると、**新しい入力欄の選択範囲を消す**。
     * そのため「所有者が一致するときだけ出している」と見なし、
     * 一致しないときは記録を捨てて何も送らない（安全側に倒す）。
     */
    private var composingOwner: InputConnection? = null

    // --------------------------------------------------- 自動アルファベット化

    /**
     * いまの単語で打たれた生のローマ字（ストロークそのままの ASCII）。
     *
     * かなへ変換したあとでも元の綴りへ巻き戻せるように、単語単位で持っておく。
     * 単語の区切り（スペース・変換確定・リターン・モード切替など）でクリアする。
     */
    private val wordRaw = StringBuilder()

    /**
     * この単語を英字として扱っているか（[RomajiConverter.looksNonJapanese] が真になった）。
     *
     * 日本語のローマ字として明らかに無理のある綴り（"str" など）を打ったときだけ立つ。
     * 立っている間はかな変換をやめ、[wordRaw] をそのまま未確定として見せる。
     * 候補バーには「かな解釈」も残すので、誤判定でも 1 タップで戻れる。
     */
    private var autoLatin = false

    // ------------------------------------------------------------ 確定アンドゥ

    /**
     * 直前にアプリへ確定した内容。バックスペース 1 回で確定前へ戻すために覚えておく。
     *
     * [surface] が実際にコミットした文字列、[reading] が戻すときの読み（ひらがな）。
     * 「直前」は厳密に「確定のあと何も入力していない」こと ―― ストローク・カーソル移動・
     * 全選択・モード切替・入力欄の変更のいずれかが挟まったら [clearUndo] で捨てる。
     */
    private class CommitUndo(val surface: String, val reading: String)

    private var lastCommit: CommitUndo? = null

    /**
     * こちらが選択した範囲（長押しの直前確定選択）。
     * 全選択ボタンの 2 回目タップで「消してよい選択か」を判定するのに使う。
     */
    private var ownSelection: Pair<Int, Int>? = null

    /** 確定を記録する。[reading] が空なら戻す先が無いので覚えない。 */
    private fun noteCommit(surface: String, reading: String) {
        lastCommit = if (surface.isEmpty() || reading.isEmpty()) {
            null
        } else {
            CommitUndo(surface, reading)
        }
    }

    /**
     * 確定アンドゥと「こちらが選択した範囲」を無効にする。
     * 無効化の条件は両者で同じ（確定・選択の直後でなくなったら捨てる）。
     */
    private fun clearUndo() {
        lastCommit = null
        ownSelection = null
    }

    /**
     * 直前の確定を取り消して、確定前のひらがな合成状態へ戻す。
     * 戻せたら true。
     *
     * 確定した文字列をアプリから削除し、元の読みを composing として復元する。
     * 文節ごとに確定した場合は、直前に確定した文節ぶんだけが戻る
     * （残りの未確定文節には触らない）。
     */
    private fun undoLastCommit(ic: InputConnection): Boolean {
        val undo = lastCommit ?: return false
        clearUndo()
        // 変換中や合成中に呼ばれることは無い（呼び出し側で弾いている）が、
        // 念のため合成を閉じてから削除する。
        clearComposing(ic)
        // **消す前に、カーソル直前が本当にその文字列か確かめる。**
        //
        // 記録とアプリ側の実体が 1 文字でもずれていると、
        // deleteSurroundingText(surface.length) は「余分な文字 + surface の後ろ側」を
        // 消し、そのあと reading を合成として挿し直すので、
        // surface の先頭が残って **1 回につき 1 文字ずつ増える**。
        // これが「一文字ずつ文字が増えていく」の壊れ方そのもの。
        // 同じ lastCommit を使う [selectLastCommit] は元からこの確認をしている。
        val before = ic.getTextBeforeCursor(undo.surface.length, 0)
        if (before?.toString() != undo.surface) return false
        if (!ic.deleteSurroundingText(undo.surface.length, 0)) return false
        kana.setLength(0)
        kana.append(undo.reading)
        romaji.setLength(0)
        endWord()
        updateComposing()
        scheduleLiveSuggest()
        syncView()
        return true
    }

    // ------------------------------------------------------------- 漢字変換

    private val converter = GoogleConvertClient()

    /**
     * 圏外・通信失敗・オフライン設定のときに肩代わりする端末内変換。
     * 辞書 assets を開けない端末では null になり、その場合は従来どおり
     * 履歴と内蔵フレーズ辞書だけで動く。
     */
    private var onDevice: OnDeviceConverter? = null

    /** いま候補バーに出ている変換候補が端末内辞書由来か（「端末内」チップの出し分け）。 */
    private var candidatesFromOnDevice = false

    private var segments: List<GoogleConvertClient.Segment> = emptyList()
    private var choices: IntArray = IntArray(0)
    private var activeSegment = 0

    // ------------------------------------------------- ネット変換のオプトイン

    /**
     * 通信の可否は 2 段で判定する。両方が真のときだけ外へ出す。
     *
     *  1. [netConvertOptedIn]   … ユーザーが設定で明示的に有効化したか（既定 false）
     *  2. [netConvertAllowedHere] … いま開いている入力欄で送ってよいか
     *
     * 2 を分けているのは、オプトイン済みでもパスワード欄などでは送らないため。
     * かなモードへ手動で切り替えられても通らないハードな禁止として効かせる。
     */
    private var netConvertOptedIn = false
    private var netConvertAllowedHere = false

    /** この入力欄で入力履歴を学習してよいか（シークレットタブ等では false）。 */
    private var learningAllowedHere = true

    /** 設定で「端末内のみ」が選ばれているか。 */
    private var onDeviceOnly = false

    /**
     * 実際に通信してよいか。
     * オプトイン・入力欄・エンジン設定の 3 つがすべて許して初めて外へ出す。
     */
    private val networkConvertOk: Boolean
        get() = netConvertOptedIn && netConvertAllowedHere && !onDeviceOnly

    /** スペースで入る「文節編集モード」かどうか。 */
    private val converting: Boolean get() = segments.isNotEmpty()

    // ------------------------------------------------------- 自動変換候補

    private val handler = Handler(Looper.getMainLooper())

    /** 自動候補の元になった読み。合成内容が変わったら無効。 */
    private var liveReading: String = ""

    /** 自動候補の元データ（スペースを押したときに再リクエストせず流用する）。 */
    private var liveSegments: List<GoogleConvertClient.Segment> = emptyList()

    /** 候補バーに出す全文候補。タップで全文確定。 */
    private var liveCandidates: List<PredictionEngine.Candidate> = emptyList()

    /** Google Suggest の結果（読みごと）。 */
    private var suggestReading: String = ""
    private var suggestResults: List<String> = emptyList()

    private lateinit var prediction: PredictionEngine

    private val liveSuggestRunnable = Runnable { requestLiveSuggest() }

    // --------------------------------------------------------- 書き癖の学習

    private lateinit var personalStore: PersonalTemplateStore
    private lateinit var learner: StrokeLearner

    // ------------------------------------------------------------- 音声入力

    private lateinit var voice: VoiceInput

    /**
     * この入力欄で音声入力してよいか。
     *
     * パスワード欄では端末内認識であっても許さない（声に出させること自体が危ない）。
     * さらに、音声を端末外へ出す経路（[VoiceInput.Ready.SERVICE]）は
     * ネット変換とまったく同じ基準（[netConvertAllowedHere]）でも止める。
     */
    private var voiceAllowedHere = true

    /** 録音〜結果待ちの最中か（案内バナーだけ出ている状態は含まない）。 */
    private var voiceActive = false

    /** いまのセッションが端末内認識か（表示を実態に合わせるために持つ）。 */
    private var voiceOnDevice = false

    /** 何らかのバナー（録音中・認識中・案内）を出しているか。 */
    private var voiceBannerShown = false

    /**
     * このセッションが連続入力か（開始時の設定で決め、途中では変えない）。
     *
     * 連続入力では 1 回の長押しで、「音声終了」と言うか ✕ を押すまで聞き続ける。
     * ハンズフリーで使うための挙動なので、**始めるとき以外はマイクを開けない**という
     * 原則（長押しが唯一の入口）は変えていない。
     */
    private var voiceContinuous = false

    /** 連続入力で「何も話されなかった」が続いた回数。続いたら自動で終わる。 */
    private var voiceSilentRounds = 0

    /** 音声で最後に確定した文字列（「取り消し」で消す対象）。 */
    private var lastVoiceCommit: String? = null

    /** 案内バナーを一定時間で閉じるためのタイマー。 */
    private val voiceNoticeRunnable = Runnable { hideVoiceBanner() }

    /** 連続入力で次の発話を聞き始めるためのタイマー。 */
    private val voiceRestartRunnable = Runnable { restartVoiceListening() }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        // プロセス内で共有する唯一のインスタンスを使う（トレーニング画面と同じ実体）
        personalStore = PersonalTemplateStore.get(this)
        prediction = PredictionEngine.get(this)
        // 辞書はメモリマップするだけなのでここで開いてよい（展開もパースもしない）
        onDevice = OnDeviceConverter.get(this)
        // 実体（認識器・マイク）は長押しで始めるまで作らない。
        // 設定の変更通知（KEY_VOICE_*）から触るので、購読より先に用意しておく。
        voice = VoiceInput(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        learner = StrokeLearner(personalStore).apply {
            guardProvider = { setName ->
                // 衝突ガードは標準テンプレートのみで判定する
                val zone = if (setName == PersonalTemplateStore.SET_NUMBER) Zone.NUMBER else Zone.ALPHA
                StrokeRecognizer(StrokeTemplates.templatesFor(zone, SymbolMode.NORMAL))
            }
            onPromoted = { refreshLearnedTemplates() }
        }
    }

    override fun onCreateInputView(): View {
        val view = UniStrokeView(this)
        view.listener = this
        personalStore.reloadIfChanged()
        view.personalTemplates = { setName -> personalStore.templatesFor(setName) }
        view.learnedSymbols = personalStore.learnedSymbols()
        view.debugStrokes = Prefs.isDebugStrokes(this)
        inputView = view
        applyLayoutPrefs()
        makeWindowTransparent()
        syncVoiceAvailability()
        syncView()
        return view
    }

    /**
     * 入力欄が変わった（= InputConnection が別インスタンスになった）。
     *
     * onStartInputView より先に必ず呼ばれる。ここで合成領域と確定の記録を
     * 落としておかないと、前の欄の状態のまま新しい欄を触ってテキストを壊す。
     * 新旧どちらの接続にも触らない（記録を捨てるだけ）。
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // 入力欄が変わったら録音も打ち切る（前の欄のつもりで喋った内容を持ち越さない）
        endVoice()
        noteComposingGone()
        clearUndo()
    }

    /** 接続が外れた。触れない接続なので記録だけ落とす。 */
    override fun onUnbindInput() {
        endVoice()
        noteComposingGone()
        clearUndo()
        super.onUnbindInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 入力欄の種類に応じて開始モードを決める。
        // かなに向かない欄（パスワード・メール・URL・数値など）は英字で開く。
        inputMode = if (isLatinOnlyField(info)) InputMode.LATIN else savedMode()
        // 通信と学習の可否は入力欄ごとに引き直す（前の欄の判定を持ち越さない）。
        // 入力欄が変わったら前の欄の確定は取り消せない
        clearUndo()
        netConvertOptedIn = Prefs.isNetworkConvertEnabled(this)
        onDeviceOnly = Prefs.isOnDeviceOnly(this)
        netConvertAllowedHere = !isNoNetworkField(info)
        learningAllowedHere = !noPersonalizedLearning(info)
        voiceAllowedHere = !isPasswordField(info)
        syncVoiceAvailability()
        // 別プロセスで書き換わっていた場合に備えて読み直す（同一プロセスならシングルトンで既に最新）。
        personalStore.reloadIfChanged()
        prediction.reloadIfChanged()
        inputView?.debugStrokes = Prefs.isDebugStrokes(this)
        // 認識器のキャッシュは必ず作り直す。データが同じ実体でも、
        // UniStrokeView 側は古いテンプレートで組んだ認識器を握ったままなので、
        // reloadIfChanged() の戻り値で条件分岐してはいけない。
        refreshLearnedTemplates()
        applyLayoutPrefs()
        makeWindowTransparent()
        resetAll()
    }

    /**
     * 利き手と画面の展開状態、そして大画面でのパネル位置をビューへ渡す。
     * 展開判定は smallestScreenWidthDp。Fold の内側画面（sw600dp 以上）では効き、
     * 通常のスマホは横向きにしても sw が変わらないので効かない。
     */
    private fun applyLayoutPrefs() {
        val view = inputView ?: return
        val leftHanded = Prefs.isLeftHanded(this)
        view.leftHanded = leftHanded
        view.expandedScreen =
            resources.configuration.smallestScreenWidthDp >= EXPANDED_MIN_SW_DP
        view.panelOnLeft = Prefs.panelOnLeft(this, leftHanded)
        view.panelYRatio = Prefs.panelYRatio(this)
    }

    /** 大画面でパネルをドラッグして置き直した。次に開くときも同じ場所へ出す。 */
    override fun onPanelMoved(onLeft: Boolean, yRatio: Float) {
        Prefs.setPanelPosition(this, onLeft, yRatio)
    }

    /**
     * かな入力に向かない入力欄か。
     * 一般的な IME と同じく、パスワード / メールアドレス / URL / 数値 / 電話 / 日時は英数で開く。
     */
    private fun isLatinOnlyField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> true

            InputType.TYPE_CLASS_TEXT -> when (type and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_FILTER,
                -> true

                else -> false
            }

            else -> false
        }
    }

    /**
     * この入力欄の内容を外部へ送ってはいけないか。
     *
     * [isLatinOnlyField] が「かなで開かない欄」という UI の都合なのに対し、
     * こちらはユーザーが手動でかなへ切り替えても通信を許さないハードな禁止。
     * 素性が分からない欄は送らない側に倒す。
     */
    private fun isNoNetworkField(info: EditorInfo?): Boolean {
        if (info == null) return true
        if (info.inputType == InputType.TYPE_NULL) return true
        if (noPersonalizedLearning(info)) return true
        return isLatinOnlyField(info)
    }

    /**
     * アプリ側が「学習するな」と指定しているか。
     * ブラウザのシークレットタブなどが立てる（API 26+）。
     */
    private fun noPersonalizedLearning(info: EditorInfo?): Boolean =
        ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /**
     * パスワード欄か。
     *
     * 音声入力はここでは一切使わせない。端末内認識で外へ出ないとしても、
     * 「パスワードを声に出す」こと自体が周囲に対して危ないため、
     * エンジンの種類によらないハードな禁止として効かせる。
     */
    private fun isPasswordField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    /** 入力履歴に積む。学習が禁止された欄では何もしない。 */
    private fun recordHistory(reading: String, surface: String, now: Long) {
        if (!learningAllowedHere) return
        prediction.record(reading, surface, now)
    }

    /** 前回使っていたモード。記録が無ければ「かな」。 */
    private fun savedMode(): InputMode {
        val name = prefs.getString(Prefs.KEY_LAST_INPUT_MODE, null) ?: return InputMode.HIRAGANA
        return runCatching { InputMode.valueOf(name) }.getOrDefault(InputMode.HIRAGANA)
    }

    /**
     * ユーザーが明示的に切り替えたモードだけを記憶する。
     * 入力欄の都合で英字にした場合は記録しない（普通の欄へ戻ったらかなに戻したいため）。
     */
    private fun persistMode() {
        prefs.edit().putString(Prefs.KEY_LAST_INPUT_MODE, inputMode.name).apply()
    }

    /** 個人テンプレートを認識器へ反映し直す。 */
    private fun refreshLearnedTemplates() {
        inputView?.refreshPersonalTemplates()
        inputView?.learnedSymbols = personalStore.learnedSymbols()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 入力ビューを畳むときにマイクを掴んだままにしない
        endVoice()
        flushComposing()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 折りたたみ / 展開の切り替えでパネル配置が変わる
        applyLayoutPrefs()
    }

    override fun onDestroy() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        // ここで save() してはいけない。IME が持っている内容が古い場合、
        // トレーニング画面などが書いた新しい内容を巻き戻してしまう。
        // 変更時に都度 save() 済みなので何もしない。
        handler.removeCallbacks(liveSuggestRunnable)
        handler.removeCallbacks(voiceNoticeRunnable)
        handler.removeCallbacks(voiceRestartRunnable)
        voice.release()
        converter.shutdown()
        onDevice?.shutdown()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 展開レイアウトのときは「フローティング IME」として振る舞う。
     *
     * - contentTopInsets / visibleTopInsets をビュー高さにして、背後のアプリを縮めない
     * - touchableRegion をパネル（＋候補バー＋つまみ）だけに限定して、
     *   パネル外のタッチを背後のアプリへ素通しする
     *
     * 展開時のビューは画面いっぱいの高さを持つ（その中でパネルが動く）ので、
     * ここで渡す高さもそのまま画面の高さになる。塗るのもタッチを取るのも
     * パネルの矩形だけなので、残りは背後のアプリがそのまま見えて操作もできる。
     *
     * 1 画面（通常のスマホ・Fold の外側）では既定のドッキング動作のままにする。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = inputView ?: return
        if (!view.expandedScreen || view.height <= 0) return

        val h = view.height
        // 背後のアプリは全画面のまま。IME はその上にかぶさるだけ。
        outInsets.contentTopInsets = h
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        view.fillTouchableRegion(outInsets.touchableRegion)
    }

    /**
     * IME ウィンドウ自体の背景を透明にする。
     * アプリのテーマが windowBackground に不透明色を指定しているため、
     * これを消さないとパネル外を透過させられない。
     */
    private fun makeWindowTransparent() {
        val w = window?.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.findViewById<View>(android.R.id.inputArea)?.let { area ->
            area.setBackgroundColor(Color.TRANSPARENT)
            (area.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun resetAll() {
        if (::learner.isInitialized) learner.onOther(System.currentTimeMillis())
        endVoice()
        handler.removeCallbacks(liveSuggestRunnable)
        cancelConversion(restoreKana = false)
        clearLiveSuggest()
        inputView?.hideOverlay()
        romaji.setLength(0)
        kana.setLength(0)
        endWord()
        clearUndo()
        shift = Shift.OFF
        tempLatin = TempLatin.NONE
        symbolMode = SymbolMode.NORMAL
        currentInputConnection?.let { clearComposing(it) }
        syncView()
    }

    // ------------------------------------------------------- symbol dispatch

    override fun onSymbol(symbol: String, stroke: List<Pt>, zone: Zone) {
        val ic = currentInputConnection ?: return
        stopVoiceForOtherInput()
        // 確定アンドゥが効くのは「確定の直後のバックスペース」だけ。
        // 他のストロークが 1 つでも挟まったら、以降は普通のバックスペースに戻す。
        if (symbol != StrokeTemplates.BACKSPACE) clearUndo()
        lastStroke = stroke
        lastZone = zone

        if (symbol == UniStrokeView.UNRECOGNIZED) {
            // 認識できなかった。記号シフト中なら 1 ストロークで解除する。
            if (symbolMode != SymbolMode.NORMAL) {
                symbolMode = SymbolMode.NORMAL
                syncView()
            }
            return
        }

        if (symbol == StrokeTemplates.TAP) {
            when (symbolMode) {
                SymbolMode.NORMAL -> {
                    symbolMode = SymbolMode.PUNCTUATION
                }
                SymbolMode.PUNCTUATION -> {
                    symbolMode = SymbolMode.NORMAL
                    emitSymbol(ic, ".")
                }
                SymbolMode.EXTENDED -> {
                    symbolMode = SymbolMode.NORMAL
                    emitSymbol(ic, "•")
                }
            }
            syncView()
            return
        }

        if (symbolMode != SymbolMode.NORMAL) {
            val mode = symbolMode
            symbolMode = SymbolMode.NORMAL
            when (symbol) {
                // 記号シフト中の右->左横線はモード解除だけ（チャート通り）
                StrokeTemplates.BACKSPACE -> Unit
                StrokeTemplates.TAB -> {
                    flushComposing()
                    commitFinal(ic, "\t")
                }
                else -> emitSymbol(ic, symbol)
            }
            syncView()
            return
        }

        when (symbol) {
            StrokeTemplates.SHIFT -> { learner.onOther(System.currentTimeMillis()); onShiftStroke() }
            // 「＼」は常に Extended Shift
            StrokeTemplates.EXT_SHIFT -> enterExtended()
            // 「／」（右上スワイプ）は abc なら Extended、かななら ひらがな ⇄ カタカナ。
            // かなモードでは右上スワイプ = カナ切替を優先し、Extended へは「＼」だけで入る。
            StrokeTemplates.EXT_SHIFT_ALT ->
                if (inputMode == InputMode.LATIN) {
                    enterExtended()
                } else {
                    learner.onOther(System.currentTimeMillis())
                    onKanaScriptToggle()
                }
            StrokeTemplates.SPACE -> { learner.onOther(System.currentTimeMillis()); onSpace(ic) }
            StrokeTemplates.BACKSPACE -> onBackspace(ic)
            StrokeTemplates.RETURN -> { learner.onOther(System.currentTimeMillis()); onReturn(ic) }
            StrokeTemplates.TAB -> {
                flushComposing()
                commitFinal(ic, "\t")
            }
            else -> onCharacter(ic, symbol)
        }
    }

    /** 直近ストロークの生データ（訂正学習に使う）。 */
    private var lastStroke: List<Pt> = emptyList()
    private var lastZone: Zone = Zone.ALPHA

    /** 文字が 1 つ入力された。訂正シーケンスの追跡に流す。 */
    private fun noteCharacterTyped(symbol: String) {
        if (symbolMode != SymbolMode.NORMAL) return
        if (lastStroke.size < 2) return
        learner.onCharacter(
            StrokeLearner.Input(symbol, lastStroke, learnerSetName()),
            System.currentTimeMillis(),
        )
    }

    private fun learnerSetName(): String =
        if (lastZone == Zone.NUMBER) PersonalTemplateStore.SET_NUMBER
        else PersonalTemplateStore.SET_ALPHA

    /** 記号（punctuation / extended）をアプリへ送る。 */
    private fun emitSymbol(ic: InputConnection, symbol: String) {
        learner.onOther(System.currentTimeMillis())
        // どちらの入力窓で書いたかで全角/半角を切り分ける。
        //   数字窓: 数字と同じ扱い。半角のまま確定し、変換にも関わらせない。
        //   かな窓: 全角にする。
        // 自動英字化中の単語は ASCII として扱っているので、記号も半角のままにする。
        val kanaMode = inputMode != InputMode.LATIN && !autoLatin && lastZone != Zone.NUMBER
        val text = if (kanaMode) toZenkakuSymbol(symbol) else symbol
        // かなモードの全角記号は「読みの一部」。確定させず合成へ足す。
        // 「ー」は読みそのもの（こーひー -> コーヒー）だし、
        // 「、」「。」「？」「！」も付けたまま変換できるのが普通の日本語 IME の動き。
        if (kanaMode && isKanaComposingSymbol(text)) {
            appendSymbolToComposing(text)
            return
        }
        finishConversionIfAny()
        flushComposing()
        commitFinal(ic, text)
    }

    /**
     * かな窓で書いた記号の全角形。
     *
     * 日本語の記号として別字になるもの（。、ー？！〜）は専用の対応で、
     * それ以外の ASCII 記号は機械的に全角形（U+FF01〜）へ写す。
     * 全角形を持たない記号（Extended の ° × ÷ など）はそのまま。
     */
    private fun toZenkakuSymbol(symbol: String): String = when (symbol) {
        "." -> "。"
        "," -> "、"
        "-" -> "ー"
        "?" -> "？"
        "!" -> "！"
        // 波ダッシュ U+301C。全角チルダ U+FF5E ではない
        // （[KANA_COMPOSING_SYMBOLS] に入っているのが U+301C なので合わせる）
        "~" -> "〜"
        else ->
            if (symbol.length == 1 && symbol[0].code in 0x21..0x7E) {
                (symbol[0].code + 0xFEE0).toChar().toString()
            } else {
                symbol
            }
    }

    /**
     * 全角記号を合成へ足す。
     *
     * 変換中（文節編集モード）だったらそこは確定してから、
     * 新しい合成の 1 文字目として記号を置く。
     * 未確定のローマ字（"k" など）は先にかなへ落としてから足す。
     */
    private fun appendSymbolToComposing(text: String) {
        finishConversionIfAny()
        mergePendingRomaji()
        kana.append(text)
        // 生の綴りにも積む。バックスペースでの巻き戻しと自動英字化の判定を揃えるため。
        wordRaw.append(text)
        updateComposing()
        scheduleLiveSuggest()
    }

    /**
     * かなモードで合成に足してよい全角の日本語記号か。
     *
     * かな窓で書いた記号は [toZenkakuSymbol] で全角になるので、
     * ここに並んだものは読みの一部として合成に残る（こーひー、〜？！ など）。
     * ここに無い全角記号（＠＃＄ など）と半角記号は従来どおり確定して挿入する。
     */
    private fun isKanaComposingSymbol(text: String): Boolean =
        text.length == 1 && KANA_COMPOSING_SYMBOLS.indexOf(text[0]) >= 0

    // -------------------------------------------------------------- 文字入力

    private fun onCharacter(ic: InputConnection, symbol: String) {
        finishConversionIfAny()

        if (inputMode == InputMode.LATIN) {
            val text = if (shift == Shift.OFF) symbol else symbol.uppercase()
            commitFinal(ic, text)
            noteCharacterTyped(symbol)
            if (shift == Shift.ONCE) {
                shift = Shift.OFF
                syncView()
            }
            return
        }

        // かなモード中の一時アルファベット入力（上スワイプ 1 回 = 大文字 / 2 回 = 小文字）。
        // 1 文字入れたら自動でかな入力へ戻る。
        if (tempLatin != TempLatin.NONE) {
            val upper = tempLatin == TempLatin.UPPER
            tempLatin = TempLatin.NONE
            flushComposing()
            val text = if (upper) symbol.uppercase() else symbol.lowercase()
            commitFinal(ic, text)
            noteCharacterTyped(symbol)
            syncView()
            return
        }

        // かな / カナ: 数字ゾーンの数字はそのまま確定
        if (symbol.length == 1 && symbol[0].isDigit()) {
            flushComposing()
            commitFinal(ic, symbol)
            noteCharacterTyped(symbol)
            return
        }

        // 生の綴りは常に残す（自動英字化の判定と、かな解釈への巻き戻しに使う）
        wordRaw.append(symbol)

        if (autoLatin) {
            // すでに英字と判定済みの単語。かな変換はせず、打った綴りをそのまま見せる。
            updateComposing()
            noteCharacterTyped(symbol)
            scheduleLiveSuggest()
            return
        }

        romaji.append(symbol)
        val katakana = inputMode == InputMode.KATAKANA
        val r = RomajiConverter.convert(romaji.toString(), katakana)
        kana.append(r.kana)
        romaji.setLength(0)
        romaji.append(r.pending)

        // 日本語のローマ字として無理がある綴りになったら、この単語だけ英字へ倒す。
        // ひらがなモードのみ（カタカナ入力は意図的な選択なので触らない）。
        if (inputMode == InputMode.HIRAGANA &&
            RomajiConverter.looksNonJapanese(wordRaw.toString())
        ) {
            autoLatin = true
            kana.setLength(0)
            romaji.setLength(0)
            syncView()
        }

        updateComposing()
        noteCharacterTyped(symbol)
        scheduleLiveSuggest()
    }

    private fun enterExtended() {
        symbolMode = SymbolMode.EXTENDED
        syncView()
    }

    /**
     * 上スワイプ（下 -> 上の縦線）。
     *
     * abc モードでは従来どおり Caps シフトの 3 段階トグル。
     * かなモードでは「一時アルファベット入力」の切替
     * （1 回 = 次の 1 文字を大文字 / 2 回 = 小文字 / 3 回 = 解除）。
     */
    private fun onShiftStroke() {
        if (inputMode == InputMode.LATIN) {
            shift = when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> Shift.LOCK
                Shift.LOCK -> Shift.OFF
            }
            syncView()
            return
        }
        tempLatin = when (tempLatin) {
            TempLatin.NONE -> TempLatin.UPPER
            TempLatin.UPPER -> TempLatin.LOWER
            TempLatin.LOWER -> TempLatin.NONE
        }
        syncView()
    }

    /**
     * 右上スワイプ（左下 -> 右上の「／」）。かなモードでの ひらがな ⇄ カタカナ。
     * 音節の途中なので未確定ローマ字（"k" など）は捨てずに持ち越す。
     */
    private fun onKanaScriptToggle() {
        tempLatin = TempLatin.NONE
        finishConversionIfAny()
        val carry = romaji.toString()
        romaji.setLength(0)
        flushComposing()
        inputMode = if (inputMode == InputMode.KATAKANA) InputMode.HIRAGANA else InputMode.KATAKANA
        persistMode()
        romaji.append(carry)
        updateComposing()
        syncView()
    }

    private fun onSpace(ic: InputConnection) {
        if (converting) {
            cycleCandidate()
            return
        }
        // 確定かなが無い（未確定子音だけ）状態では変換要求を投げない。
        // 表示上かなになっている未確定分（"nn" -> 「ん」）は「かながある」とみなす。
        if (inputMode == InputMode.HIRAGANA && readingForConversion().isNotEmpty()) {
            // ひらがな合成中のスペースは「変換」
            mergePendingRomaji()
            updateComposing()
            requestConversion()
            return
        }
        flushComposing()
        commitFinal(ic, " ")
    }

    private fun onBackspace(ic: InputConnection) {
        if (converting) {
            cancelConversion(restoreKana = true)
            return
        }
        // 確定直後の 1 回目は「確定を取り消して確定前へ戻す」。
        // 意図しない文節で確定してしまったとき、消してから打ち直さずに直せる。
        // 2 回目以降は復元された composing の末尾から 1 文字ずつ消える（従来動作）。
        if (composingLength() == 0 && lastCommit != null) {
            if (undoLastCommit(ic)) {
                learner.onOther(System.currentTimeMillis())
                return
            }
        }
        if (autoLatin) {
            // 自動英字化中は生の綴りを 1 文字だけ戻す。
            // 戻した結果もう「日本語として無理」でなくなったら、かな解釈へ復帰する。
            if (wordRaw.isNotEmpty()) wordRaw.setLength(wordRaw.length - 1)
            learner.onUndoLastCharacter(System.currentTimeMillis())
            if (wordRaw.isEmpty() || !RomajiConverter.looksNonJapanese(wordRaw.toString())) {
                revertAutoLatin()
            } else {
                updateComposing()
                scheduleLiveSuggest()
            }
            return
        }
        if (romaji.isNotEmpty()) {
            // ローマ字 1 文字 = 直前のストローク 1 つぶん。訂正として追跡できる。
            romaji.setLength(romaji.length - 1)
            if (wordRaw.isNotEmpty()) wordRaw.setLength(wordRaw.length - 1)
            learner.onUndoLastCharacter(System.currentTimeMillis())
            updateComposing()
            scheduleLiveSuggest()
            return
        }
        if (kana.isNotEmpty()) {
            // かな 1 文字は複数ストロークに対応しうるので、どのストロークの訂正か特定できない。
            // 生ローマ字との対応も崩れるので、単語の履歴はここで捨てる。
            kana.setLength(kana.length - 1)
            endWord()
            learner.onOther(System.currentTimeMillis())
            updateComposing()
            scheduleLiveSuggest()
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            commitFinal(ic, "")
            learner.onOther(System.currentTimeMillis())
        } else {
            ic.deleteSurroundingText(1, 0)
            // 英字モードは 1 コミット = 1 ストロークなので訂正として追跡できる
            if (inputMode == InputMode.LATIN) {
                learner.onUndoLastCharacter(System.currentTimeMillis())
            } else {
                learner.onOther(System.currentTimeMillis())
            }
        }
    }

    /**
     * リターンストローク。一般的な IME の Enter と同じ二段構えにする。
     *
     *   合成中・変換中 -> **確定だけ**（改行は送らない）
     *   合成が空       -> 改行（または入力欄が要求するアクション）
     *
     * 確定と改行を同時に行うと、変換を確定したいだけなのに行が変わってしまう。
     * 「1 回目で確定、2 回目で改行」が期待される動作。
     */
    private fun onReturn(ic: InputConnection) {
        if (converting) {
            // 変換中のリターンは「全文節を現在の選択で確定」
            finishConversionIfAny()
            return
        }
        if (composingLength() > 0) {
            // 合成中は確定のみ。改行したければもう一度リターンを書く。
            flushComposing()
            return
        }
        // 合成が空なので、ここは純粋な改行。合成領域だけ閉じてから送る。
        flushComposing()
        sendEnter(ic)
    }

    /**
     * 改行を送る。
     *
     * 入力欄が「検索」「送信」などのアクションを要求している場合は、
     * 生の Enter ではなくそのアクションを実行する（検索欄で改行だけ入って
     * 検索が走らない、という状態を避ける）。
     * [EditorInfo.IME_FLAG_NO_ENTER_ACTION] が立っている欄は改行が正解なので従う。
     */
    private fun sendEnter(ic: InputConnection) {
        // 改行・エディタアクションは commitFinal を通らない文書の変更なので、
        // ここで確定アンドゥを無効にする（記録とアプリ側の実体をずらさない）。
        clearUndo()
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
            return
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // ------------------------------------------------------------- composing

    /**
     * 変換・予測に使う読み。
     *
     * 確定済みのかな（[kana]）に加えて、**表示上すでにかなになっている未確定分**
     * （"nn" -> 「ん」）も含める。これを含めないと、画面に「しゃけん」と出ているのに
     * 「しゃけ」を変換してしまい、確定後に「ん」だけが取り残される。
     */
    private fun readingForConversion(): String =
        kana.toString() +
            RomajiConverter.settledPending(romaji.toString(), inputMode == InputMode.KATAKANA)

    private fun composingLength(): Int =
        if (autoLatin) wordRaw.length else kana.length + romaji.length

    /**
     * 単語の区切り。生ローマ字の履歴と自動英字化の状態を捨てる。
     * 次の単語の判定が前の単語に引きずられないようにするためのもの。
     */
    private fun endWord() {
        wordRaw.setLength(0)
        autoLatin = false
    }

    /**
     * 自動英字化を解除して、いまの単語をかな解釈へ戻す。
     * 生ローマ字は保ったままなので、続けて打てばまた判定が走る。
     */
    private fun revertAutoLatin() {
        if (!autoLatin) return
        autoLatin = false
        val katakana = inputMode == InputMode.KATAKANA
        val r = RomajiConverter.convert(wordRaw.toString(), katakana)
        kana.setLength(0)
        kana.append(r.kana)
        romaji.setLength(0)
        romaji.append(r.pending)
        updateComposing()
        syncView()
        scheduleLiveSuggest()
    }

    /** 未確定ローマ字を強制的にかなへ落とし込む。 */
    private fun mergePendingRomaji() {
        if (romaji.isEmpty()) return
        kana.append(RomajiConverter.flush(romaji.toString(), inputMode == InputMode.KATAKANA))
        romaji.setLength(0)
    }

    /**
     * 合成領域を空にして合成を終える。
     *
     * finishComposingText() は合成領域の中身を **確定してしまう** ので、
     * 単体で呼ぶと未確定だった子音（"k" など）がアプリ側に残ってしまう。
     * 必ず setComposingText("") で中身を消してから終了すること。
     */
    private fun clearComposing(ic: InputConnection) {
        // 合成領域を出していないなら InputConnection には一切触らない。
        //
        //   setComposingText("") … 合成領域が無いと「選択範囲の置換」として働くので、
        //                          アプリ側で選択中のテキストを消してしまう
        //   finishComposingText() … 合成領域があるとその中身を**確定してしまう**
        //
        // 後者が [composingShown] の取りこぼしと組み合わさると、
        // 合成中の文字列が確定側へ漏れ、次の setComposingText が新規挿入になって
        // 「バックスペースのたびに 1 文字ずつ増える」という壊れ方をする。
        if (!composingShownOn(ic)) {
            // 出していない、または別の入力欄に対して出した記録。
            // この接続には一切触らず、記録だけ落とす。
            noteComposingGone()
            return
        }
        // 選択範囲があるなら合成領域は存在しないはず（Android では両立しない）。
        // ここで setComposingText("") を送ると選択中のテキストを消してしまうので、
        // 「記録の方がずれている」と判断して何も送らない。
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) {
            noteComposingGone()
            return
        }
        ic.setComposingText("", 1)
        noteComposingGone()
        ic.finishComposingText()
    }

    /** [ic] に対して合成領域を出している、と記録しているか。 */
    private fun composingShownOn(ic: InputConnection): Boolean =
        composingShown && composingOwner === ic

    /**
     * 合成領域がもう無い（コミットされた・接続が変わった）ことを記録する。
     * InputConnection には触らないので、いつ呼んでも破壊的にならない。
     */
    private fun noteComposingGone() {
        composingShown = false
        composingOwner = null
    }

    /**
     * 合成領域を [text] にする。**合成領域を書き換えるのは必ずこの経路を通すこと。**
     *
     * [composingShown] は「いま合成領域を出しているか」を表す唯一の記録で、
     * これがずれると [clearComposing] が合成中の文字列を確定させてしまう。
     * 直接 setComposingText を呼ぶと更新漏れが起きるので、書き手を 1 つに絞っている。
     */
    private fun showComposing(ic: InputConnection, text: CharSequence) {
        if (text.isEmpty()) {
            clearComposing(ic)
            return
        }
        ic.setComposingText(text, 1)
        composingShown = true
        composingOwner = ic
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        // 自動英字化中は打った ASCII をそのまま見せる（かなには一切触らない）
        val text = if (autoLatin) {
            wordRaw.toString()
        } else {
            kana.toString() +
                RomajiConverter.preview(romaji.toString(), inputMode == InputMode.KATAKANA)
        }
        showComposing(ic, text)
    }

    /**
     * アプリへ文字列を確定する **唯一の経路**。ic.commitText を直接呼んではいけない。
     *
     * ここに一本化しているのは、確定のたびに「直前の確定」（[lastCommit]）を
     * 必ず書き直すため。[undoReading] を渡した確定だけがバックスペース 1 回で
     * 戻せる対象になり、渡さなかった確定は直前の記録を **捨てる**。
     *
     * これが無いと、たとえば
     *   flushComposing()  … 「こんにちは」を確定して lastCommit=(こんにちは, こんにちは)
     *   commitText("、")   … 呼び出し側が記号をもう 1 文字確定（記録は更新されない）
     * の並びで記録とアプリ側の実体が 1 文字ずれ、次のバックスペースで
     * [undoLastCommit] が消しすぎ + 読みの挿し直しをして文字が増える。
     *
     * 合成領域はコミットで消費されるので、記録も同時に落とす。
     */
    private fun commitFinal(ic: InputConnection, text: CharSequence, undoReading: String? = null) {
        ic.commitText(text, 1)
        noteComposingGone()
        if (undoReading.isNullOrEmpty()) {
            clearUndo()
        } else {
            noteCommit(text.toString(), undoReading)
        }
    }

    /** 合成中の文字列をそのまま確定する。 */
    private fun flushComposing() {
        val ic = currentInputConnection ?: return
        handler.removeCallbacks(liveSuggestRunnable)
        clearLiveSuggest()
        if (composingLength() == 0) {
            clearComposing(ic)
            endWord()
            return
        }
        if (autoLatin) {
            // 自動英字化中の確定は、打った綴りをそのまま出す（履歴には積まない）
            val raw = wordRaw.toString()
            endWord()
            kana.setLength(0)
            romaji.setLength(0)
            // 生の綴りをそのまま出した確定は戻す先（読み）が無いので undo にしない
            commitFinal(ic, raw)
            syncView()
            return
        }
        mergePendingRomaji()
        val text = kana.toString()
        kana.setLength(0)
        if (inputMode == InputMode.HIRAGANA) {
            recordHistory(text, text, System.currentTimeMillis())
        }
        endWord()
        // ひらがなをそのまま確定した場合、戻す先は同じ文字列
        commitFinal(ic, text, text)
    }

    // --------------------------------------------------------------- 漢字変換

    /**
     * 合成内容が変わったときに呼ぶ。
     * 古い候補は即座に消し（読みとズレた候補をタップさせないため）、
     * 最後の変化から [LIVE_SUGGEST_DELAY_MS] 後に 1 回だけリクエストする。
     */
    private fun scheduleLiveSuggest() {
        handler.removeCallbacks(liveSuggestRunnable)
        clearLiveSuggest()
        // 自動候補はひらがなモードのみ。文節編集中は出さない。
        if (inputMode != InputMode.HIRAGANA || converting) return
        // 自動英字化中は「綴り」と「かな解釈」の 2 件だけ。通信も端末内変換も要らない。
        if (autoLatin) {
            rebuildCandidates()
            return
        }
        // かなが 1 文字でも確定していれば即リクエストする（短い読みでも抑制しない）。
        // 末尾に未確定子音（"か" + "k" など）が残っていても、確定済みの "か" だけで投げる。
        if (readingForConversion().isEmpty()) return
        // 履歴・内蔵辞書はオフラインで即答できるので、待たずに出す
        rebuildCandidates()
        handler.postDelayed(liveSuggestRunnable, LIVE_SUGGEST_DELAY_MS)
    }

    private fun clearLiveSuggest() {
        candidatesFromOnDevice = false
        if (liveCandidates.isEmpty() && liveSegments.isEmpty() && suggestResults.isEmpty()) return
        liveReading = ""
        liveSegments = emptyList()
        liveCandidates = emptyList()
        suggestReading = ""
        suggestResults = emptyList()
        syncCandidateBar()
    }

    /**
     * 確定済みかな部分（[kana]）だけで変換をかける。
     * 末尾の未確定ローマ字は読みに含めない。
     */
    private fun requestLiveSuggest() {
        val reading = readingForConversion()
        if (reading.isEmpty()) return
        // オプトインしていない、送ってはいけない欄、「端末内のみ」設定 ―― どれでも通信しない。
        // その場合は端末内変換がそのまま変換候補を作る。
        if (!networkConvertOk) {
            requestOnDeviceSuggest(reading)
            return
        }
        converter.convert(reading) { result ->
            if (converting) return@convert
            if (readingForConversion() != reading) return@convert
            if (result == null) {
                // 通信の失敗・タイムアウトはここへ来る。黙って端末内へ落とす。
                requestOnDeviceSuggest(reading)
                return@convert
            }
            liveReading = reading
            liveSegments = result
            candidatesFromOnDevice = false
            rebuildCandidates()
        }
        converter.suggest(reading) { result ->
            if (result == null || converting) return@suggest
            if (readingForConversion() != reading) return@suggest
            suggestReading = reading
            suggestResults = result
            rebuildCandidates()
        }
    }

    /**
     * 端末内辞書だけで自動変換候補を作る。
     * 変換自体は別スレッドで走り、結果はメインスレッドへ返ってくる。
     */
    private fun requestOnDeviceSuggest(reading: String) {
        val engine = onDevice ?: return
        engine.convert(reading) { result ->
            if (result == null || result.isEmpty() || converting) return@convert
            if (readingForConversion() != reading) return@convert
            liveReading = reading
            liveSegments = result
            candidatesFromOnDevice = true
            rebuildCandidates()
        }
    }

    /**
     * 候補バーの中身を組み立て直す。
     *
     * 並び順は
     *   [履歴予測] -> [フレーズ辞書予測] -> [端末内辞書予測] -> [変換候補]
     *   -> [Suggest 予測] -> [ひらがな/カタカナ]
     * すべて「読み全体を置き換える文字列」なので、どれをタップしても全文確定できる。
     * 端末内辞書の予測は、通信していないとき（＝それが予測の主力になるとき）だけ足す。
     */
    private fun rebuildCandidates() {
        // 自動英字化中は「打った綴り」と「かな解釈」の 2 つだけ出す。
        // かな解釈をタップすると、確定せずにかな入力へ戻る（誤判定の逃げ道）。
        if (autoLatin) {
            val raw = wordRaw.toString()
            if (raw.isEmpty()) {
                if (liveCandidates.isNotEmpty()) {
                    liveCandidates = emptyList()
                    syncCandidateBar()
                }
                return
            }
            val kanaRead = RomajiConverter.flush(raw)
            val cands = ArrayList<PredictionEngine.Candidate>(2)
            cands.add(PredictionEngine.Candidate(raw, raw, PredictionEngine.Source.RAW))
            if (kanaRead.isNotEmpty() && kanaRead != raw) {
                cands.add(PredictionEngine.Candidate(raw, kanaRead, PredictionEngine.Source.RAW))
            }
            liveCandidates = cands
            candidatesFromOnDevice = false
            syncCandidateBar()
            return
        }

        val reading = readingForConversion()
        if (reading.isEmpty() || converting || inputMode != InputMode.HIRAGANA) {
            if (liveCandidates.isNotEmpty()) {
                liveCandidates = emptyList()
                syncCandidateBar()
            }
            return
        }

        val out = ArrayList<PredictionEngine.Candidate>()
        val seen = HashSet<String>()
        fun add(c: PredictionEngine.Candidate) {
            if (out.size >= MAX_LIVE_CANDIDATES) return
            if (c.surface.isEmpty()) return
            if (seen.add(c.surface)) out.add(c)
        }

        // 1) 履歴 + 2) 内蔵辞書（オフラインで即時）
        for (c in prediction.predictLocal(reading, System.currentTimeMillis())) add(c)

        // 2') 端末内辞書の前方一致予測。通信できないときの予測はここが主力になる。
        if (!networkConvertOk) {
            onDevice?.predict(reading, ON_DEVICE_PREDICTIONS)?.forEach { add(it) }
        }

        // 3) 変換候補（transliterate の結果）
        if (liveReading == reading && liveSegments.isNotEmpty()) {
            val segs = liveSegments
            fun full(index: Int, alt: String): String = buildString {
                for (i in segs.indices) append(if (i == index) alt else segs[i].candidates[0])
            }
            add(PredictionEngine.Candidate(reading, full(-1, ""), PredictionEngine.Source.CONVERSION))
            for (c in segs[0].candidates) {
                add(PredictionEngine.Candidate(reading, full(0, c), PredictionEngine.Source.CONVERSION))
            }
            if (segs.size > 1) {
                for (c in segs[1].candidates) {
                    add(PredictionEngine.Candidate(reading, full(1, c), PredictionEngine.Source.CONVERSION))
                }
            }
        }

        // 4) Google Suggest（ノイズを含むので下位）
        if (suggestReading == reading) {
            for (c in suggestResults) {
                add(PredictionEngine.Candidate(readingOf(c, reading), c, PredictionEngine.Source.SUGGEST))
            }
        }

        // 5) 素のひらがな / カタカナ
        add(PredictionEngine.Candidate(reading, reading, PredictionEngine.Source.RAW))
        add(
            PredictionEngine.Candidate(
                reading, RomajiConverter.toKatakana(reading), PredictionEngine.Source.RAW,
            ),
        )

        liveCandidates = out
        syncCandidateBar()
    }

    /** Suggest 結果がかな主体ならそれ自体を読みとして扱う。 */
    private fun readingOf(surface: String, fallback: String): String =
        if (surface.all { it in 'ぁ'..'ゖ' || it == 'ー' }) surface else fallback

    /** 自動候補をタップしたときの全文確定。 */
    private fun commitLiveCandidate(candidate: PredictionEngine.Candidate) {
        val ic = currentInputConnection ?: return
        // 自動英字化を誤判定だったと訂正するタップ。確定せずにかな入力へ戻す。
        if (autoLatin && candidate.surface != wordRaw.toString()) {
            revertAutoLatin()
            return
        }
        if (autoLatin) {
            handler.removeCallbacks(liveSuggestRunnable)
            val raw = wordRaw.toString()
            endWord()
            liveCandidates = emptyList()
            liveSegments = emptyList()
            liveReading = ""
            kana.setLength(0)
            romaji.setLength(0)
            commitFinal(ic, raw)
            updateComposing()
            syncCandidateBar()
            syncView()
            return
        }
        val text = candidate.surface
        handler.removeCallbacks(liveSuggestRunnable)
        // 予測を採用したら、その読み全体を履歴に積む（次からもっと早く出る）
        recordHistory(candidate.reading, text, System.currentTimeMillis())
        liveReading = ""
        liveSegments = emptyList()
        liveCandidates = emptyList()
        suggestReading = ""
        suggestResults = emptyList()
        kana.setLength(0)
        // 候補を選んだ時点でその語は完結しているので、未確定ローマ字は**捨てる**。
        //
        //   "nn"（すでに「ん」として見えている分）… 読みに含めて確定済み -> 捨ててよい
        //   "k" / "ky"（まだかなになっていない子音）… 候補には入っていない
        //
        // 後者を合成として持ち越すと、確定した語のうしろに "k" が residue として残る。
        // latin として commit もしない（ユーザーは候補を選んだのであって k は打ち終えていない）。
        romaji.setLength(0)
        endWord()
        // 合成領域（かな + 未確定ローマ字）を候補で置き換える
        commitFinal(ic, text, candidate.reading)
        // 未確定は捨てたので、合成領域は空になる
        updateComposing()
        syncCandidateBar()
    }

    private fun requestConversion() {
        val reading = readingForConversion()
        if (reading.isEmpty()) return
        // 保留中の自動候補リクエストが明示変換を打ち消さないように止める
        handler.removeCallbacks(liveSuggestRunnable)
        // 自動候補で同じ読みの結果を持っていれば、通信せずそのまま文節編集モードへ
        if (liveSegments.isNotEmpty() && liveReading == reading) {
            val segs = liveSegments
            clearLiveSuggest()
            startConversion(segs)
            return
        }
        val wasOnDevice = candidatesFromOnDevice
        clearLiveSuggest()
        candidatesFromOnDevice = wasOnDevice
        if (!networkConvertOk) {
            startLocalConversion(reading)
            return
        }
        converter.convert(reading) { result ->
            // 応答が返るまでに合成内容が変わっていたら破棄する
            if (converting || readingForConversion() != reading) return@convert
            if (result == null) {
                // 通信の失敗・タイムアウト。端末内変換で文節編集モードへ入る。
                startLocalConversion(reading)
                return@convert
            }
            candidatesFromOnDevice = false
            startConversion(result)
        }
    }

    /**
     * ネット変換を使えない（使わない）ときのスペース変換。
     *
     * 端末内変換が使えるならそれで文節に切る（《 》での文節移動もそのまま効く）。
     * 辞書を開けない端末では従来どおり、履歴・内蔵辞書・ひらがな・カタカナを
     * 「1 文節ぶんの候補」として組んで文節編集モードへ入る。
     */
    private fun startLocalConversion(reading: String) {
        val engine = onDevice
        if (engine != null) {
            candidatesFromOnDevice = true
            engine.convert(reading) { result ->
                if (converting || readingForConversion() != reading) return@convert
                if (result.isNullOrEmpty()) {
                    startFallbackConversion(reading)
                } else {
                    startConversion(result)
                }
            }
            return
        }
        startFallbackConversion(reading)
    }

    /** 端末内辞書も無いときの最後の受け皿。 */
    private fun startFallbackConversion(reading: String) {
        val cands = ArrayList<String>()
        for (c in prediction.predictLocal(reading, System.currentTimeMillis())) {
            if (c.surface.isNotEmpty() && c.surface !in cands) cands.add(c.surface)
        }
        // 素のひらがな・カタカナは必ず選べるようにする
        if (reading !in cands) cands.add(reading)
        val katakana = RomajiConverter.toKatakana(reading)
        if (katakana !in cands) cands.add(katakana)
        startConversion(listOf(GoogleConvertClient.Segment(reading, cands)))
    }

    private fun startConversion(result: List<GoogleConvertClient.Segment>) {
        segments = result
        choices = IntArray(result.size)
        activeSegment = 0
        updateConversionComposing()
        syncCandidateBar()
    }

    private fun currentConversionText(): CharSequence {
        val sb = SpannableStringBuilder()
        for (i in segments.indices) {
            val start = sb.length
            sb.append(segments[i].candidates[choices[i]])
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i == activeSegment) {
                sb.setSpan(
                    BackgroundColorSpan(HIGHLIGHT),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return sb
    }

    private fun updateConversionComposing() {
        val ic = currentInputConnection ?: return
        showComposing(ic, currentConversionText())
    }

    private fun syncCandidateBar() {
        val view = inputView ?: return
        view.onDeviceChip = candidatesFromOnDevice
        if (converting) {
            // 文節編集モード: その文節の候補 + 文節送り
            view.setCandidates(
                segments[activeSegment].candidates,
                choices[activeSegment],
                withSegmentNav = true,
            )
            view.statusLabel = buildStatusLabel()
            return
        }
        if (liveCandidates.isNotEmpty()) {
            // 自動候補: 全文候補のみ。選択ハイライトも文節送りも出さない。
            view.setCandidates(liveCandidates.map { it.surface }, selected = -1, withSegmentNav = false)
            view.statusLabel = buildStatusLabel()
            return
        }
        view.clearCandidates()
        view.statusLabel = buildStatusLabel()
    }

    private fun cycleCandidate() {
        if (!converting) return
        val cands = segments[activeSegment].candidates
        choices[activeSegment] = (choices[activeSegment] + 1) % cands.size
        updateConversionComposing()
        inputView?.setCandidateSelection(choices[activeSegment])
    }

    /** 変換中なら現在の選択で確定する。 */
    private fun finishConversionIfAny() {
        if (!converting) return
        val text = StringBuilder()
        val reading = StringBuilder()
        for (i in segments.indices) {
            text.append(segments[i].candidates[choices[i]])
            reading.append(segments[i].reading)
        }
        val now = System.currentTimeMillis()
        // 全文と、文節が複数あれば各文節も覚える
        recordHistory(reading.toString(), text.toString(), now)
        if (segments.size > 1) {
            for (i in segments.indices) {
                recordHistory(segments[i].reading, segments[i].candidates[choices[i]], now)
            }
        }
        segments = emptyList()
        choices = IntArray(0)
        activeSegment = 0
        kana.setLength(0)
        romaji.setLength(0)
        endWord()
        val ic = currentInputConnection
        if (ic != null) {
            // 戻す先は全文節の読みを繋いだひらがな
            commitFinal(ic, text.toString(), reading.toString())
        } else {
            // 接続が無いのでアプリ側には何も入っていない。
            // ここで確定を記録すると、次に繋がった入力欄で「存在しない確定」を
            // 取り消そうとしてテキストを壊す。記録は持たない。
            noteComposingGone()
            clearUndo()
        }
        syncCandidateBar()
    }

    /**
     * 変換をやめる。[restoreKana] が true ならひらがな合成状態に戻す
     * （false は入力先が変わったときなどの単純破棄）。
     */
    private fun cancelConversion(restoreKana: Boolean) {
        converter.cancel()
        onDevice?.cancel()
        if (segments.isEmpty()) {
            if (!restoreKana) syncCandidateBar()
            return
        }
        segments = emptyList()
        choices = IntArray(0)
        activeSegment = 0
        syncCandidateBar()
        if (restoreKana) {
            updateComposing()
            // ひらがな合成に戻ったので自動候補を出し直す
            scheduleLiveSuggest()
        }
    }

    // ------------------------------------------------------------ view 連携

    /** abc ⇄ かな のトグル（カタカナへはかなモード中のシフトストロークで）。 */
    override fun onModeToggle() {
        stopVoiceForOtherInput()
        learner.onOther(System.currentTimeMillis())
        finishConversionIfAny()
        clearUndo()
        flushComposing()
        handler.removeCallbacks(liveSuggestRunnable)
        clearLiveSuggest()
        inputMode = if (inputMode == InputMode.LATIN) InputMode.HIRAGANA else InputMode.LATIN
        persistMode()
        shift = Shift.OFF
        tempLatin = TempLatin.NONE
        symbolMode = SymbolMode.NORMAL
        syncView()
    }

    override fun onCursorMove(forward: Boolean) {
        stopVoiceForOtherInput()
        moveCursor(forward)
    }

    /**
     * カーソルを 1 つ動かす。
     * ボイスコマンドからも呼ぶので、音声入力を止める処理は呼び出し側に置く。
     */
    private fun moveCursor(forward: Boolean) {
        // 合成中はカーソル移動と競合するので、先に確定してから動かす
        finishConversionIfAny()
        clearUndo()
        flushComposing()
        sendDownUpKeyEvents(
            if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
        )
    }

    /**
     * 全選択ボタンの長押し。直前に確定した文字列だけを選択する。
     *
     * 確定アンドゥ用に持っている「最後の確定」をそのまま使う。
     * 確定のあとにカーソル移動や別の入力が挟まっていれば無効になっているので、
     * そのときは素直に全選択へ落とす。
     */
    override fun onSelectLastCommit() {
        val ic = currentInputConnection ?: return
        stopVoiceForOtherInput()
        finishConversionIfAny()
        // 長押しの前にすでに選択が残っていれば、そこで消す（タップと同じ扱い）
        if (deleteOwnSelection(ic)) {
            clearUndo()
            return
        }
        val undo = lastCommit
        if (undo != null && composingLength() == 0 && selectLastCommit(ic, undo.surface)) {
            // selectLastCommit が ownSelection を立てているので、ここでは捨てない
            lastCommit = null
            return
        }
        clearUndo()
        ic.performContextMenuAction(android.R.id.selectAll)
    }

    /**
     * カーソルの直前 [length] 文字を選択する。選択できたら true。
     *
     * 選択したままストロークを書くと、InputConnection の既定動作で
     * 選択範囲が置き換わる（＝書き直しになる）。
     */
    private fun selectLastCommit(ic: InputConnection, text: String): Boolean {
        if (text.isEmpty()) return false
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val cursor = extracted.startOffset + extracted.selectionEnd
        val start = cursor - text.length
        if (start < 0 || extracted.selectionStart != extracted.selectionEnd) return false
        // 直前が本当にその文字列か確かめてから選択する（別アプリが書き換えている場合の保険）
        val before = ic.getTextBeforeCursor(text.length, 0)
        if (before?.toString() != text) return false
        if (!ic.setSelection(start, cursor)) return false
        // 続けてタップされたらこの範囲を消してよい
        ownSelection = start to cursor
        return true
    }

    /**
     * 全選択ボタンのタップ。
     *
     * 1 回目は全選択、**すでに選択されている状態なら 2 回目で削除**する。
     * 消す対象は「テキスト全体が選択されている」か「長押しでこちらが選択した範囲」
     * のどちらかに限る。ユーザーがアプリ側で自分で選んだ範囲まで消してしまうと、
     * 全選択のつもりが消去になって取り返しがつかないため。
     */
    override fun onSelectAll() {
        stopVoiceForOtherInput()
        selectAllOrDelete()
    }

    /**
     * 全選択（すでに選択されていれば削除）。
     * ボイスコマンドからも呼ぶので、音声入力を止める処理は呼び出し側に置く。
     */
    private fun selectAllOrDelete() {
        val ic = currentInputConnection ?: return
        finishConversionIfAny()
        flushComposing()
        // 選択の判定は clearUndo より先に行う（clearUndo が ownSelection を捨てるため）
        val deleted = deleteOwnSelection(ic)
        clearUndo()
        if (deleted) return
        ic.performContextMenuAction(android.R.id.selectAll)
    }

    /**
     * 選択範囲がこちらの意図したもの（全体 or 長押しで選んだ範囲）なら削除して true。
     * それ以外（選択が無い・ユーザーが自分で選んだ範囲）なら何もせず false。
     */
    private fun deleteOwnSelection(ic: InputConnection): Boolean {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (end <= start) return false
        val length = extracted.text?.length ?: return false
        val whole = start == 0 && end >= length
        val mine = ownSelection?.let { it.first == start && it.second == end } == true
        if (!whole && !mine) return false
        ownSelection = null
        // 選択範囲を空文字で置き換える = 削除
        commitFinal(ic, "")
        return true
    }

    override fun onCandidateSelected(index: Int) {
        stopVoiceForOtherInput()
        if (!converting) {
            // 自動候補は全文確定
            if (index in liveCandidates.indices) commitLiveCandidate(liveCandidates[index])
            return
        }
        if (index !in segments[activeSegment].candidates.indices) return
        choices[activeSegment] = index
        if (activeSegment < segments.size - 1) {
            activeSegment++
            updateConversionComposing()
            syncCandidateBar()
        } else {
            finishConversionIfAny()
        }
    }

    override fun onOpenSettings() {
        // 設定画面へ移るあいだ録音を続けない
        stopVoiceForOtherInput()
        // IME サービスからの起動なので NEW_TASK が必須
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onSegmentStep(forward: Boolean) {
        stopVoiceForOtherInput()
        if (!converting) return
        val next = activeSegment + if (forward) 1 else -1
        if (next !in segments.indices) return
        activeSegment = next
        updateConversionComposing()
        syncCandidateBar()
    }

    // ------------------------------------------------------------- 音声入力

    /**
     * 手書きゾーンに「長押しで音声入力」を出すか（＝長押しを受け付けるか）。
     *
     * 設定・入力欄・端末の対応状況の 3 つが揃ったときだけ出す。
     * 使えない端末で案内だけ出しても、長押ししたユーザーを空振りさせるだけなので出さない。
     */
    private fun syncVoiceAvailability() {
        inputView?.voiceAvailable =
            Prefs.isVoiceInputEnabled(this) && voiceAllowedHere && voiceUsableOnThisDevice()
    }

    /** この端末・この設定で音声認識を始められるか（マイクの許可はここでは見ない）。 */
    private fun voiceUsableOnThisDevice(): Boolean =
        voice.onDeviceAvailable() ||
            (!Prefs.isVoiceOnDeviceOnly(this) && voice.serviceAvailable())

    /** 手書きゾーンの長押し。 */
    override fun onVoiceStart() {
        if (voiceActive) return
        if (currentInputConnection == null) return
        if (!Prefs.isVoiceInputEnabled(this)) return
        if (!voiceAllowedHere) {
            showVoiceNotice(getString(R.string.voice_blocked_field))
            return
        }
        when (voice.check()) {
            VoiceInput.Ready.ON_DEVICE -> beginVoice(onDevice = true)

            VoiceInput.Ready.SERVICE ->
                // 音声を端末外へ出す経路は、ネット変換とまったく同じ基準で欄ごとに止める
                if (netConvertAllowedHere) {
                    beginVoice(onDevice = false)
                } else {
                    showVoiceNotice(getString(R.string.voice_blocked_field_service))
                }

            VoiceInput.Ready.NEED_PERMISSION -> {
                showVoiceNotice(getString(R.string.voice_need_permission))
                requestMicPermission()
            }

            VoiceInput.Ready.NO_ON_DEVICE -> showVoiceNotice(getString(R.string.voice_no_ondevice))
            VoiceInput.Ready.NO_SERVICE -> showVoiceNotice(getString(R.string.voice_no_service))
        }
    }

    /**
     * 録音を始める。書きかけは先に確定させてから始めるので、
     * 音声で入った文字列がローマ字合成の途中に割り込むことはない。
     */
    private fun beginVoice(onDevice: Boolean) {
        learner.onOther(System.currentTimeMillis())
        if (converting) finishConversionIfAny() else flushComposing()
        handler.removeCallbacks(liveSuggestRunnable)
        clearLiveSuggest()
        clearUndo()
        inputView?.hideOverlay()
        symbolMode = SymbolMode.NORMAL
        tempLatin = TempLatin.NONE
        syncView()

        voiceActive = true
        voiceOnDevice = onDevice
        voiceContinuous = Prefs.isVoiceContinuous(this)
        voiceSilentRounds = 0
        lastVoiceCommit = null
        inputView?.voiceContinuous = voiceContinuous
        showVoiceBanner(UniStrokeView.VoiceState.LISTENING, listeningMessage())
        voice.start(voiceCallback)
    }

    /** いま録音中であることの表示。どのエンジンで聞いているかを必ず出す。 */
    private fun listeningMessage(): String = getString(
        when {
            voiceOnDevice && voiceContinuous -> R.string.voice_listening_ondevice_continuous
            voiceOnDevice -> R.string.voice_listening_ondevice
            voiceContinuous -> R.string.voice_listening_service_continuous
            else -> R.string.voice_listening_service
        },
    )

    private val voiceCallback = object : VoiceInput.Callback {

        override fun onVoiceReady() = Unit

        /** 端末内で始めたが端末のサービスへ切り替わった。表示も実態に合わせる。 */
        override fun onVoiceEngineChanged(onDevice: Boolean) {
            if (!voiceActive) return
            voiceOnDevice = onDevice
            showVoiceBanner(UniStrokeView.VoiceState.LISTENING, listeningMessage())
        }

        override fun onVoiceLevel(rms: Float) {
            inputView?.setVoiceLevel(rms)
        }

        override fun onVoicePartial(text: String) {
            if (!voiceActive) return
            // 聞き取り途中は未確定表示（下線付き）で見せるだけ。確定は onVoiceResult のみ。
            currentInputConnection?.let { showComposing(it, text) }
        }

        override fun onVoiceWorking() {
            if (!voiceActive) return
            showVoiceBanner(
                UniStrokeView.VoiceState.WORKING,
                getString(R.string.voice_working),
            )
        }

        override fun onVoiceResult(text: String) {
            val ic = currentInputConnection
            if (ic == null) {
                // 入力欄がもう無い。合成領域の記録だけ落として捨てる。
                voiceActive = false
                noteComposingGone()
                hideVoiceBanner()
                return
            }
            voiceSilentRounds = 0

            // 発話まるごとがコマンドなら、文字にせず操作として実行する
            val command = if (Prefs.isVoiceCommandsEnabled(this@UniStrokeIME)) {
                VoiceCommands.match(text)
            } else {
                null
            }
            if (command != null) {
                // 聞き取り途中に出していた未確定表示（コマンドの読み）は残さない
                clearComposing(ic)
                inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (runVoiceCommand(command, ic)) return
                continueOrFinishVoice()
                return
            }

            // 音声認識の結果は既に変換済みの文字列で、対応する読みが無い。
            // 確定アンドゥ（バックスペース 1 回で戻す）には載せられないので載せない。
            commitFinal(ic, text)
            lastVoiceCommit = text
            endWord()
            syncView()
            continueOrFinishVoice()
        }

        override fun onVoiceFailed(message: String, silent: Boolean) {
            val hadPartial = voiceActive
            if (hadPartial) currentInputConnection?.let { clearComposing(it) }
            // 連続入力で「まだ何も話していない」だけなら、黙って聞き直す。
            // ただし続くようならマイクを開けたままにせず自動で終わる。
            if (hadPartial && voiceContinuous && silent &&
                ++voiceSilentRounds < MAX_VOICE_SILENT_ROUNDS
            ) {
                continueOrFinishVoice()
                return
            }
            voiceActive = false
            if (hadPartial && voiceContinuous && silent) {
                // 黙ったまま終わったので、エラーではなく「終わりました」と伝える
                showVoiceNotice(getString(R.string.voice_finished))
            } else {
                showVoiceNotice(message)
            }
        }
    }

    /**
     * ボイスコマンドを実行する。
     *
     * @return 音声入力そのものを終えたら true（呼び出し側はそこで打ち切る）
     */
    private fun runVoiceCommand(command: VoiceCommands.Command, ic: InputConnection): Boolean {
        when (command) {
            VoiceCommands.Command.ENTER -> onReturn(ic)
            VoiceCommands.Command.SPACE -> onSpace(ic)
            VoiceCommands.Command.CONVERT -> requestConversion()
            VoiceCommands.Command.COMMIT -> {
                finishConversionIfAny()
                flushComposing()
            }

            VoiceCommands.Command.BACKSPACE -> {
                onBackspace(ic)
                // 1 文字消したので、まるごと取り消す対象としては当てにできない
                lastVoiceCommit = null
            }

            VoiceCommands.Command.UNDO -> undoVoiceCommit(ic)
            VoiceCommands.Command.SELECT_ALL -> selectAllOrDelete()
            VoiceCommands.Command.CURSOR_LEFT -> moveCursor(false)
            VoiceCommands.Command.CURSOR_RIGHT -> moveCursor(true)
            VoiceCommands.Command.STOP -> {
                // それまでに入れた内容は残したまま終わる
                endVoice()
                showVoiceNotice(getString(R.string.voice_finished))
                return true
            }
        }
        return false
    }

    /**
     * 直前に音声で入れた文字列をまるごと消す。
     *
     * 消すのは「いまカーソルの直前がその文字列そのものだった」ときだけ。
     * ずれていたら何もしない（[undoLastCommit] と同じく、消しすぎを防ぐため）。
     */
    private fun undoVoiceCommit(ic: InputConnection): Boolean {
        val text = lastVoiceCommit ?: return false
        lastVoiceCommit = null
        if (text.isEmpty()) return false
        val before = ic.getTextBeforeCursor(text.length, 0)
        if (before?.toString() != text) return false
        return ic.deleteSurroundingText(text.length, 0)
    }

    /** 連続入力なら次の発話を聞きに行き、そうでなければセッションを終える。 */
    private fun continueOrFinishVoice() {
        if (!voiceActive) return
        if (!voiceContinuous) {
            voiceActive = false
            hideVoiceBanner()
            return
        }
        showVoiceBanner(UniStrokeView.VoiceState.LISTENING, listeningMessage())
        handler.removeCallbacks(voiceRestartRunnable)
        handler.postDelayed(voiceRestartRunnable, VOICE_RESTART_MS)
    }

    /** 連続入力で次の発話を聞き始める。入力欄が無くなっていたら終わる。 */
    private fun restartVoiceListening() {
        if (!voiceActive) return
        if (currentInputConnection == null) {
            endVoice()
            return
        }
        voice.start(voiceCallback)
    }

    /**
     * バナーの ✕（案内表示ならタップ）。聞き取った内容は捨てる。
     *
     * 話し終わりの判定は認識器に任せてあるので、確定のための操作は無い。
     * 黙ったところで [VoiceInput.Callback.onVoiceResult] が来て自動で確定する。
     */
    override fun onVoiceCancel() = endVoice()

    /**
     * 音声入力を打ち切る。マイクを手放し、聞き取り途中の未確定表示も消す。
     * 何も動いていないときは何もしない（通常の合成領域に触れてはいけない）。
     */
    private fun endVoice() {
        handler.removeCallbacks(voiceRestartRunnable)
        if (!voiceActive && !voice.isListening && !voiceBannerShown) return
        val hadPartial = voiceActive
        voiceActive = false
        voiceContinuous = false
        voiceSilentRounds = 0
        lastVoiceCommit = null
        voice.cancel()
        // 未確定表示を出しているのは音声のときだけ確実に分かる。
        // それ以外（ローマ字合成中）の合成領域には絶対に触らない。
        if (hadPartial) currentInputConnection?.let { clearComposing(it) }
        hideVoiceBanner()
    }

    /** 手書きやボタン操作が入ったら音声入力は打ち切る（マイクを掴んだままにしない）。 */
    private fun stopVoiceForOtherInput() {
        endVoice()
    }

    private fun showVoiceBanner(state: UniStrokeView.VoiceState, message: String) {
        handler.removeCallbacks(voiceNoticeRunnable)
        voiceBannerShown = true
        inputView?.showVoice(state, message)
    }

    /** 案内・エラーの表示。一定時間で自動的に閉じる（タップでも閉じる）。 */
    private fun showVoiceNotice(message: String) {
        voiceActive = false
        voiceBannerShown = true
        inputView?.showVoice(UniStrokeView.VoiceState.NOTICE, message)
        handler.removeCallbacks(voiceNoticeRunnable)
        handler.postDelayed(voiceNoticeRunnable, VOICE_NOTICE_MS)
    }

    private fun hideVoiceBanner() {
        handler.removeCallbacks(voiceNoticeRunnable)
        voiceBannerShown = false
        inputView?.showVoice(UniStrokeView.VoiceState.OFF, "")
    }

    /**
     * マイクの許可を求める。
     *
     * IME サービスは自分で権限ダイアログを出せないので、透明な Activity を
     * 一瞬だけ経由する。許可されても勝手に録音は始めない（もう一度長押ししてもらう）。
     */
    private fun requestMicPermission() {
        startActivity(
            Intent(this, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // ----------------------------------------------------------------- label

    private fun modeLabel(): String = when (inputMode) {
        InputMode.HIRAGANA -> UniStrokeView.MODE_HIRAGANA
        InputMode.KATAKANA -> UniStrokeView.MODE_KATAKANA
        InputMode.LATIN -> when (shift) {
            Shift.OFF -> UniStrokeView.MODE_LOWER
            Shift.ONCE -> UniStrokeView.MODE_SHIFT
            Shift.LOCK -> UniStrokeView.MODE_CAPS
        }
    }

    private fun syncView() {
        val view = inputView ?: return
        view.modeLabel = modeLabel()
        view.symbolMode = symbolMode
        view.statusLabel = buildStatusLabel()
    }

    /**
     * かなモードで、いま書かれようとしている 1 ストロークに期待される文字。
     * ローマ字の続きとしてありえる文字だけをごく僅かに優遇する（[StrokeRecognizer.CONTEXT_BONUS]）。
     *
     * 認識の直前に毎回問い合わせられるので、状態の同期漏れが起きない。
     */
    override fun expectedSymbols(): Set<String> {
        if (inputMode == InputMode.LATIN) return emptySet()
        // 一時アルファベット入力中は次に来る文字が任意なので偏らせない
        if (tempLatin != TempLatin.NONE) return emptySet()
        if (symbolMode != SymbolMode.NORMAL || converting) return emptySet()
        return RomajiConverter.expectedNext(romaji.toString())
    }

    /** 描画エリア右上に出す状態インジケータ。 */
    private fun buildStatusLabel(): String = buildString {
        if (inputMode == InputMode.LATIN) {
            when (shift) {
                Shift.ONCE -> append("⇧")
                Shift.LOCK -> append("⇧⇧")
                Shift.OFF -> Unit
            }
        } else {
            when (tempLatin) {
                TempLatin.UPPER -> append(TEMP_LATIN_UPPER)
                TempLatin.LOWER -> append(TEMP_LATIN_LOWER)
                TempLatin.NONE -> Unit
            }
        }
        when (symbolMode) {
            SymbolMode.PUNCTUATION -> { if (isNotEmpty()) append(' '); append("•") }
            SymbolMode.EXTENDED -> { if (isNotEmpty()) append(' '); append("＼") }
            SymbolMode.NORMAL -> Unit
        }
        if (autoLatin) {
            if (isNotEmpty()) append(' ')
            append(AUTO_LATIN_CHIP)
        }
        if (converting) {
            if (isNotEmpty()) append(' ')
            append("${activeSegment + 1}/${segments.size}")
            // 文節編集モードでは候補バーの両端が 《 》 で埋まるので、
            // 「端末内」はチップではなくこちらへ出す。
            if (candidatesFromOnDevice) append(" 端末内")
        }
    }

    private companion object {
        val HIGHLIGHT: Int = Color.argb(90, 125, 247, 160)

        /** 一時アルファベット入力の状態チップ。 */
        const val TEMP_LATIN_UPPER = "A"
        const val TEMP_LATIN_LOWER = "a"

        /** これ以上の smallestScreenWidthDp を「展開状態」とみなす。 */
        const val EXPANDED_MIN_SW_DP = 600

        /**
         * 自動変換候補のデバウンス。体感が即時になるよう短くしてある。
         * リクエスト数は GoogleConvertClient 側の LRU キャッシュと世代番号で抑える。
         */
        const val LIVE_SUGGEST_DELAY_MS = 120L

        /** 候補バーに並べる自動候補の最大数（横スクロールで全部見られる）。 */
        const val MAX_LIVE_CANDIDATES = 15

        /** 端末内辞書から取る前方一致予測の件数。 */
        const val ON_DEVICE_PREDICTIONS = 5

        /** 自動アルファベット化中の状態チップ。 */
        const val AUTO_LATIN_CHIP = "abc自動"

        /** 音声入力の案内・エラー表示を自動で閉じるまでの時間。 */
        const val VOICE_NOTICE_MS = 4_000L

        /**
         * 連続入力で次の発話を聞き始めるまでの間。
         * 認識器を作り直すので、詰めすぎると取りこぼす。
         */
        const val VOICE_RESTART_MS = 250L

        /**
         * 連続入力で「何も話されなかった」が続いたら自動で終える回数。
         * ハンズフリーでも、黙っているあいだにマイクが開き続けることはない。
         */
        const val MAX_VOICE_SILENT_ROUNDS = 2

        /**
         * かなモードで合成へ足す全角の日本語記号。
         * 読点・句点・長音は読みの一部として変換にかけられる必要がある。
         */
        const val KANA_COMPOSING_SYMBOLS = "ー、。・「」『』（）？！〜…：；"
    }
}
