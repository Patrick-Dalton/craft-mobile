plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release workflow passes these; a local build gets placeholders.
val craftVersionName = (findProperty("craftVersionName") as String?) ?: "0.0.0-dev"
val craftVersionCode = ((findProperty("craftVersionCode") as String?) ?: "1").toInt()
val keystore: String? = System.getenv("CRAFT_KEYSTORE")

android {
    namespace = "io.github.patrickdalton.craft.hub"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.patrickdalton.craft.hub"
        minSdk = 26
        targetSdk = 35
        versionCode = craftVersionCode
        versionName = craftVersionName
    }

    signingConfigs {
        if (keystore != null) {
            create("craft") {
                storeFile = file(keystore)
                storePassword = System.getenv("CRAFT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CRAFT_KEY_ALIAS")
                keyPassword = System.getenv("CRAFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("craft") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
