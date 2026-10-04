import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 端末に入っている APK を識別するためのビルド印（BuildConfig.BUILD_TIME）。
 *
 * 「新しい APK を入れたつもりが古いままだった」という事故を潰すためのもの。
 * 値は **ソースの最終更新時刻**（app/src と build.gradle.kts の中で最も新しい
 * ファイルの mtime）にしてある。
 *
 * System.currentTimeMillis() を埋めると値が毎回変わり、BuildConfig.java が
 * 毎回書き換わって Kotlin の再コンパイル（= モジュール全体）が毎回走る。
 * ソース mtime なら「中身が変わったときだけ変わる」ので、
 *   ・変更したのに古い APK が残っている  -> 表示が変わるので気付ける
 *   ・変更していないビルド                -> 値が同じなので UP-TO-DATE のまま
 * の両方を満たす。TZ は端末とビルド機の差で混乱しないよう JST 固定。
 */
fun sourceBuildStamp(): String {
    var newest = 0L
    for (root in listOf(file("src"), file("build.gradle.kts"))) {
        if (!root.exists()) continue
        root.walkTopDown().forEach { f ->
            if (f.isFile) newest = maxOf(newest, f.lastModified())
        }
    }
    if (newest == 0L) newest = System.currentTimeMillis()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    return fmt.format(Date(newest))
}

/**
 * リリース署名の設定。
 *
 * 鍵とパスワードはリポジトリに置かない。プロジェクト直下の `keystore.properties`
 * （.gitignore 済み）から読み、**ファイルが無ければ署名設定そのものを作らない**。
 * これにより鍵を持たない環境（他のコントリビュータ・CI）でも
 * `assembleDebug` と `assembleRelease` が通る（後者は未署名 APK になる）。
 *
 * 作り方は README の「リリース用の署名」を参照。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * 署名情報を 3 段で解決する。先に見つかったものを使う。
 *
 *   1. Gradle プロパティ … `-PunistrokeStorePassword=…`、環境変数
 *      `ORG_GRADLE_PROJECT_unistrokeStorePassword`、`~/.gradle/gradle.properties`
 *   2. 環境変数 `UNISTROKE_STORE_PASSWORD` など
 *   3. `keystore.properties`（ローカル作業用のフォールバック）
 *
 * 平文をディスクに置きたくない場合は 1 か 2 を使う。CI では 1 が定石。
 */
fun signingValue(gradleProp: String, envVar: String, fileKey: String): String? =
    (project.findProperty(gradleProp) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(fileKey)?.takeIf { it.isNotBlank() }

val signStoreFile = signingValue("unistrokeStoreFile", "UNISTROKE_STORE_FILE", "storeFile")
val signStorePassword = signingValue("unistrokeStorePassword", "UNISTROKE_STORE_PASSWORD", "storePassword")
val signKeyAlias = signingValue("unistrokeKeyAlias", "UNISTROKE_KEY_ALIAS", "keyAlias")
val signKeyPassword = signingValue("unistrokeKeyPassword", "UNISTROKE_KEY_PASSWORD", "keyPassword")

// 4 つ揃っていて、かつ鍵ファイルが実在するときだけ署名する。
// 一部だけ埋まっている状態で署名を試すと分かりにくいエラーになるので、
// 何が足りないかをビルドログに出す（値そのものは絶対に出さない）。
val signingParts = mapOf(
    "storeFile" to signStoreFile,
    "storePassword" to signStorePassword,
    "keyAlias" to signKeyAlias,
    "keyPassword" to signKeyPassword,
)
val missingSigningParts = signingParts.filterValues { it == null }.keys
val signStoreExists = signStoreFile != null && rootProject.file(signStoreFile).exists()
val hasReleaseKey = missingSigningParts.isEmpty() && signStoreExists

if (!hasReleaseKey && missingSigningParts.size < signingParts.size) {
    logger.warn(
        "署名設定が不完全なため release は未署名になります。未設定: " +
            missingSigningParts.joinToString(", ").ifEmpty { "(鍵ファイルが見つからない)" },
    )
}

/**
 * Google Play へ上げる App Bundle の署名（アップロード鍵）。
 *
 * 配布用の署名（アプリ署名鍵）は Play が持ち、こちらが持つのは
 * 「この AAB は開発者本人が上げた」と示すアップロード鍵だけ。
 * GitHub 版の署名鍵（上の release）とは別の鍵にしてある ――
 * 片方が漏れても、もう片方の配布経路に影響しない。
 *
 * 解決順は release と同じ（Gradle プロパティ → 環境変数 → keystore.properties）。
 * `source tools/play-env.sh` で macOS のキーチェーンから環境変数へ読み込める。
 * 揃っていなければ署名設定を作らず、bundlePlayRelease は未署名の AAB を出す。
 */
val uploadStoreFile = signingValue("unistrokeUploadStoreFile", "UNISTROKE_UPLOAD_STORE_FILE", "uploadStoreFile")
val uploadStorePassword = signingValue("unistrokeUploadStorePassword", "UNISTROKE_UPLOAD_STORE_PASSWORD", "uploadStorePassword")
val uploadKeyAlias = signingValue("unistrokeUploadKeyAlias", "UNISTROKE_UPLOAD_KEY_ALIAS", "uploadKeyAlias")
val uploadKeyPassword = signingValue("unistrokeUploadKeyPassword", "UNISTROKE_UPLOAD_KEY_PASSWORD", "uploadKeyPassword")
val hasUploadKey = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword).all { it != null } &&
    rootProject.file(uploadStoreFile!!).exists()

