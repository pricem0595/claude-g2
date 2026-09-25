import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing. The key stays off the repo: ~/.gradle/gradle.properties names a properties
// file (storeFile, storePassword, keyAlias, keyPassword) with
//   claudeG2.signing=C:/path/to/claude-g2-release.properties
// Without it, release builds come out unsigned; debug builds are unaffected.
val releaseKey: Properties? = (findProperty("claudeG2.signing") as String?)?.let { path ->
    val props = Properties()
    val stream = FileInputStream(file(path))
    try {
        props.load(stream)
    } finally {
        stream.close()
    }
    props
}

android {
    namespace = "com.mattprice.claudeg2.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mattprice.claudeg2.bridge"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        // Shown as "v0.1" on the bridge screen. Bump both with each release.
        versionName = "0.5.2"
    }

    if (releaseKey != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.util.Log calls in the bridge become no-ops in JVM tests instead of throwing.
    testOptions.unitTests.isReturnDefaultValues = true
}

// `gradlew testDebugUnitTest --tests '*DesktopBridge*' --rerun -Dbridge.serve=true` runs the real
// bridge on this PC, replaying the screen fixtures, for the glasses simulator to talk to.
tasks.withType<Test>().configureEach {
    System.getProperty("bridge.serve")?.let { systemProperty("bridge.serve", it) }
    System.getProperty("bridge.screen")?.let { systemProperty("bridge.screen", it) }
    System.getProperty("bridge.locked")?.let { systemProperty("bridge.locked", it) }
    System.getProperty("bridge.scenario")?.let { systemProperty("bridge.scenario", it) }
    System.getProperty("bridge.page")?.let { systemProperty("bridge.page", it) }
    System.getProperty("bridge.steps")?.let { systemProperty("bridge.steps", it) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Android's org.json is a stub off-device; the real one lets JVM tests exercise the HTTP layer.
    testImplementation("org.json:json:20260814")
}
