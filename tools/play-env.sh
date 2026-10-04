#!/bin/sh
# Google Play へ上げる App Bundle の署名（アップロード鍵）用の環境変数を、
# macOS キーチェーンから読み込む。
#
#   source tools/play-env.sh
#   ./gradlew bundlePlayRelease
#
# 出力: app/build/outputs/bundle/playRelease/app-play-release.aab
#
# 鍵は ~/keystores/unistroke-upload.jks、パスワードはキーチェーンの
# 'unistroke-upload-keystore' にある（リポジトリには置かない）。
# GitHub 版の署名鍵（tools/release-env.sh）とは別の鍵。
#
# このスクリプトはパスワードを表示しない。

SERVICE=unistroke-upload-keystore

if ! security find-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1; then
    echo "キーチェーンに '$SERVICE' が見つかりません。" >&2
    return 1 2>/dev/null || exit 1
fi

UNISTROKE_UPLOAD_STORE_PASSWORD=$(security find-generic-password -a "$USER" -s "$SERVICE" -w) || {
    echo "キーチェーンからの読み出しに失敗しました" >&2
    return 1 2>/dev/null || exit 1
}
# ストアと鍵は同じパスワード（PKCS12）
UNISTROKE_UPLOAD_KEY_PASSWORD="$UNISTROKE_UPLOAD_STORE_PASSWORD"
# 呼び出し側で上書きできるようにしておく
UNISTROKE_UPLOAD_STORE_FILE="${UNISTROKE_UPLOAD_STORE_FILE:-$HOME/keystores/unistroke-upload.jks}"
UNISTROKE_UPLOAD_KEY_ALIAS="${UNISTROKE_UPLOAD_KEY_ALIAS:-upload}"

export UNISTROKE_UPLOAD_STORE_PASSWORD UNISTROKE_UPLOAD_KEY_PASSWORD
export UNISTROKE_UPLOAD_STORE_FILE UNISTROKE_UPLOAD_KEY_ALIAS

echo "Play アップロード用の環境変数を設定しました（storeFile=$UNISTROKE_UPLOAD_STORE_FILE / alias=$UNISTROKE_UPLOAD_KEY_ALIAS）"
