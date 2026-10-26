plugins {
    id("com.android.application")
}

// The release workflow passes these; a local build gets placeholders.
val craftVersionName = (findProperty("craftVersionName") as String?) ?: "0.0.0-dev"
val craftVersionCode = ((findProperty("craftVersionCode") as String?) ?: "1").toInt()
val keystore: String? = System.getenv("CRAFT_KEYSTORE")

android {
    namespace = "io.github.patrickdalton.craft.pdfcraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.patrickdalton.craft.pdfcraft"
        minSdk = 26
        targetSdk = 35
        versionCode = craftVersionCode
        versionName = craftVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
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
            // Without the shared key (a repository secret), the runner's debug key signs it: it
            // installs, but each build then needs the previous one uninstalled first.
            signingConfig = signingConfigs.findByName("craft") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../../../../shared/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // The app is the Rust library; lint has little to check and must not block a release.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // android-activity 0.6 (in winit) supports GameActivity 4.4.0. Its own native glue replaces
    // the library's prefab C++ layer, so prefab stays off.
    implementation("androidx.games:games-activity:4.4.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // A Java-only app has no Kotlin plugin to align the stdlib: without the BOM, the old
    // kotlin-stdlib-jdk7/jdk8 (1.6) duplicate classes of kotlin-stdlib 1.8.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
}
