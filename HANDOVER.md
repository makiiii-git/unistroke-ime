# Uni-Stroke IME 引き継ぎ資料

作成日: 2026-09-02　対象: `main` ブランチ（v1.2.4 / versionCode 8 時点）

この資料は、プロジェクトを初めて引き継ぐ人が **「何がどこにあり、どう直し、どう配るか」** を
一通り把握するためのものです。利用者向けの説明は [README.md](README.md)、辞書ツールと
認識器の設計メモは [tools/README.md](tools/README.md) にあるので、重複する部分はそちらへ
リンクし、この資料では **運用・判断・落とし穴** に重点を置いています。

---

## 1. プロジェクトの要約

| 項目 | 内容 |
| --- | --- |
| 何か | 一筆書きストロークで日本語を入力する Android IME（InputMethodService） |
| 配布 | GitHub Releases の署名済み APK（ストア配布なし）。アプリ内に更新確認あり |
| 現在の版 | v1.2.4（versionCode 8）、2026-08-31 リリース |
| 対応端末 | Android 8.0（API 26）以上。音声入力の端末内認識は Android 13 以降 |
| 言語 | Kotlin（アプリ本体）、Python（検証ハーネス・辞書ビルド） |
| ライセンス | 本体 Apache-2.0。同梱辞書は Mozc `dictionary_oss`（BSD-3-Clause） |
| 外部依存 | `androidx.core:core-ktx` のみ。ネットワークライブラリ・DI・Compose は使っていない |
| リポジトリ | https://github.com/makiiii-git/unistroke-ime |

### 譲れない方針（設計の前提）

1. **入力中に通信しない。** IME サービスからネットへ出るのは「ネット変換」をオプトインした場合だけ。
   更新確認はアプリ画面（Activity）側でしか走らせない。
2. **オフラインで完結する。** かな漢字変換は同梱辞書（コア 8 万語）で成立し、拡張辞書は任意。
3. **署名証明書を変えない。** 変えると既存利用者が上書き更新できなくなる。CI が指紋を照合して止める。
4. **Kotlin と Python の二重定義を許さない。** テストは Kotlin ソースを正規表現で読み、
   定数・テーブル・字形を Python 側へ写して検証する。片方だけ直すとテストが落ちる（意図的）。

---

## 2. 現在の状態

### 2.1 リリース履歴

| タグ | 日付 | 主な内容 |
| --- | --- | --- |
| dict-v1 | 2026-08-13 | 拡張辞書 v1（約 22 万語）。`dictionary/manifest.json` が今もこれを指す |
| v1.0 | 2026-08-14 | 初回リリース。署名証明書の指紋はこの APK から採った |
| v1.1.0 / v1.1.1 | 2026-08-14 | アプリ更新確認、署名の CI 一本化、認識精度の修正 |
| v1.2.0 | 2026-08-17 | 音声入力（長押し）、ボイスコマンド |
| v1.2.2 | 2026-08-22 | 学習データの文字単位リセット、画面改善（v1.2.1 は欠番） |
| v1.2.3 | 2026-08-28 | 連続記入の認識劣化修正、記号の全角/半角切り分け |
| v1.2.4 | 2026-08-31 | かな入力の子音バックスペース復帰、大文字始まり英単語の継続入力 |

### 2.2 未完了・オープンな作業

- Open な Issue と Pull Request は **0 件**（2026-09-02 時点）。
- 拡張辞書は v1 から更新していない。`dictionary-watch` ワークフローが毎月ハッシュを比べ、
  変化があれば Issue を自動で立てる（3 節参照）。
- 検証ハーネスは手元で全スイート PASS（2026-09-02、Python 3.11）。CI は Python 3.12 で回る。

### 2.3 既知の制約（直せない、または直さないと決めたもの）

