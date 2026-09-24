package com.mattprice.claudeg2.bridge

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a test: runs the real bridge on this PC at 127.0.0.1:8421, backed by the pretend Claude
 * app replaying the fixture screens, until the process is stopped. The glasses app in the Even
 * Hub simulator can then run against the actual Kotlin bridge.
 *
 *   gradlew testDebugUnitTest --tests "*DesktopBridge*" --rerun -Dbridge.serve=true
 *
 * Token: ABCDE. Skipped in normal test runs.
 */
class DesktopBridge {
    @Test
    fun serve() {
        assumeTrue("Set -Dbridge.serve=true to run the desktop bridge", System.getProperty("bridge.serve") == "true")
        // -Dbridge.scenario=long: one long session, scrolled up, for scroll triage.
        if (System.getProperty("bridge.scenario") == "long") {
            val fake = FakeLongTranscript()
            val controller = MirrorController(fake)
            fake.controller = controller
            fake.start()
            BridgeHttpServer(controller, { TEST_TOKEN }, port = BRIDGE_PORT).start()
            println("Desktop bridge (long transcript) on http://127.0.0.1:$BRIDGE_PORT, token $TEST_TOKEN.")
            Thread.sleep(Long.MAX_VALUE)
        }
        val (_, claude) = startBridge(BRIDGE_PORT)
        // -Dbridge.screen=real-question starts on that fixture instead of the session list.
        System.getProperty("bridge.screen")?.let { claude.show(it) }
        // -Dbridge.locked=true locks the phone now and posts a question notification 10 s later.
        if (System.getProperty("bridge.locked") == "true") {
            claude.controller.onLockState(true)
            Thread {
                Thread.sleep(10_000)
                claude.controller.onNotification("Claude has a question: Claude", "Colour")
            }.start()
        }
        println("Desktop bridge on http://127.0.0.1:$BRIDGE_PORT, token $TEST_TOKEN. Stop the process to exit.")
        Thread.sleep(Long.MAX_VALUE)
    }
}
