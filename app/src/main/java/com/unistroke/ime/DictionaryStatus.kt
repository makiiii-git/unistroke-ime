package com.unistroke.ime

import android.content.Context
import android.widget.Toast

/**
 * [DictionaryUpdater.Progress] を利用者向けの文言にする。
 *
 * セットアップの案内（[MainActivity]）と設定画面（[SettingsActivity]）の
 * どちらからも同じ言い方になるように、変換をここへ集約する。
 */
object DictionaryStatus {

    /** 進行中の状態を 1 行で表す。 */
    fun message(context: Context, p: DictionaryUpdater.Progress): String = when (p) {
        DictionaryUpdater.Progress.Checking -> context.getString(R.string.dict_checking)

        is DictionaryUpdater.Progress.Downloading -> {
            val total = p.total.coerceAtLeast(1L)
            val percent = ((p.bytes * 100) / total).toInt().coerceIn(0, 100)
            context.getString(
                R.string.dict_downloading,
                percent,
                p.bytes / 1048576.0,
                p.total / 1048576.0,
            )
        }

        DictionaryUpdater.Progress.Verifying -> context.getString(R.string.dict_verifying)
        DictionaryUpdater.Progress.UpToDate -> context.getString(R.string.dict_up_to_date)
        is DictionaryUpdater.Progress.Available ->
            context.getString(R.string.dict_btn_download)

        is DictionaryUpdater.Progress.Done ->
            context.getString(R.string.dict_done, p.manifest.dictVersion, p.manifest.words)

        is DictionaryUpdater.Progress.Failed -> failureMessage(context, p.reason)
    }

    /** 終わった状態（成功・失敗・最新）だけを知らせる。 */
    fun toast(context: Context, p: DictionaryUpdater.Progress) {
        val text = when (p) {
            is DictionaryUpdater.Progress.Done,
            is DictionaryUpdater.Progress.Failed,
            DictionaryUpdater.Progress.UpToDate,
            -> message(context, p)

            else -> return
        }
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /**
     * 失敗の理由。
     * どれも「取り込まなかった」だけで、今入っている辞書は無傷であることが伝わる言い方にする。
     */
    private fun failureMessage(context: Context, reason: DictionaryUpdater.Failure): String =
        context.getString(
            when (reason) {
                DictionaryUpdater.Failure.NETWORK -> R.string.dict_fail_network
                DictionaryUpdater.Failure.MANIFEST -> R.string.dict_fail_manifest
                DictionaryUpdater.Failure.INCOMPATIBLE -> R.string.dict_fail_incompatible
                DictionaryUpdater.Failure.SIZE -> R.string.dict_fail_size
                DictionaryUpdater.Failure.CHECKSUM -> R.string.dict_fail_checksum
                DictionaryUpdater.Failure.BROKEN -> R.string.dict_fail_broken
                DictionaryUpdater.Failure.STORAGE -> R.string.dict_fail_storage
            },
        )
}