| 事象 | 理由と扱い |
| --- | --- |
| 傾けて書いた `i` が `#return`（改行）になる | 数値的に分離できない（8 度程度が限界）。傾きバリアントを足すと改行を誤爆するので採用しない。README で「縦に書く」と案内 |
| ごく浅い波形の `e` が `i` になる（約 20%） | 幾何学的にほぼ直線なので原理的な限界。深い波は 3 バリアントで対応済み |
| 音声入力を声だけで起動できない | Android がホットワードをアプリに開放していない |
| 端末内音声認識は Android 13 以降 | 既定の「端末内のみ」ではそれ未満の端末で音声入力が動かない。設定「自動」で端末のサービスへ渡せる |
| Python テストは本物のコンパイルではない | 括弧バランス・参照整合までは見るが、型エラーは CI の `assembleDebug` でしか分からない |
| Android の instrumented テストが無い | 実機での確認は手動。速書き調査用に設定「デバッグ: ストロークログ」がある |

---

## 3. 運用手順

### 3.1 リリース（アプリの版を上げる）

すべて GitHub Actions の「リリース」ワークフロー（`.github/workflows/release.yml`）で行う。
ローカルで署名済み APK を作る手順は **無い**（意図的に CI へ一本化してある）。

1. `app/build.gradle.kts` の `versionCode`（+1）と `versionName`（メジャー.マイナー.パッチ）を上げる。
   これが唯一の情報源で、ワークフローはここから読む。
2. 「vX.Y.Z（説明）」の件名でコミットし、`main` へ push する。
   件名が `v数字` で始まるコミットは変更履歴から自動で除かれる。
3. Actions タブ → 「リリース」 → Run workflow。入力は次の 3 つ。

   | 入力 | 既定 | 意味 |
   | --- | --- | --- |
   | `dry_run` | false | ビルド・署名・指紋照合まで行い、リリース作成とマニフェスト更新だけ飛ばす |
   | `force_dictionary` | false | 辞書に変化が無くても作り直して配る |
   | `dict_limit` | 220000 | 拡張辞書の語数上限 |

4. ワークフローが行うこと（順に）:
   バージョン読み取りと重複タグ確認 → 拡張辞書ビルドとハッシュ比較 → `run_all.py` →
   署名鍵の確認 → `assembleRelease` → **証明書指紋を `.github/expected-signing-cert.txt` と照合** →
   前回タグからの変更履歴を生成 → リリース作成（APK と、変化があれば辞書を添付）→
   辞書が変わった場合のみ `dictionary/manifest.json` を `main` へコミット。
5. 終わったら Releases ページで APK が添付されていることと、端末側の「アプリの更新確認」で
   新版が見えることを確認する。

**署名 APK を試したいだけ**なら `dry_run: true` で実行し、Actions の生成物
`release-vX.Y.Z` から取り出す（README「署名済み APK をリリースせずに入手する」）。

### 3.2 拡張辞書の更新

- 辞書は **再現可能ビルド**（同じ入力なら同じバイト列）。「変わったかどうか」はハッシュ比較で決める。
- `dictionary-watch.yml` が毎月 28 日 19:00 UTC（翌 1 日 04:00 JST）と、
  `tools/build_dictionary.py` を `main` へ push したときに辞書を作り直し、
  配布中と違えば Issue を立てる（勝手には配らない）。
- 配るときは通常の「リリース」を回すだけでよい。辞書の変化は自動検知され、
  `dictVersion` が +1 され、APK と同じリリースに `ondevice-ext.dic` が載り、マニフェストが更新される。
- Issue に書かれる「文字単位の一致率」が下がっていたら配らず、
  `tools/build_dictionary.py` の絞り込み・コスト調整を見直す。

コア辞書（APK 同梱、`app/src/main/assets/ondevice.dic`）は **手動で作ってコミット**する。
リリースワークフローは触らない。

```bash
python3 tools/build_dictionary.py --fetch --limit 80000   # 出力先の既定が assets
python3 test_dictionary.py && python3 test_ondevice.py
```

