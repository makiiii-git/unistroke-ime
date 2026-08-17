package com.unistroke.ime

// 定数を直接取り込むのは、android.Manifest が配布マニフェスト
// （[DictionaryUpdater.Manifest]）と名前でぶつかるため。
import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * マイクの許可を求めるためだけの透明な画面。
 *
 * IME サービスは自分で権限ダイアログを出せない（Activity が要る）ので、
 * 音声入力を初めて使うときだけここを一瞬経由する。許可の可否を伝えたら即座に閉じる。
 *
 * 「今後表示しない」で断られている場合は権限ダイアログが出ないまま拒否が返るので、
 * そのときだけアプリ情報画面への導線を出す（黙って閉じると何も起きないように見える）。
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (granted()) {
            done(R.string.voice_permission_granted)
            return
        }
        requestPermissions(arrayOf(RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        when {
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED ->
                done(R.string.voice_permission_granted)

            // ダイアログすら出ていない（＝恒久的に拒否されている）ときだけ設定へ案内する
            !shouldShowRequestPermissionRationale(RECORD_AUDIO) ->
                offerAppSettings()

            else -> done(R.string.voice_permission_denied)
        }
    }

    private fun granted(): Boolean =
        checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun offerAppSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_permission_title)
            .setMessage(R.string.voice_permission_blocked)
            .setPositiveButton(R.string.voice_permission_open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
                finish()
            }
            .setNegativeButton(R.string.voice_permission_later) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun done(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val REQUEST_MIC = 1
    }
}
