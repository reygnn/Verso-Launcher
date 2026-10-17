plugins {
    alias(libs.plugins.android.application)
    // No Kotlin plugin: AGP 9 built-in Kotlin compiles the sources.
    // No Compose, no serialization, no KSP, no Hilt — by design.
}

android {
    namespace = "com.github.reygnn.verso_launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.reygnn.verso_launcher"
        minSdk = 36                 // Android 16 only — no compat shims
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"

        // On-device tests only for what a JVM can't reproduce: the real MediaStore behind the
        // Downloads backup (its renaming of taken names, pending rows, ownership) and Android's
        // own org.json. Everything else stays a JVM test.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // The release build on the device is signed with the family key, a debug build with
            // the debug key — same applicationId and the install (e.g. for connectedAndroidTest)
            // fails on the signature. A separate id lets both coexist; release is unaffected.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true      // R8 (full mode is the AGP 9 default)
            isShrinkResources = true    // strip unreferenced resources too
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Drop Kotlin's reflection metadata from the APK. These kotlin/**.kotlin_builtins
    // files are packaged as RESOURCES, not code, so R8 never touches them however
    // aggressively it shrinks — and at ~12 KB compressed they were the second-largest
    // item in a 49 KB APK, behind classes.dex itself.
    //
    // Safe only because nothing here uses Kotlin reflection: no DI framework, no
    // kotlinx.serialization, no ::class.members. The failure mode if that ever changes
    // is a RUNTIME KotlinReflectionNotSupportedError, not a build error — so a library
    // added later that reflects over Kotlin types needs this narrowed or removed.
    packaging {
        resources {
            excludes += "kotlin/**"
        }
    }

    // Java 21 end to end.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources — a test that builds a
            // real widget resolves R.string/@style through them.
            isIncludeAndroidResources = true
            // The plain (non-Robolectric) JVM tests never call android.jar, but keep the
            // stub returning defaults rather than throwing, so a future one that grazes a
            // framework getter degrades instead of failing on the android.jar stub.
            isReturnDefaultValues = true
            all {
                // Robolectric self-attaches a ByteBuddy agent; on JDK 21 that prints a
                // warning (and is an error on later JDKs) unless dynamic agent loading is
                // opted into explicitly.
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                // Robolectric loads Conscrypt, which lowercases os.name with the default locale:
                // on a Turkish host "Linux" becomes "lınux", its native library is not found and
                // every Robolectric test fails before it runs. Verso uses no TLS — leave it out.
                it.systemProperty("robolectric.conscryptMode", "OFF")
            }
        }
    }

    // Every buildFeature stays OFF (BuildConfig, Compose, viewBinding, …).
    // Verso Launcher has no generated code and no resource-backed binding.
}

// No PRODUCTION dependencies — that is the feature; the release APK is Kotlin
// stdlib + platform APIs only. Every entry below is testImplementation or
// androidTestImplementation, so none of them ever enters the shipped APK.
dependencies {
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath for the ConfigJson round-trip
    // (the android.jar org.json is a throwing stub). Test-only — not in the APK.
    testImplementation(libs.json)
    // Android runtime on the JVM for the few tests that need real widgets (RenameDialog's
    // tag InputFilter). Test-only — never in the shipped APK. No androidx.test/ActivityScenario
    // here on purpose: a plain Activity with no Hilt/AppCompat is driven with Robolectric's
    // own APIs, which keeps the whole androidx.test consistent-resolution force-pin out.
    testImplementation(libs.robolectric)
    // On-device tests (connectedAndroidTest) — test APK only, never in the shipped one.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
