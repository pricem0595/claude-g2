plugins {
    id("com.android.application")
}

android {
    namespace = "com.mattprice.claudeg2.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mattprice.claudeg2.bridge"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        // Shown as "v0.1" on the bridge screen. Bump both with each release.
        versionName = "0.3"
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
