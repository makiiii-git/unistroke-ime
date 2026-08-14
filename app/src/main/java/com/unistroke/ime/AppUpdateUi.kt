package com.unistroke.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * アプリ更新の画面まわり。確認 -> 案内 -> ダウンロード -> インストーラー起動をまとめる。
 *
 * [MainActivity] と [SettingsActivity] から同じ流れを呼ぶためにここへ置く。
 * **IME サービスからは呼ばない**（入力中にダイアログや通信が走らないように）。
 *
 * インストールは必ずシステムのインストーラーに任せる。
 * 署名の検証もそちらが行うので、今入っているものと署名が違えば弾かれる。
 */
object AppUpdateUi {

    /** ダウンロードを途中でやめたいときに立てる。 */
    private var cancelled = false

    /**
     * 更新を確認し、あれば案内する。
     *
     * @param manual 手動確認なら true。自動確認のときは、
     *               「最新です」も失敗も黙って何も出さない（勝手に邪魔しないため）。
     */
    fun checkAndOffer(activity: Activity, manual: Boolean, onDone: () -> Unit = {}) {
        AppUpdater.check(activity) { p ->
            if (activity.isFinishing || activity.isDestroyed) return@check
            when (p) {
                is AppUpdater.Progress.Available -> offer(activity, p.release)
                AppUpdater.Progress.UpToDate ->
                    if (manual) toast(activity, R.string.update_up_to_date)

                is AppUpdater.Progress.Failed ->
                    if (manual) toast(activity, failureMessage(p.reason))

                else -> Unit
            }
            onDone()
        }
    }

    /** 更新があることを知らせて、落とすかどうかを尋ねる。 */
    private fun offer(activity: Activity, release: AppUpdater.Release) {
        val size = release.sizeBytes / (1024.0 * 1024.0)
        val body = activity.getString(
            R.string.update_available_body,
            release.version,
            BuildConfig.VERSION_NAME,
            size,
            release.notes,
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, release.version))
            .setMessage(body)
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** 進捗をダイアログに出しながら落とす。キャンセルできる。 */
    private fun startDownload(activity: Activity, release: AppUpdater.Release) {
        cancelled = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setMessage(activity.getString(R.string.update_progress, 0, 0))
            .setNegativeButton(R.string.update_cancel) { _, _ -> cancelled = true }
            .setCancelable(false)
            .create()
        dialog.show()

        AppUpdater.download(activity, release, { cancelled }) { p ->
            if (activity.isFinishing || activity.isDestroyed) {
                cancelled = true
                return@download
            }
            when (p) {
                is AppUpdater.Progress.Downloading -> dialog.setMessage(
                    activity.getString(
                        R.string.update_progress,
                        (p.bytes / (1024 * 1024)).toInt(),
                        (p.total / (1024 * 1024)).toInt(),
                    ),
                )

                is AppUpdater.Progress.Ready -> {
                    dialog.dismiss()
                    install(activity, p.apk)
                }

                is AppUpdater.Progress.Failed -> {
                    dialog.dismiss()
                    toast(activity, failureMessage(p.reason))
                }

                else -> Unit
            }
        }
    }

    /**
     * システムのインストーラーへ渡す。
     *
     * Android 8 以降は「不明なアプリのインストール」を個別に許可する必要がある。
     * 未許可ならその設定画面へ案内してから戻ってきてもらう。
     */
    fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_need_permission_title)
                .setMessage(R.string.update_need_permission_body)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"),
                            ),
                        )
                    }
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, R.string.update_install_failed) }
    }

    private fun failureMessage(reason: AppUpdater.Failure): Int = when (reason) {
        AppUpdater.Failure.NETWORK -> R.string.update_failed_network
        AppUpdater.Failure.NO_ASSET -> R.string.update_failed_no_asset
        AppUpdater.Failure.NO_SPACE -> R.string.update_failed_space
        AppUpdater.Failure.VERIFY -> R.string.update_failed_verify
    }

    private fun toast(activity: Activity, res: Int) {
        Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
    }
}
