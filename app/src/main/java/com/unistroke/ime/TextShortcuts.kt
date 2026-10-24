package com.unistroke.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定型文（英語版・プレミアム）。短い綴りを書くと、登録した文章が候補バーの先頭に出る。
 *
 * 例: "brb" → "be right back"、"addr" → 住所。候補をタップすると文章が入る。
 * 出すのは英単語の予測と同じ候補バーなので、予測が使える欄（＝プレミアムで、
 * パスワード欄などではない欄）でだけ出る。鍵はここでは見ない（[UniStrokeIME.applyEnglishPrefs]）。
 *
 * 保存先は設定（[Prefs.KEY_TEXT_SHORTCUTS]）に JSON の配列で 1 つだけ。端末の外へは出さない。
 */
object TextShortcuts {

    class Shortcut(val key: String, val phrase: String)

    /** 登録できる数。候補バーと設定画面の一覧が長くなりすぎないように。 */
    const val MAX_COUNT = 50

    /** 綴りの長さ。書きかけの単語として照合するので英字だけ。 */
    const val MAX_KEY_LENGTH = 16

    const val MAX_PHRASE_LENGTH = 500

    fun load(context: Context): List<Shortcut> =
        parse(Prefs.of(context).getString(Prefs.KEY_TEXT_SHORTCUTS, null))

    fun save(context: Context, items: List<Shortcut>) {
        val array = JSONArray()
        for (s in items.take(MAX_COUNT)) {
            array.put(JSONObject().put("key", s.key).put("phrase", s.phrase))
        }
        Prefs.of(context).edit().putString(Prefs.KEY_TEXT_SHORTCUTS, array.toString()).apply()
    }

    /** 保存値を読む。壊れていたら空（定型文が消えても入力そのものは壊さない）。 */
    fun parse(json: String?): List<Shortcut> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<Shortcut>()
            for (i in 0 until minOf(array.length(), MAX_COUNT)) {
                val o = array.optJSONObject(i) ?: continue
                val key = normalizeKey(o.optString("key"))
                val phrase = o.optString("phrase")
                if (key != null && phrase.isNotEmpty()) out.add(Shortcut(key, phrase))
            }
            out
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /** 綴りを登録用の形（小文字の英字だけ）にする。使えない綴りなら null。 */
    fun normalizeKey(raw: String): String? {
        val key = raw.trim().lowercase()
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
        if (!key.all { it in 'a'..'z' }) return null
        return key
    }

    /**
     * 書きかけの綴り [typed] に一致する定型文。大文字で書き始めたら文章の頭も大文字にする
     * （英単語の予測と同じ扱い）。綴りは大文字・小文字を区別しない。
     */
    fun matching(items: List<Shortcut>, typed: String): List<String> {
        if (typed.isEmpty()) return emptyList()
        val key = typed.lowercase()
        val capital = typed[0].isUpperCase()
        return items.filter { it.key == key }.map { s ->
            if (capital) s.phrase.replaceFirstChar { it.uppercaseChar() } else s.phrase
        }
    }
}
