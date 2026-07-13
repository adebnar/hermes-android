import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release signing is driven by a gitignored keystore.properties at the repo root.
// When absent (e.g. a fresh clone or CI without secrets), release builds stay unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.hermes.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hermes.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 52
        versionName = "0.1.50"
        testInstrumentationRunner = "com.hermes.client.HiltTestRunner"
        // App name; the beta build type overrides this so both can be installed at once.
        manifestPlaceholders["appLabel"] = "Hermes"
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the release config only when the keystore is present.
            signingConfig = signingConfigs.findByName("release")
        }
        // Beta channel: a separate applicationId + "Hermes Beta" label so testers can run
        // the beta alongside the production app. Cut from the `dev` branch as a GitHub
        // pre-release. Inherits the release signing config.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "Hermes Beta"
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true }

    // Let stubbed android.* calls (e.g. android.util.Log) return defaults instead of throwing
    // in local JVM unit tests, so pure logic that mirrors to logcat stays unit-testable.
    testOptions { unitTests.isReturnDefaultValues = true }

    // Build daemon runs on JBR (JDK 21); emit JVM 17 bytecode for Android.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.material)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt 2.59.2 bundles kotlin-metadata-jvm capped at metadata 2.3.0, but Kotlin 2.3.10 emits
    // 2.4.0. Dagger 2.57+ unshades kotlin-metadata-jvm, so pin a matching version on the Hilt
    // processing classpaths (KSP + the plugin's javac aggregation) to read the newer metadata.
    val kotlinMetadataJvm = "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}"
    ksp(kotlinMetadataJvm)
    annotationProcessor(kotlinMetadataJvm)
    kspAndroidTest(kotlinMetadataJvm)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.markdown.m3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.hilt.testing)
    androidTestImplementation(libs.androidx.test.runner)
    kspAndroidTest(libs.hilt.compiler)
}
