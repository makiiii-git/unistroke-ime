package com.unistroke.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拡張辞書（約 22 万語）の取得と更新。
 *
 * 辞書は 2 層になっていて、コア辞書（約 8 万語）は APK に同梱されている。
 * この更新機構が無くても、あるいは通信に失敗しても、日本語入力は成立する。
 * **拡張辞書は「あれば嬉しい」ものであって、必須ではない。**
 *
 * 置き換えは必ず「一時ファイルへ落とす -> 検証 -> リネーム」の順で行う。
 * 検証を通らなかったものは捨て、既に入っている辞書には触れない。
 * 途中で電源が落ちても、壊れた辞書が本番の位置に残ることはない。
 *
 * 通信は利用者が明示的に操作したときだけ発生する。
 * [Prefs.KEY_DICT_AUTO_UPDATE] を有効にした場合でも、
 * [MIN_CHECK_INTERVAL_MS] の間隔を空けたうえでアプリを開いたときに便乗するだけで、
 * バックグラウンドで定期的に起こすことはしない。
 */
object DictionaryUpdater {

    /**
     * 配布物の所在。マニフェストは小さいのでリポジトリに置き、
     * 辞書本体は GitHub Releases のアセットとして配る（マニフェスト内の url）。
     */
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/makiiii-git/unistroke-ime/main/dictionary/manifest.json"

    /** マニフェストの構造そのもののバージョン。上がったら古いアプリは無視する。 */
    const val SUPPORTED_SCHEMA = 1

    /** 辞書バイナリの形式。OnDeviceDictionary が読める版だけを受け入れる。 */
    const val SUPPORTED_FORMAT = 2

    /** 自動確認を有効にしていても、これより短い間隔では確認しない。 */
    const val MIN_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 壊れた応答が延々流れてきた場合の保険。辞書は 7 MB 程度。 */
    private const val MAX_DICT_BYTES = 32L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 8 * 1024

    private const val BUFFER = 64 * 1024

    /** 展開先の空き容量にこれだけ余裕が無ければ始めない。 */
    private const val FREE_SPACE_MARGIN = 8L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unistroke-dict-update").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 同時に 2 つ走らせない。 */
    private val running = AtomicBoolean(false)

    // ------------------------------------------------------------------ 型

    data class Manifest(
        val schema: Int,
        val dictVersion: Int,
        val formatVersion: Int,
        val words: Int,
        val size: Long,
        val sha256: String,
        val url: String,
        val minAppVersionCode: Int,
    )

    enum class Failure {
        /** 通信できない・タイムアウト・HTTP エラー。 */
        NETWORK,

        /** マニフェストが読めない、または未対応の schema。 */
        MANIFEST,

        /** このアプリでは扱えない辞書形式／アプリが古い。 */
        INCOMPATIBLE,

        /** 落としたサイズが宣言と違う。 */
        SIZE,

        /** SHA-256 が一致しない。改竄または破損。 */
        CHECKSUM,

        /** 辞書として読めない（マジックが違う等）。 */
        BROKEN,

        /** 保存できない（空き容量・権限）。 */
        STORAGE,
    }

    sealed interface Progress {
        /** マニフェスト取得中。 */
        data object Checking : Progress

        /** 既に最新。 */
        data object UpToDate : Progress

        /** 取得可能なものがある（自動で落とさない場合に使う）。 */
        data class Available(val manifest: Manifest) : Progress

        data class Downloading(val bytes: Long, val total: Long) : Progress

        /** ハッシュ照合中。 */
        data object Verifying : Progress

        data class Done(val manifest: Manifest) : Progress

        data class Failed(val reason: Failure) : Progress
    }

    // ------------------------------------------------------------- 公開 API

    /** 拡張辞書が入っているか（読める状態か）。 */
    fun isExtensionInstalled(context: Context): Boolean =
        OnDeviceDictionary.hasValidExtension(context)

    /** 入っている拡張辞書の版。無ければ 0。 */
    fun installedVersion(context: Context): Int =
        if (isExtensionInstalled(context)) {
            Prefs.of(context).getInt(Prefs.KEY_DICT_VERSION, 0)
        } else {
            0
        }

    /**
     * 自動確認をしてよいタイミングか。
     * 有効になっていて、かつ前回から [MIN_CHECK_INTERVAL_MS] 以上経っている場合だけ。
     */
    fun shouldAutoCheck(context: Context, now: Long): Boolean {
        if (!Prefs.isDictAutoUpdate(context)) return false
        if (!isExtensionInstalled(context)) return false
        val last = Prefs.of(context).getLong(Prefs.KEY_DICT_LAST_CHECK, 0L)
        return now - last >= MIN_CHECK_INTERVAL_MS
    }