8 万語は「サイズと品質の折れ点」として実測で決めた値（30k=0.780 / 80k=0.845 / 220k=0.855）。
コア辞書を差し替えたら `test_dictionary.py` の期待レンジも合わせて見直すこと。

### 3.3 秘密情報と引き継ぐべき権限

| もの | 場所 | 備考 |
| --- | --- | --- |
| リリース署名鍵（`.jks`） | GitHub Secrets `ANDROID_KEYSTORE_BASE64` に base64 で格納。**原本はリポジトリに無い** | **前任者からオフラインのバックアップを必ず受け取ること。** 失うと以後アップデートを配れない |
| ストアのパスワード | Secrets `ANDROID_STORE_PASSWORD` | |
| 鍵のエイリアス | Secrets `ANDROID_KEY_ALIAS`（`unistroke`） | |
| 鍵のパスワード | Secrets `ANDROID_KEY_PASSWORD` | ストアと同じ値で運用している前提のスクリプトあり（`tools/release-env.sh`） |
| 署名証明書の SHA-256 指紋 | `.github/expected-signing-cert.txt`（公開情報） | 鍵を替えるときはここも更新。v3 署名を有効にしてあるので `apksigner rotate` で系譜を作れる |
| GitHub リポジトリの管理権限 | makiiii-git | Actions の実行・Secrets の閲覧/更新・Issue の自動作成に必要 |

Secrets が 1 つでも欠けると、本番実行は「署名鍵があることを確認」ステップで止まる
（`dry_run` のときだけ警告で通す）。

ローカルで署名設定を持つ場合は 3 段階で解決される（Gradle プロパティ → 環境変数
`UNISTROKE_*` → `keystore.properties`）。詳しくは [keystore.properties.example](keystore.properties.example)。

---

## 4. 開発環境

### 4.1 必要なもの

| 用途 | 必要なもの |
| --- | --- |
| Python テスト・辞書ビルド | Python 3.11 以上。外部パッケージ不要（標準ライブラリのみ） |
| APK ビルド | JDK 17、Android SDK（compileSdk 35）。Gradle 8.10.2 は wrapper が落とす |
| 辞書の素材 | `--fetch` で `tools/mozc-src/` に約 93 MB を取得（gitignore 済み） |

`JAVA_HOME` 未設定なら `~/.gradle/gradle.properties` に `org.gradle.java.home` を書く。
`local.properties`（SDK の場所）もコミットしない。

### 4.2 日常のコマンド

```bash
python3 run_all.py            # 検証ハーネス全部（数分）
python3 run_all.py -q         # 結果行だけ
python3 test_recognition.py   # 単体で回すときはスクリプト名で
./gradlew assembleDebug       # デバッグ APK（app/build/outputs/apk/debug/）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`BuildConfig.BUILD_TIME` は **ソースの最終更新時刻**（`currentTimeMillis` ではない）。
「新しい APK を入れたつもりが古かった」を端末の設定画面で見分けるためのもの。

### 4.3 CI（`.github/workflows/tests.yml`）

`main` への push と PR で 2 ジョブが走る。

- `python-suite`: `run_all.py`。Android SDK 不要。
- `build`: `assembleDebug`。ここが唯一の「本物のコンパイル」で、生成物 `app-debug` を保存する。

---

## 5. コードの地図

### 5.1 ディレクトリ

```
app/src/main/java/com/unistroke/ime/   Kotlin 本体（31 ファイル・約 11,700 行）
app/src/main/assets/ondevice.dic       コア辞書（2.5 MB、無圧縮で同梱）
app/src/main/res/                      レイアウト・文字列（すべて日本語）・テーマ
dictionary/manifest.json               拡張辞書の配布マニフェスト（アプリが raw URL で読む）
tools/build_dictionary.py              Mozc → バイナリ辞書
tools/make_manifest.py                 辞書 → manifest.json
tools/release-env.sh                   macOS キーチェーンから署名パスワードを読む補助
*.py（ルート）                          検証ハーネス（下記 6 節）
.github/workflows/                     tests / release / dictionary-watch
.github/expected-signing-cert.txt      署名証明書の指紋（ピン留め）
```

### 5.2 入力パイプライン

```
タッチ ──▶ UniStrokeView ──▶ StrokeRecognizer ──▶ UniStrokeIME ──▶ InputConnection
           (描画・ゾーン・      (テンプレート照合・      (状態機械・かな合成・
            ボタン・長押し)      ゲート・個人テンプレ)    変換・候補・確定)
                                     ▲                       │
                          PersonalTemplateStore     ┌────────┼─────────┐
                          StrokeLearner             ▼        ▼         ▼
                                            RomajiConverter  OnDeviceConverter  GoogleConvertClient
                                                             (端末内・既定)      (ネット変換・任意)
                                                                    │
                                                             PredictionEngine ＋ PhraseDictionary
