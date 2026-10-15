plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cl.tablero.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cl.tablero.app"
        // 30 for the framework SQLite: 3.28 there, and the median and
        // price-observation queries use window functions, which need 3.25.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The Python checkout's parity corpus, readable by the on-device test.
    sourceSets["androidTest"].assets.directories.add(
        rootDir.parentFile.resolve("tests/fixtures").path
    )

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sqlite.framework)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
