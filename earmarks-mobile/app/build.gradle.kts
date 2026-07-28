plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.derpy.earmarks"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.derpy.earmarks"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugShared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "earmarksdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugShared")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debugShared")
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Bouncy Castle includes duplicate signature files
            excludes += "META-INF/BCKEY.SF"
            excludes += "META-INF/BCKEY.DSA"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Crypto: secp256k1 ECDH, HKDF-SHA256, ChaCha20 for NIP-44
    implementation(libs.bouncycastle)

    // JVM unit tests. The NIP-44 and gift wrap code deliberately avoids
    // android.* APIs so its cross-client conformance vectors run here rather
    // than needing a device.
    testImplementation(libs.junit4)
    // Android stubs out org.json in JVM unit tests; the real implementation lets
    // the gift wrap tests run without a device.
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