```

1. **UniStrokeView** がタッチを集め、ゾーン（左 2/3 英字・右 1/3 数字）と記号モードに応じた
   `StrokeRecognizer` で認識し、`Listener.onSymbol(symbol, stroke, zone)` を投げる。
   タップ・長押し（音声）・ボタン列・パネルのドラッグもここ。
2. **StrokeRecognizer** は正規化 → 回転補正（±15 度）→ コサイン類似度。その前に
   「形の性質」によるゲート（直線らしさ・横揺れ・折り返し・傾き）で候補を絞る。
   閾値と根拠は companion object のコメントに実測値付きで書いてある。
3. **UniStrokeIME** が入力モード（abc / かな / カナ）、シフト、一時アルファベット、
   自動アルファベット化、確定アンドゥ、合成領域の所有者管理を持つ。2,259 行あり最も複雑。
   `onCharacter` → `emitSymbol` / `appendSymbolToComposing` → `updateComposing` が中心線。
4. **変換**は `Prefs.convertEngine` で切り替え。既定は端末内。ネット変換はオプトインかつ
   パスワード欄・URL 欄・シークレットタブなどでは `isNoNetworkField` で止める。

### 5.3 コンポーネント一覧

| ファイル | 役割 | 触るときの注意 |
| --- | --- | --- |
| `UniStrokeIME.kt` | IME サービス本体。状態機械のすべて | `test_ime_sequence.py` が状態機械を Python に写している。挙動を変えたらテストも直す |
| `UniStrokeView.kt` | 入力パネルの描画・タッチ・候補バー・音声バナー・見本オーバーレイ | `fillTouchableRegion` で大画面の透過領域を決める |
| `PanelLayout.kt` | パネル配置の純粋計算（利き手・展開時の寄せ） | Android 非依存 |
| `StrokeTemplates.kt` | 字形定義（正規化座標のポリライン） | 追加はバリアントとして。`test_recognition.py` で自己認識率とマージンを確認 |
| `StrokeRecognizer.kt` | 認識器とゲート | 定数は `unistroke_model.py` が読む。名前を変えると Python 側が落ちる |
| `SampleStrokes.kt` / `StrokeArt.kt` | 見本の描画（K/X の ∝ 字形は `unistroke_geom.py` と同じ式） | |
| `PersonalTemplateStore.kt` | 個人テンプレート（`personal_strokes.json`） | 訂正 2 回で昇格。衝突ガードあり |
| `StrokeLearner.kt` | 訂正パターン検出の状態機械 | Android 非依存 |
| `TrainingActivity/Session/Views.kt` | 書き方トレーニング（8 文字） | 縦向き固定 |
| `RomajiConverter.kt` | ローマ字 → かな。`expectedNext` が文脈バイアスの元 | `test_romaji.py` / `test_context_bias.py` |
| `OnDeviceDictionary.kt` | バイナリ辞書のメモリマップ読み取り | オフセット定義は `test_dictionary.py` が突き合わせる |
| `OnDeviceConverter.kt` | ラティス構築と Viterbi、前方一致予測 | コスト定数は `ondevice_model.py` と一致必須（`test_ondevice.py`） |
| `GoogleConvertClient.kt` | ネット変換（Google 日本語入力 CGI API） | 文字コードのフォールバックは `test_charset.py` |
| `PredictionEngine.kt` | 履歴ベース予測（`prediction_history.json`、最大 500 件） | |
| `PhraseDictionary.kt` | 内蔵フレーズ約 350 件 | |
| `VoiceInput.kt` / `VoiceCommands.kt` | 音声入力ラッパと発話コマンド表 | 表は `test_voice_commands.py` が Kotlin から読む。設定画面の一覧文字列と対応させる |
| `DictionaryUpdater.kt` | 拡張辞書の取得（落とす → 検証 → リネーム） | 受け入れ条件は `test_updater.py` がマニフェストと突き合わせる |
| `AppUpdater.kt` / `AppUpdateUi.kt` | アプリ更新確認（GitHub Releases API）とインストーラー起動 | IME サービスからは絶対に呼ばない |
| `Prefs.kt` | 設定キーの一元管理 | キーを増やしたらここに定数を置く |
| `MainActivity.kt` | 有効化案内、初回セットアップ（ネット変換の可否 → 拡張辞書 → トレーニング）、自動更新確認 | |
| `SettingsActivity.kt` / `ResetLearningActivity.kt` / `LicenseActivity.kt` | 設定・学習リセット・ライセンス表示 | ライセンス表示は BSD-3-Clause の帰属条件を満たすためのもの。消さない |

### 5.4 端末内に残るデータ

| ファイル（`filesDir`） | 内容 | 消し方 |
| --- | --- | --- |
| `personal_strokes.json` | 個人テンプレートと候補 | 設定 → 学習データのリセット（文字単位） |
| `prediction_history.json` | 確定履歴（読み → 表記、最大 500 件） | 設定画面から |
| `ondevice-ext.dic` | 拡張辞書 | 設定 → 拡張辞書を削除 |
| `cacheDir/updates/` | ダウンロードした更新 APK | 自動で掃除 |

SharedPreferences 名は `unistroke`。キーは `Prefs.kt` を参照。

---

## 6. 検証ハーネス（Python）

JDK 無しで回帰を検出するために、Kotlin ソースを読んで Python 側に同じモデルを組む方式。
`unistroke_model.py` が `SRC[...]` に Kotlin 全文を持ち、定数・テンプレート・テーブルを
正規表現で抜き出す。**Kotlin 側の識別子や書式を変えると、まずここが落ちる**。

| スイート | 見るもの |
| --- | --- |
| `test_static.py` | 括弧バランス、`Type.MEMBER` 参照の実在、`R.*` と XML の整合、XML の well-formed |
| `test_romaji.py` | ローマ字 → かなのテーブル |
| `test_ime_sequence.py` | IME 状態機械（ストローク列 → 確定文字列・合成内容・チップ）。最大のスイート |
| `test_charset.py` | ネット変換レスポンスの文字コード判定（◆ 化け対策） |
| `test_voice_commands.py` | 発話コマンド表と正規化 |
| `test_recognition.py` | 合成ストロークによる自己認識率・マージン、K/X 字形の連続性 |
| `test_context_bias.py` | かなモードの文脈バイアスの救済/誤爆（`--sweep` で掃引） |
| `test_dictionary.py` | 辞書バイナリの整合、Kotlin のオフセット定義との一致 |
| `test_ondevice.py` | コスト定数の一致、20 文の変換品質、予測、所要時間 |
| `test_updater.py` | マニフェストと Kotlin の受け入れ条件（schema / format / サイズ / SHA-256） |

`stroke_sim.py` が「人が指で書いた」ストロークを合成する（相関のある手ぶれ、丸まり、間引き）。
認識パラメータを変えるときは、このモデルで率とマージン中央値を見て決めるのが慣例で、
根拠は Kotlin のコメントと `tools/README.md` に残してある。

`UNISTROKE_DIC=dist/ondevice-ext.dic` を付けると辞書系のテストを拡張辞書で回せる。

---

## 7. よくある変更の手順

### 字形（テンプレート）を足す・直す

1. `StrokeTemplates.kt` にバリアントとして追加（既存を置き換えない）。
2. `python3 test_recognition.py` で対象の認識率と、他字形のマージンが落ちていないか見る。
3. 見本に出したい字形なら `SampleStrokes.kt` の OVERRIDES も確認。
4. 直線系（`LINE_SYMBOLS`）や `#return` に関わるものは、ゲートの閾値コメントにある実測を
   再現してから変える。改行・スペース・バックスペースの取り違えは被害が大きい。

