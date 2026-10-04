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
 * アプリ本体の更新確認とダウンロード。
 *
 * 配布は GitHub Releases（署名済み APK）なので、最新リリースの情報を見て
 * 「今より新しい版があるか」を判断し、あれば APK を落としてシステムの
 * インストーラーへ渡す。**サイレントインストールはしない**（Android の仕様上できないし、
 * 利用者の明示的な承認を挟むべきもの）。
 *
 * ### 通信について
 * この IME の方針は「**入力しているときは通信しない**」。
 * 更新確認はアプリの画面側の機能なので、その線引きの内側にある。
 *
 *   - 既定は**オン**。設定でオフにできる（[Prefs.KEY_APP_AUTO_UPDATE]）
 *   - 走るのはアプリの画面を開いたときだけ。[MIN_CHECK_INTERVAL_MS] 以上空けて便乗する。
 *     バックグラウンドで定期的に起こすことはしない
 *   - **IME サービスからは絶対に呼ばない**（入力中に通信や UI が走らないように）
 *   - ネット変換の同意とは別物。更新確認をオンにしても、ネット変換はオフのまま
 *   - 送るのは「最新版は何か」という問い合わせだけ。入力内容は含まれない
 *     （GitHub 側からは接続元の IP アドレスと、要求に付く User-Agent が見える）
 *
 * ### 検証
 * ダウンロードした APK は、リリースが申告するサイズと
 * SHA-256（GitHub が `digest` として返す）に一致するかを確かめてから渡す。
 * 署名の検証は Android のインストーラーが行う ―― 今入っているものと署名が違えば
 * インストールは拒否されるので、こちら側で追加の実装は要らない。
 */
object AppUpdater {

    /** 最新リリースの情報。公開リポジトリなので認証は不要。 */
    const val RELEASE_URL =
        "https://api.github.com/repos/makiiii-git/unistroke-ime/releases/latest"

    /** 自動確認を有効にしていても、これより短い間隔では確認しない（1 日）。 */
    const val MIN_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** ダウンロードした APK を置く場所（アプリ専用領域）。 */
    const val DOWNLOAD_DIR = "updates"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 壊れた応答が延々流れてきた場合の保険。APK は 10 MB 程度。 */
    private const val MAX_APK_BYTES = 128L * 1024 * 1024
    private const val MAX_JSON_BYTES = 512 * 1024

    private const val BUFFER = 64 * 1024

    /** 保存先の空き容量にこれだけ余裕が無ければ始めない。 */
    private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024

    /** リリースノートは長いことがあるので、画面に出すぶんだけ切り出す。 */
    private const val NOTE_LIMIT = 400

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unistroke-app-update").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 同時に 2 つ走らせない。 */
    private val running = AtomicBoolean(false)

    // ------------------------------------------------------------------ 型

    /** 最新リリースのうち、更新に必要なぶんだけ。 */
    data class Release(
        /** タグ名（例: "v1.1.0"）。 */
        val tag: String,
        /** 表示用のバージョン（先頭の "v" を落としたもの）。 */
        val version: String,
        val apkUrl: String,
        val sizeBytes: Long,
        /** "sha256:..." の 16 進部分。GitHub が返さなければ空。 */
        val sha256: String,
        /** リリースノートの抜粋。 */
        val notes: String,
    )

    enum class Failure {
        /** 通信できなかった / 応答が壊れていた。 */
        NETWORK,

        /** APK アセットが見つからない。 */
        NO_ASSET,

        /** 保存先の空きが足りない。 */
        NO_SPACE,

        /** サイズかハッシュが申告と違った。 */
        VERIFY,
    }

    sealed interface Progress {
        /** 更新がある。 */
        data class Available(val release: Release) : Progress

        /** すでに最新。 */
        data object UpToDate : Progress

        data class Downloading(val bytes: Long, val total: Long) : Progress

        /** 落とし終わって検証も通った。この APK をインストーラーへ渡せる。 */
        data class Ready(val release: Release, val apk: File) : Progress

        data class Failed(val reason: Failure) : Progress
    }

