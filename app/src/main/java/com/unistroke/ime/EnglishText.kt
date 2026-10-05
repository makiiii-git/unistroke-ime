package com.unistroke.ime

/**
 * 英語版の入力で使う、文字列まわりの小さな規則。
 *
 * Android に依存しない純粋な関数だけを置く（test_english.py が同じ規則を写して検証する）。
 */
object EnglishText {

    /** 単語の途中に置ける記号。打った綴りの一部として合成に残す。 */
    const val APOSTROPHE = "'"

    /**
     * 候補を確定した直後に書かれたら、自動で入れた空白の前へ詰める記号。
     * 「word 」のあとに「.」を書くと「word. 」になる（空白は記号のうしろへ回る）。
     */
    const val TIGHT_PUNCTUATION = ".,!?:;"

    /** 単語として覚える長さの範囲。1 文字は予測する意味が無く、長すぎるものは単語ではない。 */
    const val MIN_LEARN_LENGTH = 2
    const val MAX_LEARN_LENGTH = 32

    /**
     * 辞書を引く鍵。英字を小文字にし、アポストロフィは落とす。
     *
     * アポストロフィを落とすのは、一筆書きでは記号が 2 ストロークかかるため。
     * "dont" と書けば "don't" が、"im" と書けば "I'm" が候補に出る。
     * 英字とアポストロフィ以外（数字・アクセント付きの字など）が混じっていたら、
     * 辞書には無い綴りなので空文字を返す（＝予測しない）。
     */
    fun key(typed: CharSequence): String {
        val out = StringBuilder(typed.length)
        for (ch in typed) {
            when {
                ch in 'a'..'z' -> out.append(ch)
                ch in 'A'..'Z' -> out.append(ch + ASCII_CASE_OFFSET)
                isApostrophe(ch) -> Unit
                else -> return ""
            }
        }
        return out.toString()
    }

    fun isApostrophe(ch: Char): Boolean = ch == '\'' || ch == '’'

    /**
     * 辞書の表記 [word] を、打った綴り [typed] の大文字・小文字に合わせる。
     *
     *   すべて大文字で 2 文字以上（CapsLock）… 候補もすべて大文字
     *   先頭だけ大文字（文頭・シフト 1 回）   … 候補の先頭を大文字
     *   それ以外                               … 辞書の表記のまま（London・I'm など）
     */
    fun applyCase(word: String, typed: CharSequence): String {
        if (word.isEmpty()) return word
        var letters = 0
        var uppers = 0
        var firstUpper = false
        for (ch in typed) {
            if (!ch.isLetter()) continue
            if (letters == 0) firstUpper = ch.isUpperCase()
            letters++
            if (ch.isUpperCase()) uppers++
        }
        return when {
            letters >= 2 && uppers == letters -> word.uppercase()
            firstUpper -> word.substring(0, 1).uppercase() + word.substring(1)
            else -> word
        }
    }

    /** 先頭だけが大文字の綴りか（"Hello"。"I" や "NASA"、"iPhone" は違う）。 */
    fun isCapitalized(word: String): Boolean {
        if (word.length < 2 || !word[0].isUpperCase()) return false
        for (i in 1 until word.length) if (word[i].isUpperCase()) return false
        return true
    }

    /** すべて大文字の綴りか（2 文字以上。記号は数えない）。 */
    fun isAllCaps(word: String): Boolean {
        var letters = 0
        for (ch in word) {
            if (!ch.isLetter()) continue
            if (!ch.isUpperCase()) return false
            letters++
        }
        return letters >= 2
    }

    /**
     * 履歴に覚えてよい単語か。
     * 英字とアポストロフィだけでできていて、長さが範囲内のものに限る。
     */
    fun isLearnable(word: String): Boolean {
        if (word.length < MIN_LEARN_LENGTH || word.length > MAX_LEARN_LENGTH) return false
        var letters = 0
        for (ch in word) {
            when {
                ch.isLetter() -> letters++
                isApostrophe(ch) -> Unit
                else -> return false
            }
        }
        return letters >= MIN_LEARN_LENGTH
    }

    /**
     * 単語削除で消す長さ。[before] はカーソル直前のテキスト。
     *
     *   1. 末尾の空白を飛ばす（改行は空白に含めない）
     *   2. その手前が単語の文字なら単語ごと、記号なら記号の並びごと消す
     *
     * 直前が改行のときは改行 1 つだけを消す（行をまたいで前の行の単語まで消さない）。
     */
    fun wordDeleteLength(before: CharSequence): Int {
        var i = before.length
        if (i == 0) return 0
        if (before[i - 1] == '\n') return 1
        while (i > 0 && isBlank(before[i - 1])) i--
        if (i == 0) return before.length
        val word = isWordChar(before[i - 1])
        while (i > 0) {
            val ch = before[i - 1]
            if (ch == '\n' || isBlank(ch) || isWordChar(ch) != word) break
            i--
        }
        return before.length - i
    }

    private fun isBlank(ch: Char): Boolean = ch != '\n' && ch.isWhitespace()

    /** 単語を作る文字か（英数字とアポストロフィ）。 */
    fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || isApostrophe(ch)

    /**
     * 音声で入った文字列の前に空白が要るか。[before] はカーソル直前の 1 文字（無ければ null）。
     *
     * 英語は語を空白で区切るので、続けて話した 2 つの発話が「hello worldhow are you」と
     * くっつかないようにする。行頭・空白のあと・開き括弧のあとには入れない。
     */
    fun needsSpaceBefore(before: Char?): Boolean {
        if (before == null) return false
        if (before.isWhitespace()) return false
        return OPENERS.indexOf(before) < 0
    }

    private const val OPENERS = "([{\"'“‘/-@#"

    private const val ASCII_CASE_OFFSET = 0x20
}
