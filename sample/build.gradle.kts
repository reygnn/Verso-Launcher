plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    // No Kotlin plugin: AGP 9 built-in Kotlin compiles the sources.
}

// The sample is Guru's first host: it takes Guru exactly as Verso does, as source compiled into
// its own module — not as a project dependency on :library.
val guruSources = "../library/src/main/kotlin"

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // Guru's own sources are checked in :library.
    source.setFrom("src/main/kotlin", "src/test/java")
}

android {
    namespace = "com.github.reygnn.guru.sample"
    compileSdk = 37

    defaultConfig {
        // No ".debug" suffix: the device test runs `am crash com.github.reygnn.guru.sample` against
        // the debug build, the only one with the ANR button.
        applicationId = "com.github.reygnn.guru.sample"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets {
        named("main") {
            kotlin.directories += guruSources
        }
    }

    buildTypes {
        release {
            // R8 as in a host's release, with the README's rules: readable traces, stable numbers.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                it.systemProperty("robolectric.conscryptMode", "OFF")
            }
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // As in :library; OldTargetApi as in Verso: targetSdk stays 36 until lifted and tested
        // on a device.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
