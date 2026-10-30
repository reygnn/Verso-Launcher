plugins {
    alias(libs.plugins.android.application)
    // Static analysis only: a build plugin, nothing of it reaches the APK.
    alias(libs.plugins.detekt)
    // No Kotlin plugin: AGP 9 built-in Kotlin compiles the sources.
    // No Compose, no serialization, no KSP, no Hilt — by design.
}

// detekt's default rules, adjusted (with reasons) in config/detekt/detekt.yml. A finding fails
// `check` like a compiler warning; what was there at switch-on is recorded in
// detekt-baseline.xml (create it once: ./gradlew detektBaseline), which only ever shrinks.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/dailyDriver/kotlin", "src/public/kotlin", "src/test/java")
    baseline = file("detekt-baseline.xml")
}

// -PdailyDriver (bare or =true): the personal release build (see sourceSets below).
val dailyDriver = providers.gradleProperty("dailyDriver").map { it.isBlank() || it.toBoolean() }.getOrElse(false)

// The tests that read files instead of code. When they run is described at sourceScanTest, below.
val sourceScans = listOf("*.SourceGuardTest", "*.CommentLinkTest")

android {
    namespace = "com.github.reygnn.verso_launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.reygnn.verso_launcher"
        minSdk = 36                 // Android 16 only — no compat shims
        targetSdk = 36
        // A GitHub release has an even versionCode; the daily-driver build of the same release the
        // odd one above it and "-daily" in its name, so each build's mapping has a versionCode of its
        // own. A release bump is +2 (CLAUDE.md, Stack particulars).
        versionCode = 70 + if (dailyDriver) 1 else 0
        versionName = "0.6.11" + if (dailyDriver) "-daily" else ""
    }

    // The daily-driver build: debug always, a release only with -PdailyDriver. DailyDriver.ENABLED
    // comes from one of two twin files, so a plain assembleRelease/bundleRelease can only produce
    // the public build, with the personal-only surfaces (~crash) compiled out.
    sourceSets {
        named("debug") { kotlin.directories += "src/dailyDriver/kotlin" }
        named("release") { kotlin.directories += if (dailyDriver) "src/dailyDriver/kotlin" else "src/public/kotlin" }
    }

    buildTypes {
        debug {
            // The release build on the device is signed with the family key, a debug build with
            // the debug key — same applicationId and the install fails on the signature. A separate
            // id lets both coexist; release is unaffected.
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
    // aggressively it shrinks — and at ~12 KB compressed they are among the largest items
    // in the APK.
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
            // A warning is a finding, not noise: main and test sources alike. A deliberate one
            // gets a @Suppress at the narrowest scope, with the reason on the line above.
            allWarningsAsErrors.set(true)
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

    // Lint findings fail the build like compiler warnings. What was there when this was switched
    // on is recorded in lint-baseline.xml (create it once: ./gradlew updateLintBaseline); that
    // file only ever shrinks — a new finding is fixed, not added to it.
    lint {
        warningsAsErrors = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
        // Not findings: the version checks quote the newest upstream release (of a dependency,
        // of Gradle), so a release would fail the build without a change here (and offline they
        // report nothing); bumps are deliberate. targetSdk stays 36 until lifted and tested on a
        // device.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    // Every buildFeature stays OFF (BuildConfig, Compose, viewBinding, …).
    // Verso Launcher has no generated code and no resource-backed binding.
}

// The source scans, SourceGuardTest and CommentLinkTest, read files that do not compile: the
// sources, the manifest, the R8 rules and the Markdown files. They run with the unit tests, and
// again in this task, part of check, with those files as inputs: a comment or a CLAUDE.md changed
// alone leaves the unit test tasks up to date, and then this task reruns the scans alone. It
// reuses the debug unit tests' classes and classpath, so the scans compile once with the other
// tests. One scan alone: ./gradlew sourceScanTest --tests '*CommentLinkTest'
// Why: the unit test tasks keep the scans, so --tests finds one in either task; both together
//   take under a second.
val sourceScanTest = tasks.register<Test>("sourceScanTest") {
    group = "verification"
    description = "Runs the source scans whenever a file they read changes."
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    useJUnit()
    filter { sourceScans.forEach { scan -> includeTestsMatching(scan) } }
    inputs.files(
        fileTree("src"),
        file("proguard-rules.pro"),
        rootProject.files("CLAUDE.md", "CLAUDE-CLOUD.md", "CAVEATS.md", "README.md"),
    ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.named("check") { dependsOn(sourceScanTest) }

// No PRODUCTION dependencies — that is the feature; the release APK is Kotlin
// stdlib + platform APIs only. Every entry below is testImplementation, so none of them ever
// enters the shipped APK.
dependencies {
    testImplementation(libs.junit)
    // Android runtime on the JVM for the tests that need it (the real Activity, its widgets,
    // MotionEvents, TalkBack nodes). Test-only — never in the shipped APK. No
    // androidx.test/ActivityScenario here on purpose: a plain Activity with no Hilt/AppCompat is
    // driven with Robolectric's own APIs, which keeps the whole androidx.test consistent-resolution
    // force-pin out.
    testImplementation(libs.robolectric)
}

// The release mapping, after every assembleRelease/bundleRelease: kept outside the repo, where
// retrace (~/AndroidStudio_Projects/retrace) finds it by the map id R8 writes into every frame. A
// crash trace of that release can only be read with it.
// Values are read here, at configuration: the task touches no `project` or `android` when it runs.
val releaseMapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
val releaseId = android.defaultConfig.run { "$versionName-$versionCode" }
val mappingArchive = providers.gradleProperty("verso.mappingArchive")
    .orElse(providers.systemProperty("user.home").map { "$it/apk/mappings" })
    .map { File(it, "verso/$releaseId") }

val archiveProguardMapping = tasks.register<Copy>("archiveProguardMapping") {
    group = "release"
    description = "Keeps the release mapping in <verso.mappingArchive>/verso/<versionName>-<versionCode>/."
    from(releaseMapping)
    into(mappingArchive)
    mustRunAfter(tasks.matching { it.name == "minifyReleaseWithR8" })
    val mapping = releaseMapping
    onlyIf("a release mapping exists") { task ->
        mapping.get().asFile.exists().also { if (!it) task.logger.warn("No release mapping at ${mapping.get().asFile}") }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    finalizedBy(archiveProguardMapping)
}
