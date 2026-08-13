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

android {
    namespace = "com.unistroke.ime"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.unistroke.ime"
        minSdk = 26
        targetSdk = 35
        // 端末上での識別用。APK を差し替えたら versionCode を上げる。
        versionCode = 2
        versionName = "1.0"

        // 「バージョン: 1.0 (build 2026-08-11 10:43)」の build 部分。
        buildConfigField("String", "BUILD_TIME", "\"${sourceBuildStamp()}\"")
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
            // 鍵が無い環境では null のまま = 未署名 APK。ビルド自体は通す。
            signingConfig = signingConfigs.findByName("release")
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
}
