package com.mattprice.claudeg2.bridge

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

const val TEST_TOKEN = "ABCDE"

/** What the pretend speech recognizer hears in any recording. */
const val FAKE_SPEECH = "List the files in src"

/** Pretend speech-to-text: a fixed sentence for any recording, nothing for silence (all zeros). */
val fakeTranscriber = object : Transcriber {
    override fun start() = object : SpeechSession {
        private val audio = java.io.ByteArrayOutputStream()
        override fun write(pcm: ByteArray) = audio.write(pcm)
        override suspend fun finish() = if (audio.toByteArray().all { it == 0.toByte() }) "" else FAKE_SPEECH
        override fun cancel() = Unit
    }
}

/** Starts a real bridge on this machine, backed by the pretend Claude app on its session list. */
fun startBridge(port: Int, longPollMs: Long = LONG_POLL_MS): Pair<BridgeHttpServer, FakeClaude> {
    val claude = FakeClaude()
    val controller = MirrorController(claude)
    claude.controller = controller
    claude.show("synthetic-sessions")
    val server = BridgeHttpServer(controller, { TEST_TOKEN }, port = port, longPollMs = longPollMs, transcriber = fakeTranscriber)
        .also { it.start() }
    return server to claude
}

class BridgeHttpServerTest {
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var server: BridgeHttpServer
    private lateinit var claude: FakeClaude

    private data class Reply(val status: Int, val json: JSONObject?, val cors: String?)

    @Before
    fun setUp() {
        startBridge(port, longPollMs = 300).let { (s, c) ->
            server = s
            claude = c
        }
    }

    @After
    fun tearDown() = server.stop()

