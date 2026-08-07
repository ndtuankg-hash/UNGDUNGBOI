import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val accountApiUrl = localProperties.getProperty(
    "ACCOUNT_API_URL",
    "https://slmyzhdnkwjkdwgwjhuh.supabase.co/functions/v1/b-dich-api"
)
val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY", "")
val updateManifestUrl = localProperties.getProperty(
    "UPDATE_MANIFEST_URL",
    "https://boi-ungdung1.pages.dev/version.json"
)
val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.dangtuan.btranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dangtuan.btranslate"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        buildConfigField("String", "ACCOUNT_API_URL", "\"${accountApiUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${updateManifestUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}
