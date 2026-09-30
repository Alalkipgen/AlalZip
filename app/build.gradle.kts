plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath = System.getenv("ALAL_KEYSTORE_FILE")
val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
val releaseStorePassword = System.getenv("ALAL_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALAL_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ALAL_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val requireReleaseSigning = System.getenv("REQUIRE_RELEASE_SIGNING") == "true"
if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException("Permanent release signing credentials are required")
}

android {
    namespace = "app.archivepocket"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.archivepocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Local builds may use the debug key; CI release builds require the permanent key.
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    implementation("org.apache.commons:commons-compress:1.27.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    testImplementation("junit:junit:4.13.2")
}