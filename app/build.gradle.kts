import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing, from keystore.properties in the project root (storeFile, storePassword,
 * keyAlias, keyPassword) or the same values in EXPLORER_* environment variables (for CI).
 * Neither is in git; without them, release builds are left unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val values = if (file.exists()) {
        Properties().apply { file.inputStream().use(::load) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
    } else {
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").mapNotNull { key ->
            System.getenv("EXPLORER_" + key.replace(Regex("([A-Z])"), "_$1").uppercase())?.let { key to it }
        }.toMap()
    }
    values.takeIf { it.keys.containsAll(listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) }
}

android {
    namespace = "com.singularity_universe.explorer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.singularity_universe.explorer"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { values ->
            create("release") {
                storeFile = rootProject.file(values.getValue("storeFile"))
                storePassword = values.getValue("storePassword")
                keyAlias = values.getValue("keyAlias")
                keyPassword = values.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.jsch)
    // Archives: tar/gz/bz2/xz/7z, and password-protected zips.
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    testImplementation(libs.junit)
    // The XML parser Android has built in, for the document reader tests.
    testImplementation(libs.kxml2)
    // A real SSH server for the key sign-in tests.
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.eddsa)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}