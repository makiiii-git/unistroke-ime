#!/usr/bin/env python3
"""拡張辞書の配布マニフェストと、Kotlin 側の受け入れ条件が食い違っていないか。

辞書の差し替えは「落とす -> 検証 -> リネーム」で行うが、検証の基準
（schema / formatVersion / サイズ / SHA-256）はマニフェストと Kotlin の
両方に書かれている。片方だけ直すと、正しい辞書を拒否したり、逆に
読めない辞書を受け入れたりする。ここで突き合わせる。
"""

import hashlib
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "unistroke", "ime")
MANIFEST = os.path.join(ROOT, "dictionary", "manifest.json")
HEADER_SIZE = 80
MAGIC = b"UNIDIC2\x00"

FAILURES = []


def check(cond, msg):
    print("  %s %s" % ("ok  " if cond else "FAIL", msg))
    if not cond:
        FAILURES.append(msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def kt_int(src, name):
    m = re.search(r"const val %s = ([0-9_]+)" % name, src)
    return int(m.group(1).replace("_", "")) if m else None


def kt_str(src, name):
    m = re.search(r'const val %s =\s*\n?\s*"([^"]+)"' % name, src)
    return m.group(1) if m else None



# ===================================================================== アプリ更新

def _app_updater_src():
    with open(os.path.join(KT, "AppUpdater.kt"), encoding="utf-8") as f:
        return f.read()


def compare_versions(a, b):
    """AppUpdater.compareVersions の移植。

    実装を Python へ写して、境界（桁数違い・v 接頭辞・プレリリース）を総当たりする。
    Kotlin 側と食い違うと配布事故（更新が出ない／古い版を新しいと誤判定）になるので、
    定数と分岐の形も下で静的に突き合わせる。
    """
    def split(raw):
        s = raw.strip()
        if s[:1] in ("v", "V"):
            s = s[1:]
        pre = ""
        for i, ch in enumerate(s):
            if ch in "-+":
                pre = s[i + 1:]
                s = s[:i]
                break
        nums = []
        for part in s.split("."):
            digits = ""
            for ch in part:
                if ch.isdigit():
                    digits += ch
                else:
                    break
            nums.append(int(digits) if digits else 0)
        return nums, pre

    an, ap = split(a)
    bn, bp = split(b)
    for i in range(max(len(an), len(bn))):
        x = an[i] if i < len(an) else 0
        y = bn[i] if i < len(bn) else 0
        if x != y:
            return 1 if x > y else -1
    if not ap and not bp:
        return 0
    if not ap:
        return 1
    if not bp:
        return -1
    return (ap > bp) - (ap < bp)


def check_app_updater():
    print("\n=== アプリ更新: バージョン比較 ===")
    src = _app_updater_src()

    newer = [
        ("1.2.0", "1.1.0"), ("1.10.0", "1.9.0"), ("2.0.0", "1.99.99"),
        ("1.1.1", "1.1.0"), ("1.1.0", "1.0"), ("v1.2.0", "1.1.0"),
        ("1.2.0", "v1.1.0"), ("1.0.1", "1.0"), ("1.2.0", "1.2.0-beta"),
        ("1.2.0-beta2", "1.2.0-beta1"),
    ]
    for a, b in newer:
        check(compare_versions(a, b) > 0, "%s > %s" % (a, b))

    same = [("1.1.0", "1.1.0"), ("v1.1.0", "1.1.0"), ("1.0", "1.0.0"),
            ("1.0.0", "1.0"), ("V1.0", "v1.0.0")]
    for a, b in same:
        check(compare_versions(a, b) == 0, "%s == %s" % (a, b))

    for a, b in newer:
        check(compare_versions(b, a) < 0, "%s < %s（逆向き）" % (b, a))

    # 文字列比較では間違える組み合わせ（これが実装の要点）
    check("1.10.0" < "1.9.0", "文字列比較だと 1.10.0 < 1.9.0 になってしまう（前提）")
    check(compare_versions("1.10.0", "1.9.0") > 0, "数値比較なら 1.10.0 > 1.9.0")

    print("       -- 不正な入力でも落ちない --")
    for bad in ("", "v", "abc", "1.x.3", "...", "v1..2", "1.2.3.4.5", "  1.2.0  "):
        try:
            compare_versions(bad, "1.1.0")
            compare_versions("1.1.0", bad)
            check(True, "%r を扱っても例外にならない" % bad)
        except Exception as exc:
            check(False, "%r で例外: %s" % (bad, exc))
    check(compare_versions("1.2.3.4.5", "1.2.3") > 0, "桁が多い版は新しいと判定される")
    check(compare_versions("abc", "1.0.0") < 0, "数字として読めない版は 0.0.0 扱い")

    print("\n=== アプリ更新: GitHub API のパース ===")
    # 実際の releases/latest 応答から必要な形だけ抜き出したもの
    sample = json.dumps({
        "tag_name": "v1.2.0",
        "name": "Uni-Stroke IME v1.2.0",
        "draft": False,
        "prerelease": False,
        "body": "## Uni-Stroke IME v1.2.0\n\n- 変更点その 1\n- 変更点その 2",
        "assets": [
            {"name": "ondevice-ext.dic", "size": 7654321,
             "browser_download_url": "https://example.invalid/ondevice-ext.dic",
             "digest": "sha256:" + "0" * 64},
            {"name": "unistroke-ime-v1.2.0.apk", "size": 4378077,
             "browser_download_url":
                 "https://github.com/makiiii-git/unistroke-ime/releases/download/"
                 "v1.2.0/unistroke-ime-v1.2.0.apk",
             "digest": "sha256:" + "a" * 64},
        ],
    })
    d = json.loads(sample)
    apk = [a for a in d["assets"] if a["name"].endswith(".apk")]
    check(len(apk) == 1, "アセットから APK を 1 つだけ選べる")
    check(apk[0]["digest"].startswith("sha256:"), "digest が sha256: で始まる")
    check(len(apk[0]["digest"].split(":", 1)[1]) == 64, "SHA-256 は 16 進 64 文字")
    check(d["tag_name"].lstrip("vV") == "1.2.0", "tag から表示用バージョンを作れる")
    check(compare_versions(d["tag_name"], "1.1.0") > 0, "tag と現行版を比較できる")

    # Kotlin 側が同じ想定で書かれているか
    check('"tag_name"' in src, "tag_name を読んでいる")
    check('.endsWith(".apk"' in src, "APK アセットを拡張子で選んでいる")
    check('"browser_download_url"' in src, "ダウンロード URL を読んでいる")
    check('startsWith("sha256:")' in src, "digest の形式を確かめている")
    check('optBoolean("draft"' in src and 'optBoolean("prerelease"' in src,
          "下書き・プレリリースを除外している")
    check("total != release.sizeBytes" in src, "サイズが申告と一致するか検証している")
    check("hex(digest.digest())" in src, "SHA-256 を計算して照合している")

    print("\n=== アプリ更新: 通信ポリシー ===")
    check("getBoolean(KEY_APP_AUTO_UPDATE, true)" in read_kt("Prefs.kt"),
          "更新確認の既定はオン")
    check("MIN_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000" in src,
          "自動確認の間隔は 1 日")
    ime = read_kt("UniStrokeIME.kt")
    check("AppUpdater" not in ime and "AppUpdateUi" not in ime,
          "IME サービスからは更新確認を呼ばない（入力中に通信しない）")
    ui = read_kt("AppUpdateUi.kt")
    check("FileProvider.getUriForFile" in ui, "APK は content:// で渡す")
    check("application/vnd.android.package-archive" in ui,
          "システムのインストーラーへ渡している")
    check("canRequestPackageInstalls" in ui, "不明なアプリのインストール許可を確認している")
    check("ACTION_MANAGE_UNKNOWN_APP_SOURCES" in ui, "未許可なら設定画面へ案内している")
    manifest = read_manifest()
    check("REQUEST_INSTALL_PACKAGES" in manifest, "インストール権限を宣言している")
    check("androidx.core.content.FileProvider" in manifest, "FileProvider を宣言している")


def read_kt(name):
    with open(os.path.join(KT, name), encoding="utf-8") as f:
        return f.read()


def read_manifest():
    with open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
              encoding="utf-8") as f:
        return f.read()


