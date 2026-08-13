#!/bin/sh
# リリース署名用の環境変数を macOS キーチェーンから読み込む。
#
#   source tools/release-env.sh
#   ./gradlew assembleRelease
#
# 事前に一度だけ登録しておく（対話でパスワードを聞かれる。-w を最後に置くのが要点で、
# こう書くとプロンプトになり、シェル履歴にもプロセス一覧にもパスワードが残らない）:
#
#   security add-generic-password -a "$USER" -s unistroke-keystore -U -w
#
# このスクリプトはパスワードを表示しない。

SERVICE=unistroke-keystore

if ! security find-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1; then
    echo "キーチェーンに '$SERVICE' が見つかりません。先に登録してください:" >&2
    echo "  security add-generic-password -a \"\$USER\" -s $SERVICE -U -w" >&2
    return 1 2>/dev/null || exit 1
fi

UNISTROKE_STORE_PASSWORD=$(security find-generic-password -a "$USER" -s "$SERVICE" -w) || {
    echo "キーチェーンからの読み出しに失敗しました" >&2
    return 1 2>/dev/null || exit 1
}
# keytool でストアと鍵に同じパスワードを設定した前提。別にした場合はここを分ける。
UNISTROKE_KEY_PASSWORD="$UNISTROKE_STORE_PASSWORD"
# 呼び出し側で上書きできるようにしておく
UNISTROKE_STORE_FILE="${UNISTROKE_STORE_FILE:-release.jks}"
UNISTROKE_KEY_ALIAS="${UNISTROKE_KEY_ALIAS:-unistroke}"

export UNISTROKE_STORE_PASSWORD UNISTROKE_KEY_PASSWORD
export UNISTROKE_STORE_FILE UNISTROKE_KEY_ALIAS

echo "署名用の環境変数を設定しました（storeFile=$UNISTROKE_STORE_FILE / alias=$UNISTROKE_KEY_ALIAS）"
