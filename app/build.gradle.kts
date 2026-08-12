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

// Upload key for Google Play. The keystore itself lives outside the repository
// and this file is gitignored, so a clone carries no signing material. When it
// is absent the release variant simply builds unsigned, which keeps a plain
// `git clone && assembleDebug` working for anyone else.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.spotwire.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spotwire.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "SMS_JOBS_PATH",
            "\"${localProps.getProperty("SMS_JOBS_PATH", "sim_module/sms/sms_jobs").removeSurrounding("\"")}\"")
        buildConfigField("String", "USERS_PATH",
            "\"${localProps.getProperty("USERS_PATH", "ttgo_users").removeSurrounding("\"")}\"")
        buildConfigField("String", "DEVICE_DOC_PATH",
            "\"${localProps.getProperty("DEVICE_DOC_PATH", "sim_module/device").removeSurrounding("\"")}\"")
        buildConfigField("int", "HISTORY_POLL_INTERVAL_SECONDS",
            localProps.getProperty("HISTORY_POLL_INTERVAL_SECONDS", "10"))
        buildConfigField("int", "WIFI_STABILITY_MINUTES",
            localProps.getProperty("WIFI_STABILITY_MINUTES", "10"))
        buildConfigField("int", "MIN_WIFI_STABILITY_MINUTES",
            localProps.getProperty("MIN_WIFI_STABILITY_MINUTES", "5"))

        // NOTE: no credential of any kind belongs in a buildConfigField. Every
        // one of them is a public static final String, so the compiler inlines
        // it into the constant pool before R8 ever runs and `strings` on the
        // published APK reads it straight back. Verification email goes through
        // Firebase, which holds its own credentials server-side.

        // WhatsApp gateway (baileys service). Both are REMOTE-FIRST at runtime:
        // fields wa_service_url / wa_portal_url on the Firestore device doc
        // override these compile-time defaults, so moving the gateway or the
        // portal is a console edit — no rebuild, no release. These are public
        // addresses; no gateway credential is ever compiled in.
        buildConfigField("String", "WHATSAPP_SERVICE_URL",
            "\"${localProps.getProperty("WHATSAPP_SERVICE_URL", "https://w2.innovorix.com").removeSurrounding("\"")}\"")
        buildConfigField("String", "WHATSAPP_PORTAL_URL",
            "\"${localProps.getProperty("WHATSAPP_PORTAL_URL", "https://w2.innovorix.com").removeSurrounding("\"")}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        // NOTE: no applicationIdSuffix on debug. google-services.json is keyed by
        // package name, so suffixing it fails the build with "no matching client"
        // until a second app is registered in the Firebase project.
    }

    // Strips the encrypted dependency blob Play would otherwise get with the
    // bundle. It is metadata about our libraries, not something Play needs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
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
    implementation(libs.play.services.location)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
