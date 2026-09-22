plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.tvnas.filebrowser"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/tvmanage.jks")
            storePassword = "yxjie5506"
            keyAlias = "tvmanage"
            keyPassword = "yxjie5506"
        }
    }

    defaultConfig {
        applicationId = "com.tvnas.filebrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            // bcprov-jdk18on 与 bcutil-jdk18on 都自带 META-INF/versions/9/OSGI-INF/MANIFEST.MF，
            // 合并时冲突；OSGI manifest 对 Android 运行无用，排除即可
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // SMB client - smbj
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("net.engio:mbassador:1.3.2")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Glide for icons
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // EXIF 解析（含 HEIC/HEIF 内嵌缩略图，纯 Java 解析 ISO-BMFF，不依赖系统 API 版本）
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Media3 ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Bugly crash reporting
    implementation("com.tencent.bugly:crashreport:4.1.9.3")
}
