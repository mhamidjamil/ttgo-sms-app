import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

fun localProp(key: String, default: String) =
    localProps.getProperty(key, default).also { require(it.isNotBlank()) { "local.properties missing: $key" } }

android {
    namespace = "com.textgate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.textgate.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "SMS_JOBS_PATH",
            "\"${localProps.getProperty("SMS_JOBS_PATH", "sim_module/sms/sms_jobs")}\"")
        buildConfigField("String", "USERS_PATH",
            "\"${localProps.getProperty("USERS_PATH", "ttgo_users")}\"")
        buildConfigField("String", "DEVICE_DOC_PATH",
            "\"${localProps.getProperty("DEVICE_DOC_PATH", "sim_module/device")}\"")
        buildConfigField("int", "UNVERIFIED_QUOTA",
            localProps.getProperty("UNVERIFIED_QUOTA", "2"))
        buildConfigField("int", "PARTIAL_VERIFIED_QUOTA",
            localProps.getProperty("PARTIAL_VERIFIED_QUOTA", "4"))
        buildConfigField("int", "HISTORY_POLL_INTERVAL_SECONDS",
            localProps.getProperty("HISTORY_POLL_INTERVAL_SECONDS", "10"))
        buildConfigField("int", "WIFI_STABILITY_MINUTES",
            localProps.getProperty("WIFI_STABILITY_MINUTES", "10"))
        buildConfigField("int", "MIN_WIFI_STABILITY_MINUTES",
            localProps.getProperty("MIN_WIFI_STABILITY_MINUTES", "5"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val firebaseBom = platform(libs.firebase.bom)
    implementation(firebaseBom)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