    private fun call(method: String, path: String, body: String? = null, token: String? = " abcde "): Reply {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Reply(status, text.takeIf { it.startsWith("{") }?.let(::JSONObject), connection.getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test
    fun `requests without the token are refused, preflight is not`() {
        assertEquals(401, call("GET", "/health", token = null).status)
        assertEquals(401, call("GET", "/health", token = "WRONG").status)
        val preflight = call("OPTIONS", "/state", token = null)
        assertEquals(204, preflight.status)
        assertEquals("*", preflight.cors)
    }

    /** Sends raw HTTP like a browser that accepts gzip, returning everything the bridge sent back. */
    private fun raw(request: String): String {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 1500
            socket.getOutputStream().write(request.toByteArray())
            val out = java.io.ByteArrayOutputStream()
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = socket.getInputStream().read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Keep-alive: the connection stays open; everything sent has arrived.
            }
            return out.toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `an empty 204 sends no body, even to a client that accepts gzip`() {
        // A body after a 204 is read by the browser as the start of the next reply on the same
        // connection, which then fails: the "Bridge not reachable" bug.
        val reply = raw(
            "OPTIONS /state HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip\r\nOrigin: http://a\r\n" +
                "Access-Control-Request-Method: GET\r\n\r\n",
        )
        assertTrue(reply, reply.startsWith("HTTP/1.1 204"))
        assertEquals("", reply.substringAfter("\r\n\r\n"))
        assertTrue(reply, !reply.contains("Content-Encoding: gzip"))
        assertTrue(reply, reply.contains("Access-Control-Max-Age: 600"))
    }

    @Test
    fun `json replies are not gzipped`() {
        val reply = raw("GET /health HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip\r\nAuthorization: Bearer $TEST_TOKEN\r\n\r\n")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply, !reply.contains("Content-Encoding: gzip"))
        assertTrue(reply, reply.substringAfter("\r\n\r\n").startsWith("{"))
    }

    @Test
    fun `state describes the current screen`() {
        val reply = call("GET", "/state")
        assertEquals(200, reply.status)
        assertEquals("*", reply.cors)
        val json = reply.json!!
        assertEquals("sessions", json.getString("screen"))
        assertTrue(json.getBoolean("foreground"))
        assertTrue(json.getBoolean("atLatest"))
        assertEquals("Fix login redirect", json.getJSONArray("sessions").getJSONObject(0).getString("label"))
    }

    @Test
    fun `long poll returns 204 when nothing changes and the new state when something does`() {
        val version = call("GET", "/state").json!!.getLong("version")
        assertEquals(204, call("GET", "/state?since=$version").status)

        Thread { Thread.sleep(100); claude.show("synthetic-transcript") }.start()
        val changed = call("GET", "/state?since=$version").json!!
        assertEquals("transcript", changed.getString("screen"))
        assertTrue(changed.getLong("version") > version)
    }

    @Test
    fun `clicking a session opens it and back returns`() {
        val session = call("GET", "/state").json!!.getJSONArray("sessions").getJSONObject(0)
        val click = JSONObject().put("type", "click").put("id", session.getString("id")).put("label", session.getString("label"))
        assertEquals(200, call("POST", "/action", click.toString()).status)
        assertEquals("transcript", call("GET", "/state").json!!.getString("screen"))
        assertEquals(listOf("Fix login redirect"), claude.clicks)

        assertEquals(200, call("POST", "/action", """{"type":"back"}""").status)
        assertEquals("sessions", call("GET", "/state").json!!.getString("screen"))
    }

    @Test
    fun `a stale id falls back to the label, and an unknown option is gone`() {
        claude.show("synthetic-permission")
        val ok = call("POST", "/action", """{"type":"click","id":"stale","label":"Deny"}""")
        assertEquals(200, ok.status)
        assertEquals(listOf("Deny"), claude.clicks)

        val gone = call("POST", "/action", """{"type":"click","id":"stale","label":"Nope"}""")
        assertEquals(410, gone.status)
    }

    @Test
    fun `actions are refused while the Claude app is not in front`() {
        claude.controller.onSnapshot(null)
        val reply = call("POST", "/action", """{"type":"back"}""")
        assertEquals(409, reply.status)
        assertEquals(false, call("GET", "/state").json!!.getBoolean("foreground"))
    }

    @Test
    fun `locked phone reports notices, refuses taps, and clears the notice on unlock`() {
        val controller = claude.controller
        controller.onNotification("Claude has a question: Claude", "Colour")
        assertEquals(JSONObject.NULL, call("GET", "/state").json!!.get("notice"))

        controller.onLockState(true)
        controller.onNotification("Claude has a question: Claude", "Colour")
        val locked = call("GET", "/state").json!!
        assertEquals(true, locked.getBoolean("locked"))
        val notice = locked.getJSONObject("notice")
        assertEquals("Claude has a question: Claude", notice.getString("title"))
        assertEquals("Colour", notice.getString("text"))
        // The last screen is kept, so the glasses can still show it under the banner.
        assertEquals("sessions", locked.getString("screen"))

        val tap = call("POST", "/action", """{"type":"back"}""")
        assertEquals(409, tap.status)
        assertEquals("Unlock your phone to answer", tap.json!!.getString("error"))

        controller.onLockState(false)
        val unlocked = call("GET", "/state").json!!
        assertEquals(false, unlocked.getBoolean("locked"))
        assertEquals(JSONObject.NULL, unlocked.get("notice"))
    }

    @Test
    fun `scroll to latest pages forward until the list stops, and down at the end is a 409`() {
        claude.show("synthetic-transcript")
        claude.pagesBelow = 3
        val latest = call("POST", "/action", """{"type":"scroll","dir":"latest"}""")
        assertEquals(200, latest.status)
        assertEquals(3, latest.json!!.getInt("pages"))
        assertEquals(0, claude.pagesBelow)

        val again = call("POST", "/action", """{"type":"scroll","dir":"latest"}""")
        assertEquals(0, again.json!!.getInt("pages"))

        val down = call("POST", "/action", """{"type":"scroll","dir":"down"}""")
        assertEquals(409, down.status)
        assertEquals("Already at the end", down.json!!.getString("error"))
    }

    @Test
    fun `dump returns the raw tree as uiautomator xml`() {
        val connection = URI("http://127.0.0.1:$port/dump?token=ABCDE").toURL().openConnection() as HttpURLConnection
        assertEquals(200, connection.responseCode)
        val xml = connection.inputStream.bufferedReader().use { it.readText() }
        assertEquals(fixture("synthetic-sessions"), UiNode.fromXml(xml))
    }

    @Test
    fun `a recording streamed in pieces comes back as text when finished`() {
        val id = call("POST", "/voice/start").json!!.getString("id")
        repeat(3) { assertEquals(200, postPcm(ByteArray(PCM_BYTES_PER_SECOND / 4) { (it % 7).toByte() }, "/voice/audio?id=$id").status) }
        val done = call("POST", "/voice/finish?id=$id")
        assertEquals(200, done.status)
        assertEquals(FAKE_SPEECH, done.json!!.getString("text"))
        // Finished recordings take no more audio.
        assertEquals(410, postPcm(ByteArray(10), "/voice/audio?id=$id").status)
    }

    @Test
    fun `a new recording replaces the old one, and a cancelled one is gone`() {
        val first = call("POST", "/voice/start").json!!.getString("id")
        val second = call("POST", "/voice/start").json!!.getString("id")
        assertEquals(410, postPcm(ByteArray(10) { 1 }, "/voice/audio?id=$first").status)
        assertEquals(200, call("POST", "/voice/cancel?id=$second").status)
        assertEquals(410, call("POST", "/voice/finish?id=$second").status)
    }

    @Test
    fun `a streamed recording over a minute is refused`() {
        val id = call("POST", "/voice/start").json!!.getString("id")
        assertEquals(200, postPcm(ByteArray(MAX_PCM_BYTES), "/voice/audio?id=$id").status)
        assertEquals(413, postPcm(ByteArray(2), "/voice/audio?id=$id").status)
        assertEquals(410, call("POST", "/voice/finish?id=$id").status)
    }

    private fun postPcm(pcm: ByteArray, path: String = "/voice/transcribe"): Reply {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(pcm.size)
        connection.outputStream.use { it.write(pcm) }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Reply(status, text.takeIf { it.startsWith("{") }?.let(::JSONObject), connection.getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a recording comes back as text, silence as nothing, and one over a minute is refused`() {
        val speech = postPcm(ByteArray(PCM_BYTES_PER_SECOND) { (it % 7).toByte() })
        assertEquals(200, speech.status)
        assertEquals(FAKE_SPEECH, speech.json!!.getString("text"))

        assertEquals("", postPcm(ByteArray(PCM_BYTES_PER_SECOND)).json!!.getString("text"))

        val tooLong = postPcm(ByteArray(MAX_PCM_BYTES + 2))
        assertEquals(413, tooLong.status)
        // The whole body was read, so the connection still works for the next request.
        assertEquals(200, call("GET", "/health").status)
    }

    @Test
    fun `send types into the session's message box and taps Send`() {
        claude.show("synthetic-transcript")
        val reply = call("POST", "/action", JSONObject().put("type", "send").put("text", FAKE_SPEECH).toString())
        assertEquals(200, reply.status)
        assertEquals(listOf(FAKE_SPEECH), claude.sent)
        assertEquals(listOf("Send"), claude.clicks)
    }

    @Test
    fun `send is refused off a session, and with nothing to send`() {
        val onList = call("POST", "/action", """{"type":"send","text":"hello"}""")
        assertEquals(409, onList.status)
        assertEquals("Open a session to send a message", onList.json!!.getString("error"))

        claude.show("synthetic-transcript")
        assertEquals(400, call("POST", "/action", """{"type":"send","text":"  "}""").status)
        assertTrue(claude.sent.isEmpty())
    }

    @Test
    fun `unknown routes and bad actions are client errors`() {
        assertEquals(404, call("GET", "/nope").status)
        assertEquals(400, call("POST", "/action", """{"type":"dance"}""").status)
        assertEquals(400, call("POST", "/action", "not json").status)
    }
}
