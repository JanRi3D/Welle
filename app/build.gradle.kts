import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Optional release key: keystore.properties in the project root (not committed).
// Without it, release builds are signed with the debug key so they stay installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "me.ri3d.welle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    // r23 is the last NDK that can target API 16; newer NDKs start at API 21.
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "me.ri3d.welle"
        minSdk = 16
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            // armeabi-v7a is the head unit; the others cover emulators and modern phones.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++14"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            // v1 (JAR) signing is what Android 4.x verifies; v2 is for Android 7+.
            enableV1Signing = true
            enableV2Signing = true
        }
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // Routes libirtdab's std::cout diagnostics to logcat (tag "std").
                    cppFlags += "-DDEBUGOUTPUT"
                }
            }
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Conscrypt is only loaded on Android 4.x, which is 32-bit; its 64-bit libraries
            // would add 4.5 MB that no device ever uses.
            excludes += listOf("lib/arm64-v8a/libconscrypt_jni.so", "lib/x86_64/libconscrypt_jni.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Checks that do not apply to this project, each for a stated reason:
        disable += setOf(
            // toolchain and dependency versions are pinned on purpose (see README)
            "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi",
            // @RequiresApi lives in AndroidX, which needs API 21+; the platform's @TargetApi is used
            "UseRequiresApi",
            // views are only ever created in code, never inflated from XML
            "ViewConstructor",
            // the APK is installed directly, there is no app bundle with language splits
            "AppBundleLocaleChanges",
            // "%d of %d stations · %d logos" style status lines
            "PluralsCandidate",
            // landscape is the layout this head-unit app is designed for
            "DiscouragedApi",
            // the build rasterises vector icons to PNG for API 16-20 (res/drawable-*dpi-v4)
            "NotificationIconCompatibility"
        )
    }
}

dependencies {
    implementation(libs.conscrypt)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
