import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

android {
    namespace = "com.unistroke.ime"
    compileSdk = 35

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