android {
    namespace = "com.unistroke.ime"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword

                // v3 は鍵のローテーション（署名証明書の系譜）を可能にするために要る。
                // v3 ブロックの無い APK を配ってしまうと、後から鍵を替えたときに
                // 「別のアプリ」扱いになり、上書き更新を配れなくなる。
                // 実際に鍵を替えるときは apksigner rotate で lineage を作る。
                enableV2Signing = true
                enableV3Signing = true
                // v1（JAR 署名）は Android 6 以前向け。minSdk 26 なので不要。
                enableV1Signing = false
            }
        }
        if (hasUploadKey) {
            create("playUpload") {
                storeFile = rootProject.file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.unistroke.ime"
        minSdk = 26
        // Google Play は新規アプリに最新の API レベルを求める（Android 16 = 36）。
        targetSdk = 36
        // 端末上での識別用。APK を差し替えたら versionCode を上げる。
        // versionName は「メジャー.マイナー.パッチ」の 3 段階で管理する。
        versionCode = 9
        versionName = "1.3.0"

        // 「バージョン: 1.0 (build 2026-08-11 10:43)」の build 部分。
        buildConfigField("String", "BUILD_TIME", "\"${sourceBuildStamp()}\"")
    }

    /**
     * 配布経路ごとのフレーバー。
     *
     *   play   … Google Play で配る本体。**アプリは 1 本**で、無料版・有料版には分けない。
     *            試用期間だけ全機能を開き、過ぎたらプレミアムの鍵（Play の購入）で解錠する
     *            「キーオープン型」。更新は Play が配るので自己更新は入れない
     *            （REQUEST_INSTALL_PACKAGES と APK 取得は Play のポリシーで不可）。
     *   github … 協力者向けに GitHub Releases へ置く APK。全機能・試用制限なし・自己更新あり。
     *            Play 版とは別物として扱う。
     *
     * フレーバー固有のコードは src/github・src/play に置き、main からは
     * 同名のオブジェクト（AppUpdateGate・PremiumGate）越しに呼ぶ。
     * 試用日数や購入の要否もそこにある（PremiumGate）。
     */
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            isDefault = true
            // 鍵が無い環境では null のまま = 未署名 APK。ビルド自体は通す。
            signingConfig = signingConfigs.findByName("release")
        }
        create("play") {
            dimension = "dist"
            // Play 上の ID は一度上げたら変えられない。github 版（com.unistroke.ime）とは
            // 別の ID にしてあるので、同じ端末に両方を入れておける。
            // クラスのパッケージ（namespace）は共通のまま。
            applicationId = "io.github.makiiii_git.unistroke"
            signingConfig = signingConfigs.findByName("playUpload")
        }
    }

    buildFeatures {
        // BuildConfig.BUILD_TIME / VERSION_NAME を Kotlin から読むために必要
        buildConfig = true
    }

    androidResources {
        // オンデバイス辞書は APK 内で無圧縮にする。
        // そうしないと AssetManager.openFd() が使えず、メモリマップできない
        // （＝起動時に 7 MB を展開してヒープに載せることになる）。
        noCompress += "dic"
    }

    buildTypes {
        release {
            // 署名はフレーバーごとに決める（github = release 鍵、play = アップロード鍵）。
            // ここで指定するとフレーバー側の指定より優先されてしまうので、書かない。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Google Play の課金。play フレーバーにだけ入れる（github 版には課金のコードが入らない）。
    "playImplementation"("com.android.billingclient:billing:8.0.0")
}