    // ------------------------------------------------------ バージョン比較

    /**
     * 「メジャー.マイナー.パッチ」を数値として比べる。
     *
     * 文字列比較では "1.10.0" < "1.9.0" になってしまうので使えない。
     * 桁数が違う場合は足りないぶんを 0 とみなす（"1.0" == "1.0.0"）。
     * 実際、初期のリリースは 2 段（v1.0）、いまは 3 段（1.1.0）で運用されている。
     *
     * 先頭の "v" は落とす。数字以外が続く部分（"1.2.0-beta" の "-beta" など）は
     * **プレリリースとして本リリースより古い**とみなす（セマンティックバージョンの規約）。
     *
     * @return [a] が [b] より新しければ正、古ければ負、同じなら 0
     */
    fun compareVersions(a: String, b: String): Int {
        val (aNums, aPre) = splitVersion(a)
        val (bNums, bPre) = splitVersion(b)
        val len = maxOf(aNums.size, bNums.size)
        for (i in 0 until len) {
            val x = aNums.getOrElse(i) { 0 }
            val y = bNums.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        // 数字が同じなら、プレリリース付きのほうが古い
        if (aPre.isEmpty() && bPre.isEmpty()) return 0
        if (aPre.isEmpty()) return 1
        if (bPre.isEmpty()) return -1
        return aPre.compareTo(bPre)
    }

    /** "v1.2.3-beta1" -> ([1,2,3], "beta1")。数字として読めない部分は捨てる。 */
    private fun splitVersion(raw: String): Pair<List<Int>, String> {
        var s = raw.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val dash = s.indexOfFirst { it == '-' || it == '+' }
        val pre = if (dash >= 0) s.substring(dash + 1) else ""
        if (dash >= 0) s = s.substring(0, dash)
        val nums = s.split('.').map { part ->
            // "1a" のような混ざりものは先頭の数字だけを見る。数字が無ければ 0。
            val digits = part.takeWhile { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }
        return nums to pre
    }

    /** [release] が今入っている版より新しいか。 */
    fun isNewer(release: Release): Boolean =
        compareVersions(release.version, BuildConfig.VERSION_NAME) > 0

    // ------------------------------------------------------------ 確認・取得

    /**
     * 自動確認をしてよいタイミングか。
     * 有効になっていて、かつ前回から [MIN_CHECK_INTERVAL_MS] 以上経っている場合だけ。
     */
    fun shouldAutoCheck(context: Context, now: Long): Boolean {
        if (!Prefs.isAppAutoUpdate(context)) return false
        val last = Prefs.of(context).getLong(Prefs.KEY_APP_LAST_CHECK, 0L)
        return now - last >= MIN_CHECK_INTERVAL_MS
    }

    /**
     * 最新リリースを見て、新しければ [Progress.Available] を返す。
     * ダウンロードはしない（利用者が承諾してから [download] を呼ぶ）。
     *
     * [onProgress] はメインスレッドで呼ばれる。
     */
    fun check(context: Context, onProgress: (Progress) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                // 前回の残骸を片付ける（キャッシュを溜めない）
                clearDownloads(app)
                Prefs.of(app).edit()
                    .putLong(Prefs.KEY_APP_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                val release = fetchLatest()
                val progress = when {
                    release == null -> Progress.Failed(Failure.NETWORK)
                    release.apkUrl.isEmpty() -> Progress.Failed(Failure.NO_ASSET)
                    isNewer(release) -> Progress.Available(release)
                    else -> Progress.UpToDate
                }
                main.post { onProgress(progress) }
            } catch (t: Throwable) {
                main.post { onProgress(Progress.Failed(Failure.NETWORK)) }
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * APK を落として検証する。成功したら [Progress.Ready]。
     *
     * [cancelled] が true を返したら途中で捨てて終わる。
     */
    fun download(
        context: Context,
        release: Release,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            var target: File? = null
            try {
                val dir = File(app.cacheDir, DOWNLOAD_DIR)
                dir.mkdirs()
                if (dir.usableSpace < release.sizeBytes + FREE_SPACE_MARGIN) {
                    main.post { onProgress(Progress.Failed(Failure.NO_SPACE)) }
                    return@execute
                }
                val tmp = File(dir, "update.apk.part")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L

                val conn = open(release.apkUrl)
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        main.post { onProgress(Progress.Failed(Failure.NETWORK)) }
                        return@execute
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                if (cancelled()) {
                                    tmp.delete()
                                    return@execute
                                }
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                total += n
                                if (total > MAX_APK_BYTES) {
                                    tmp.delete()
                                    main.post { onProgress(Progress.Failed(Failure.VERIFY)) }
                                    return@execute
                                }
                                val sent = total
                                main.post { onProgress(Progress.Downloading(sent, release.sizeBytes)) }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                // 申告どおりのものが落ちてきたか確かめてから渡す
                if (total != release.sizeBytes) {
                    tmp.delete()
                    main.post { onProgress(Progress.Failed(Failure.VERIFY)) }
                    return@execute
                }
                if (release.sha256.isNotEmpty() &&
                    !hex(digest.digest()).equals(release.sha256, ignoreCase = true)
                ) {
                    tmp.delete()
                    main.post { onProgress(Progress.Failed(Failure.VERIFY)) }
                    return@execute
                }

                val apk = File(dir, "unistroke-ime-${release.tag}.apk")
                apk.delete()
                if (!tmp.renameTo(apk)) {
                    tmp.delete()
                    main.post { onProgress(Progress.Failed(Failure.VERIFY)) }
                    return@execute
                }
                target = apk
                main.post { onProgress(Progress.Ready(release, apk)) }
            } catch (t: Throwable) {
                target?.delete()
                main.post { onProgress(Progress.Failed(Failure.NETWORK)) }
            } finally {
                running.set(false)
            }
        }
    }

    /** 落としてある APK を消す。次に開いたときに溜めっぱなしにしないため。 */
    fun clearDownloads(context: Context) {
        runCatching {
            File(context.applicationContext.cacheDir, DOWNLOAD_DIR)
                .listFiles()?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------ HTTP / 解析

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub は User-Agent の無い要求を弾くことがある
            setRequestProperty("User-Agent", "unistroke-ime")
        }

    fun fetchLatest(): Release? {
        val conn = open(RELEASE_URL)
        try {
            // レート制限（未認証は 60 回/時）に当たったら静かに諦める
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.use { input ->
                val out = StringBuilder()
                val buf = ByteArray(BUFFER)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON_BYTES) return null
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                out.toString()
            }
            return parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GitHub の `releases/latest` 応答から必要なぶんを取り出す。
     *
     * 下書き・プレリリースは配布対象ではないので無視する。
     * アセットは `.apk` で終わるものを採る。
     */
    fun parseRelease(body: String): Release? = try {
        val root = JSONObject(body)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            null
        } else {
            val tag = root.optString("tag_name", "")
            if (tag.isEmpty()) {
                null
            } else {
                var url = ""
                var size = 0L
                var sha = ""
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (!name.endsWith(".apk", ignoreCase = true)) continue
                        url = a.optString("browser_download_url", "")
                        size = a.optLong("size", 0L)
                        // "sha256:<hex>" の形。将来別の方式になったら空にしておく
                        val digest = a.optString("digest", "")
                        sha = if (digest.startsWith("sha256:")) {
                            digest.removePrefix("sha256:")
                        } else {
                            ""
                        }
                        break
                    }
                }
                Release(
                    tag = tag,
                    version = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = url,
                    sizeBytes = size,
                    sha256 = sha,
                    notes = summarize(root.optString("body", "")),
                )
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** リリースノートを画面に出せる長さへ切る。 */
    private fun summarize(body: String): String {
        val text = body.lineSequence()
            .map { it.trim() }
            // 見出しと箇条書きの記号は落として読みやすくする
            .map { it.removePrefix("#").removePrefix("#").removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return if (text.length <= NOTE_LIMIT) text else text.take(NOTE_LIMIT) + "…"
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}
