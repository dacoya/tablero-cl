import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from android/keystore.properties, which is gitignored
// along with the keystore. Without it a release build is left unsigned.
val signing: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "cl.tablero.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cl.tablero.app"
        // 30 for the framework SQLite: 3.28 there, and the median and
        // price-observation queries use window functions, which need 3.25.
        minSdk = 30
        targetSdk = 37
        versionCode = 14
        versionName = "1.9.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The Python checkout's parity corpus, readable by the on-device test.
    sourceSets["androidTest"].assets.directories.add(
        rootDir.parentFile.resolve("tests/fixtures").path
    )

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