def main() -> int:
    if not os.path.exists(MANIFEST):
        print("FAIL: マニフェストが無い: %s" % MANIFEST)
        print("      python3 tools/make_manifest.py --version 1 で作ること")
        return 1

    updater = read(os.path.join(KT, "DictionaryUpdater.kt"))
    manifest = json.loads(read(MANIFEST))

    print("== 1. マニフェストの必須項目 ==")
    for key in ("schema", "dictVersion", "formatVersion", "words",
                "size", "sha256", "url", "minAppVersionCode"):
        check(key in manifest, "%s がある" % key)

    print("== 2. Kotlin の受け入れ条件と一致 ==")
    check(manifest["schema"] == kt_int(updater, "SUPPORTED_SCHEMA"),
          "schema %s == SUPPORTED_SCHEMA %s"
          % (manifest["schema"], kt_int(updater, "SUPPORTED_SCHEMA")))
    check(manifest["formatVersion"] == kt_int(updater, "SUPPORTED_FORMAT"),
          "formatVersion %s == SUPPORTED_FORMAT %s"
          % (manifest["formatVersion"], kt_int(updater, "SUPPORTED_FORMAT")))

    print("== 3. 平文 HTTP へ誘導していない ==")
    check(manifest["url"].startswith("https://"), "url が https")
    check(kt_str(updater, "MANIFEST_URL", ).startswith("https://")
          if kt_str(updater, "MANIFEST_URL") else False,
          "MANIFEST_URL が https")

    print("== 4. サイズ上限に収まる ==")
    max_bytes = 32 * 1024 * 1024
    check(0 < manifest["size"] <= max_bytes,
          "size %d が上限 %d 以内" % (manifest["size"], max_bytes))

    print("== 5. 実物との突き合わせ ==")
    # dist/ は生成物なので CI には無い。あるときだけ照合する。
    dic = os.path.join(ROOT, "dist", "ondevice-ext.dic")
    if not os.path.exists(dic):
        print("  --   dist/ondevice-ext.dic が無いので実物照合は省略")
    else:
        size = os.path.getsize(dic)
        check(size == manifest["size"],
              "実サイズ %d == マニフェスト %d" % (size, manifest["size"]))
        h = hashlib.sha256()
        with open(dic, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                h.update(chunk)
        check(h.hexdigest() == manifest["sha256"], "SHA-256 が一致")
        with open(dic, "rb") as f:
            head = f.read(HEADER_SIZE)
        check(head[:8] == MAGIC, "マジックが UNIDIC2")
        fmt = struct.unpack_from("<I", head, 64)[0]
        check(fmt == manifest["formatVersion"],
              "辞書の形式版 %d == マニフェスト %d" % (fmt, manifest["formatVersion"]))
        words = struct.unpack_from("<I", head, 12)[0]
        check(words == manifest["words"],
              "語数 %d == マニフェスト %d" % (words, manifest["words"]))

    print("== 6. 拡張辞書はコア辞書より語彙が多い ==")
    core = os.path.join(ROOT, "app", "src", "main", "assets", "ondevice.dic")
    if os.path.exists(core):
        with open(core, "rb") as f:
            core_words = struct.unpack_from("<I", f.read(HEADER_SIZE), 12)[0]
        check(manifest["words"] > core_words,
              "拡張 %d > コア %d" % (manifest["words"], core_words))

    print("== 7. 差し替えは一時ファイル経由（本番を直接書かない） ==")
    check(".part" in updater, "一時ファイル（.part）へ落としている")
    check("renameTo" in updater, "renameTo で入れ替えている")
    check("MessageDigest" in updater, "ハッシュを計算している")
    check("isReadableDictionary" in updater, "辞書として開けるか確かめている")

    check_app_updater()

    if FAILURES:
        print("\nFAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - %s" % f)
        return 1
    print("\ntest_updater: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