### 認識パラメータ（ゲート・閾値）を変える

`StrokeRecognizer.kt` の companion object に実測値付きの根拠がある。変えるなら同じ方法で
測り直し、コメントの数字も更新する。`test_context_bias.py --sweep` のような掃引スクリプトが
参考になる。

### 設定項目を足す

`Prefs.kt` に定数と getter/setter → `SettingsActivity.kt` に UI → 必要なら
`UniStrokeIME.prefsListener` で即時反映 → `strings.xml`。`test_static.py` が R 参照を検査する。

### 音声コマンドを足す

`VoiceCommands.kt` の PHRASES と READINGS に追加し、設定画面の一覧
（`voice_commands_list`）と README の表も合わせる。「文の一部として出てきそうな短い語」は避ける。

### 辞書の絞り込みやコストを調整する

`tools/build_dictionary.py` を変更して `main` へ push すると `dictionary-watch` が走り、
変化と一致率を Issue で報告する。良ければリリースワークフローで配る。

### ネットワーク経路を増やす

原則として増やさない。増やすなら README のプライバシー表・`AndroidManifest.xml` のコメント・
`isNoNetworkField` の除外条件を同時に更新し、IME サービスからは呼ばない。

---

## 8. 落とし穴集

- **`noCompress += "dic"` を外さない。** 圧縮されるとメモリマップできず、起動時に辞書を
  ヒープへ展開することになる。拡張子を変えるならここも直す。
