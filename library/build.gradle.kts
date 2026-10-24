plugins {
    alias(libs.plugins.android.library)
    // Static analysis only: a build plugin.
    alias(libs.plugins.detekt)
    // No Kotlin plugin: AGP 9 built-in Kotlin compiles the sources.
}

// detekt's default rules, adjusted (with reasons) in config/detekt/detekt.yml. No baseline:
// every finding is fixed or suppressed with a reason.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/java", "src/androidTest/java")
}

android {
    namespace = "com.github.reygnn.guru"
    compileSdk = 37

    defaultConfig {
        minSdk = 36                 // Android 16 only — no compat shims
        // The on-device tests (connectedAndroidTest): real MediaStore, see GuruDownloadsInstrumentedTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Hosts compile these sources with -Werror (Verso does): a warning here is a
            // broken host build after the next subtree pull. Main and test sources alike.
            allWarningsAsErrors.set(true)
        }
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources.
            isIncludeAndroidResources = true
            // A plain JVM test that grazes an android.jar getter degrades to a default
            // instead of failing on the stub.
            isReturnDefaultValues = true
            all {
                // Robolectric self-attaches a ByteBuddy agent; JDK 21 warns unless dynamic
                // agent loading is opted into explicitly.
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                // Conscrypt lowercases os.name with the default locale: on a Turkish host its
                // native library is not found and every Robolectric test fails. No test
                // needs TLS.
                it.systemProperty("robolectric.conscryptMode", "OFF")
            }
        }
    }

    // Lint findings fail the build like compiler warnings. No baseline.
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Not findings: the version checks quote the newest upstream release, so a release
        // would fail the build without a change here; bumps are deliberate.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// check compiles the on-device tests too: an update that breaks them fails here and in CI, not
// at the device test before a tag. Running them needs a device (connectedAndroidTest).
tasks.named("check") { dependsOn("compileDebugAndroidTestKotlin") }

// No implementation dependency — hard rule 1. Test classpath only.
dependencies {
    testImplementation(libs.junit)
    // Real org.json for reading the ACRA JSON back (the library Acrarium parses it with).
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    // On-device tests — test APK only, never in a host.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
