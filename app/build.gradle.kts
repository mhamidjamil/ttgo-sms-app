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
        versionCode = 4
        versionName = "1.2.0"

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

        // SMTP mailer (same variable names as the baileys-service .env) — used
        // for quota-increase requests to the admin. All optional: when host/user
        // are blank the mailer no-ops with a clear error instead of crashing.
        // SECURITY NOTE: these values are compiled into the APK — use a
        // low-privilege mail account, never your personal password.
        buildConfigField("String", "SMTP_HOST",
            "\"${localProps.getProperty("SMTP_HOST", "").removeSurrounding("\"")}\"")
        buildConfigField("int", "SMTP_PORT",
            localProps.getProperty("SMTP_PORT", "587"))
        buildConfigField("boolean", "SMTP_SECURE",
            localProps.getProperty("SMTP_SECURE", "false"))
        buildConfigField("String", "SMTP_USER",
            "\"${localProps.getProperty("SMTP_USER", "").removeSurrounding("\"")}\"")
        buildConfigField("String", "SMTP_PASS",
            "\"${localProps.getProperty("SMTP_PASS", "").removeSurrounding("\"")}\"")
        buildConfigField("String", "SMTP_FROM_EMAIL",
            "\"${localProps.getProperty("SMTP_FROM_EMAIL", "").removeSurrounding("\"")}\"")
        buildConfigField("String", "SMTP_SENDER_NAME",
            "\"${localProps.getProperty("SMTP_SENDER_NAME", "TextGate").removeSurrounding("\"")}\"")
        buildConfigField("String", "ADMIN_EMAIL",
            "\"${localProps.getProperty("ADMIN_EMAIL", "").removeSurrounding("\"")}\"")

        // WhatsApp gateway (baileys service). Both values are REMOTE-FIRST at
        // runtime: fields wa_service_url / wa_sso_secret on the Firestore device
        // doc override these compile-time defaults, so rotating the URL or the
        // SSO secret is a config edit (Firestore + service .env) — no rebuild.
        buildConfigField("String", "WHATSAPP_SERVICE_URL",
            "\"${localProps.getProperty("WHATSAPP_SERVICE_URL", "https://ww.innovorix.com").removeSurrounding("\"")}\"")
        buildConfigField("String", "WHATSAPP_SSO_SECRET",
            "\"${localProps.getProperty("WHATSAPP_SSO_SECRET", "").removeSurrounding("\"")}\"")
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

    // SMTP mailer (JavaMail for Android) — quota-increase requests to the admin
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
