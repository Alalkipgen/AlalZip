plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.archivepocket"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.archivepocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Optional stable release key from CI secrets / local env; falls back to the debug key so the
        // optimized APK is always installable for sideloading.
        create("release") {
            val storePath = System.getenv("ALAL_KEYSTORE_FILE")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ALAL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALAL_KEY_ALIAS")
                keyPassword = System.getenv("ALAL_KEY_PASSWORD")
            } else {
                initWith(signingConfigs.getByName("debug"))
            }
        }
    }
    buildTypes {
        release {
            // R8 shrinking + optimization: Compose runs much smoother than in the debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { checkReleaseBuilds = false } // CI runs :app:lintDebug explicitly
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.junrar:junrar:8.1.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    testImplementation("junit:junit:4.13.2")
}