    /**
     * マニフェストを見て、必要なら落として入れ替える。
     *
     * [onProgress] はメインスレッドで呼ばれる。
     * [autoDownload] が false のときは、更新があっても [Progress.Available] で止める。
     */
    fun checkAndUpdate(
        context: Context,
        autoDownload: Boolean,
        onProgress: (Progress) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val report: (Progress) -> Unit = { p -> main.post { onProgress(p) } }
        report(Progress.Checking)
        executor.execute {
            try {
                runUpdate(app, autoDownload, report)
            } catch (t: Throwable) {
                report(Progress.Failed(Failure.NETWORK))
            } finally {
                running.set(false)
            }
        }
    }

    /** 拡張辞書を消してコア辞書へ戻す。 */
    fun removeExtension(context: Context): Boolean {
        val app = context.applicationContext
        val ok = OnDeviceDictionary.extFile(app).let { !it.exists() || it.delete() }
        if (ok) {
            Prefs.of(app).edit().remove(Prefs.KEY_DICT_VERSION).apply()
            OnDeviceConverter.reset()
        }
        return ok
    }

    // ------------------------------------------------------------------ 実装

    private fun runUpdate(context: Context, autoDownload: Boolean, report: (Progress) -> Unit) {
        Prefs.of(context).edit()
            .putLong(Prefs.KEY_DICT_LAST_CHECK, System.currentTimeMillis())
            .apply()

        val manifest = fetchManifest() ?: run {
            report(Progress.Failed(Failure.MANIFEST))
            return
        }
        if (manifest.schema != SUPPORTED_SCHEMA || manifest.formatVersion != SUPPORTED_FORMAT) {
            report(Progress.Failed(Failure.INCOMPATIBLE))
            return
        }
        if (BuildConfig.VERSION_CODE < manifest.minAppVersionCode) {
            report(Progress.Failed(Failure.INCOMPATIBLE))
            return
        }
        if (manifest.size <= 0 || manifest.size > MAX_DICT_BYTES) {
            report(Progress.Failed(Failure.MANIFEST))
            return
        }
        if (installedVersion(context) >= manifest.dictVersion) {
            report(Progress.UpToDate)
            return
        }
        if (!autoDownload) {
            report(Progress.Available(manifest))
            return
        }
        download(context, manifest, report)
    }

    /**
     * 落として検証して入れ替える。
     * 検証に落ちた場合は一時ファイルを消すだけで、既存の辞書には触れない。
     */
    fun download(context: Context, manifest: Manifest, report: (Progress) -> Unit) {
        val dir = context.filesDir
        if (dir.usableSpace < manifest.size + FREE_SPACE_MARGIN) {
            report(Progress.Failed(Failure.STORAGE))
            return
        }
        val tmp = File(dir, OnDeviceDictionary.EXT_NAME + ".part")
        tmp.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val conn = (URL(manifest.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                report(Progress.Failed(Failure.NETWORK))
                return
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > MAX_DICT_BYTES) {
                            report(Progress.Failed(Failure.SIZE))
                            return
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        report(Progress.Downloading(written, manifest.size))
                    }
                    out.fd.sync()
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            report(Progress.Failed(Failure.NETWORK))
            return
        } finally {
            conn.disconnect()
        }

        report(Progress.Verifying)

        if (written != manifest.size) {
            tmp.delete()
            report(Progress.Failed(Failure.SIZE))
            return
        }
        if (!hex(digest.digest()).equals(manifest.sha256, ignoreCase = true)) {
            tmp.delete()
            report(Progress.Failed(Failure.CHECKSUM))
            return
        }
        // ハッシュが合っていても、こちらが読める辞書とは限らない。実際に開いてみる。
        if (!OnDeviceDictionary.isReadableDictionary(tmp)) {
            tmp.delete()
            report(Progress.Failed(Failure.BROKEN))
            return
        }

        val dest = OnDeviceDictionary.extFile(context)
        // 同じディレクトリ内の rename は不可分。既存の mmap は古い inode を掴んだまま
        // 有効なので、入れ替えの瞬間に変換が壊れることはない。
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            report(Progress.Failed(Failure.STORAGE))
            return
        }
        Prefs.of(context).edit()
            .putInt(Prefs.KEY_DICT_VERSION, manifest.dictVersion)
            .apply()
        // 次回の変換から新しい辞書を使わせる。
        OnDeviceConverter.reset()
        report(Progress.Done(manifest))
    }

    fun fetchManifest(): Manifest? {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.use { input ->
                val buf = ByteArray(MAX_MANIFEST_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
            parseManifest(body)
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun parseManifest(body: String): Manifest? = try {
        val o = JSONObject(body)
        val url = o.getString("url")
        // 平文 HTTP へ誘導されないようにする
        if (!url.startsWith("https://")) {
            null
        } else {
            Manifest(
                schema = o.getInt("schema"),
                dictVersion = o.getInt("dictVersion"),
                formatVersion = o.getInt("formatVersion"),
                words = o.optInt("words", 0),
                size = o.getLong("size"),
                sha256 = o.getString("sha256"),
                url = url,
                minAppVersionCode = o.optInt("minAppVersionCode", 1),
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}