- **`versionCode` を上げ忘れるとリリースが止まる**（同名タグの存在チェック）。
  `versionName` は 3 段階（`1.2.4`）。初期の `v1.0` は 2 段だが `AppUpdater.compareVersions`
  が吸収している。
- **マニフェストのコミットはリリース作成のあと。** 順序を入れ替えると「マニフェストは新しいのに
  ファイルが無い」瞬間ができる。手で直すときも同じ順序を守る。
- **`dictionary/manifest.json` は手で書かない。** `tools/make_manifest.py` が実物から
  サイズ・SHA-256・語数を読む。
- **Kotlin のコメントに含まれる `"x" -> "y"` 形式や `const val NAME = 数値` を、
  Python 側が正規表現で読んでいる。** 書式を変えるときは `unistroke_model.py` と各テストを確認。
- **合成領域の所有者チェック**（`composingOwner`）を外さない。入力欄が切り替わった直後に
  `setComposingText("")` を送ると新しい欄の選択範囲を消す。
- **`isNoNetworkField` が判定できない欄は「送らない」側に倒す。** 緩めない。
- **辞書の素材は CC BY-SA を混ぜない**（Mozc UT など）。`dictionary_oss` だけを使う。

---

## 9. 参照

- [README.md](README.md) — 利用者向け説明、プライバシー表、ボイスコマンド一覧、書き方
- [tools/README.md](tools/README.md) — 辞書のバイナリ形式、トリミング方針、接続コストの畳み込み、
  認識器のゲート設計と実測データ
- [keystore.properties.example](keystore.properties.example) — 署名設定の 3 段階の解決順
- [.github/workflows/release.yml](.github/workflows/release.yml) — リリース手順の実体
- [NOTICE](NOTICE) — 同梱データの帰属表示
- `git log` — コミット本文に設計判断と実測値が残っているので、変更前に該当ファイルの履歴を読むと早い
