package com.unistroke.ime

/**
 * 音声入力のコマンド判定。
 *
 * ハンズフリーで使えるように、認識結果が既知の言い回しと一致したときは
 * 文字として入れずに操作として実行する。
 *
 * **一致は「発話まるごと」でしか取らない。** 「確定してください」「削除する」のような
 * 文の一部として現れた語はコマンドにしない。こうしておくと、
 * 「確定」という語そのものを書きたいときは文の中で言えば普通に入力できる。
 *
 * 表記ゆれは [normalize] で吸収する（カタカナ -> ひらがな、句読点と空白の除去）。
 * 認識器は同じ発話を「確定」とも「かくてい」とも返すので、対応表には
 * 漢字とかなの両方を並べてある。
 *
 * Android 依存を持たない純粋な関数なので、そのままテストできる。
 */
object VoiceCommands {

    enum class Command {
        /** 改行（合成中なら確定。検索欄などでは「検索」「送信」）。 */
        ENTER,

        /** 空白を入れる（かな合成中は変換になる ―― ストロークのスペースと同じ）。 */
        SPACE,

        /** かな合成を漢字変換にかける。 */
        CONVERT,

        /** 変換中・合成中の内容を確定する。 */
        COMMIT,

        /** 1 文字消す。 */
        BACKSPACE,

        /** 直前に音声で入れた内容をまるごと取り消す。 */
        UNDO,

        /** 入力欄の内容をすべて選択する。 */
        SELECT_ALL,

        /** カーソルを左へ。 */
        CURSOR_LEFT,

        /** カーソルを右へ。 */
        CURSOR_RIGHT,

        /** 音声入力を終える（それまでに入れた内容は残る）。 */
        STOP,
    }

    /**
     * 利用者に案内する言い回し。設定画面の一覧（voice_commands_list）と 1 対 1 で対応する。
     *
     * 増やすときは「文の一部として出てきそうな短い語」を避ける。
     * 誤爆しても取り返しがつく操作（カーソル移動など）はまだしも、
     * 送信・全選択のような戻しにくい操作ほど言い回しを絞ってある。
     */
    private val PHRASES: List<Pair<Command, List<String>>> = listOf(
        Command.ENTER to listOf("エンター", "リターン", "改行", "送信"),
        Command.SPACE to listOf("スペース", "空白"),
        Command.CONVERT to listOf("変換"),
        Command.COMMIT to listOf("確定", "決定"),
        Command.BACKSPACE to listOf("削除", "消して", "一文字消して", "バックスペース"),
        Command.UNDO to listOf("取り消し", "取消", "取り消して"),
        Command.SELECT_ALL to listOf("全選択", "全部選択"),
        Command.CURSOR_LEFT to listOf("左", "左へ"),
        Command.CURSOR_RIGHT to listOf("右", "右へ"),
        Command.STOP to listOf("音声終了", "終了", "終わり"),
    )

    /**
     * 漢字の言い回しを認識器がかなのまま返したとき用の読み。
     *
     * 同じ発話の別表記でしかないので、設定画面の一覧には出さない。
     * カタカナは [normalize] がひらがなへ均すので、ここに書く必要はない。
     */
    private val READINGS: List<Pair<Command, List<String>>> = listOf(
        Command.ENTER to listOf("かいぎょう", "そうしん"),
        Command.SPACE to listOf("くうはく"),
        Command.CONVERT to listOf("へんかん"),
        Command.COMMIT to listOf("かくてい", "けってい"),
        Command.BACKSPACE to listOf("さくじょ", "けして", "いちもじけして"),
        Command.UNDO to listOf("とりけし", "とりけして"),
        Command.SELECT_ALL to listOf("ぜんせんたく", "ぜんぶせんたく"),
        Command.CURSOR_LEFT to listOf("ひだり", "ひだりへ"),
        Command.CURSOR_RIGHT to listOf("みぎ", "みぎへ"),
        Command.STOP to listOf("おんせいしゅうりょう", "しゅうりょう", "おわり"),
    )

    private val TABLE: Map<String, Command> =
        (PHRASES + READINGS)
            .flatMap { (command, words) -> words.map { normalize(it) to command } }
            .toMap()

    /**
     * 認識結果を突き合わせる形に均す。
     *
     *   ・前後の空白を落とし、途中の空白も落とす（「取り消し」「取り 消し」を同一視）
     *   ・句読点・感嘆符・鉤括弧を落とす（認識器が「確定。」と返すことがある）
     *   ・カタカナをひらがなへ（「エンター」->「えんたー」。長音「ー」はそのまま）
     *   ・ASCII は小文字へ（英語表記で返す認識器のため）
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() -> Unit
                ch in IGNORED -> Unit
                // カタカナ -> ひらがな（U+30A1..U+30F6 を 0x60 引く）。「ー」は範囲外なので残る
                ch in 'ァ'..'ヶ' -> out.append(ch - KATAKANA_OFFSET)
                ch in 'A'..'Z' -> out.append(ch + ASCII_CASE_OFFSET)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * [text] がコマンドならそれを返す。文字として入れるべきならば null。
     * 判定は発話まるごとの完全一致のみ。
     */
    fun match(text: String): Command? = TABLE[normalize(text)]

    /** 句読点のたぐい。認識器が付けてくることがあるので落としてから突き合わせる。 */
    private const val IGNORED = "。、．，・！？!?「」『』（）()…"

    private const val KATAKANA_OFFSET = 0x60
    private const val ASCII_CASE_OFFSET = 0x20
}
