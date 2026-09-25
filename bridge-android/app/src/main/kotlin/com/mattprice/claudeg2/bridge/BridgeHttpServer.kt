package com.mattprice.claudeg2.bridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

const val BRIDGE_PORT = 8421

private const val TAG = "BridgeHttpServer"
private const val REQUEST_TIMEOUT_MS = 45_000L

/** How long GET /state holds a request open waiting for a change. */
const val LONG_POLL_MS = 25_000L

/**
 * How long an idle keep-alive connection stays open. NanoHTTPD's default of 5 s is shorter than
 * the WebView keeps idle connections, so it could reuse one the bridge had just closed.
 */
const val IDLE_CONNECTION_MS = 30_000

/** The longest recording POST /voice/transcribe takes: 60 s of 16 kHz 16-bit mono. */
const val MAX_PCM_BYTES = 60 * PCM_BYTES_PER_SECOND

private val REASONS = mapOf(
    200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
    409 to "Conflict", 410 to "Gone", 413 to "Payload Too Large", 500 to "Internal Server Error",
    502 to "Bad Gateway", 503 to "Service Unavailable",
)

/** NanoHTTPD's own status enum lacks some codes the API uses, so statuses are built here. */
private class HttpStatus(private val code: Int) : Response.IStatus {
    override fun getRequestStatus() = code
    override fun getDescription() = "$code ${REASONS[code] ?: "Status"}"
}

private class Raw(val code: Int, val mime: String, val body: String)

/**
 * The HTTP API from docs/API.md, bound to loopback so only this phone can reach it. Any app on
 * the phone can reach loopback, and the transcript is private and a tap can approve a command,
 * so every request except the CORS preflight needs the pairing token.
 */
class BridgeHttpServer(
    private val controller: MirrorController,
    private val token: () -> String,
    private val status: () -> JSONObject = { JSONObject() },
    port: Int = BRIDGE_PORT,
    private val longPollMs: Long = LONG_POLL_MS,
    private val transcriber: Transcriber? = null,
) : NanoHTTPD("127.0.0.1", port) {

    /**
     * When the glasses app (or anything with the token) last reached the bridge, in epoch ms;
     * 0 if never. It long-polls, so while it's open this is never more than ~25 s old.
     */
    @Volatile var lastRequestAt = 0L
        private set

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return respond(204, null)
        if (!authorized(session)) return respond(401, error("Wrong or missing token"))
        lastRequestAt = System.currentTimeMillis()
        return try {
            when (val result = runBlocking { withTimeout(REQUEST_TIMEOUT_MS) { route(session) } }) {
                null -> respond(204, null)
                is Raw -> cors(newFixedLengthResponse(HttpStatus(result.code), result.mime, result.body))
                else -> respond(200, (result as JSONObject).put("ok", true))
            }
        } catch (e: BridgeException) {
            respond(e.httpStatus, error(e.message))
        } catch (e: JSONException) {
            respond(400, error("Bad JSON"))
        } catch (e: Exception) {
            Log.e(TAG, "${session.method} ${session.uri} failed", e)
            respond(500, error(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Returns JSON, a [Raw] body, or null for 204. */
    private suspend fun route(session: IHTTPSession): Any? {
        val query = { name: String -> session.parameters[name]?.firstOrNull() }

        return when ("${session.method} ${session.uri}") {
            "GET /health" -> status().put("foreground", controller.state.value.foreground)

            "GET /state" -> {
                val since = query("since")?.toLongOrNull()
                val state = if (since == null) controller.state.value else controller.awaitChange(since, longPollMs)
                state?.let(::stateJson)
            }

            "POST /action" -> {
                val body = readBody(session)
                when (body.optString("type")) {
                    "click" -> controller.click(
                        body.optString("id").ifEmpty { throw BridgeException(400, "Missing id") },
                        body.optString("label").ifEmpty { null },
                    )
                    "scroll" -> when (body.optString("dir")) {
                        "latest" -> return JSONObject().put("pages", controller.scrollToLatest())
                        "down" -> controller.scroll(up = false)
                        else -> controller.scroll(up = true)
                    }
                    "back" -> controller.back()
                    "send" -> controller.send(body.optString("text"))
                    else -> throw BridgeException(400, "Unknown action")
                }
                JSONObject()
            }

            "POST /voice/transcribe" -> {
                val pcm = readPcm(session)
                val speech = transcriber ?: throw BridgeException(503, "No speech recognizer on this phone")
                JSONObject().put("text", speech.transcribe(pcm))
            }

            "GET /dump" -> Raw(200, "text/xml", controller.lastSnapshot?.toXml() ?: throw BridgeException(409, "Nothing captured yet"))

            else -> throw BridgeException(404, "Unknown endpoint")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val given = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: session.parameters["token"]?.firstOrNull()
            ?: return false
        // Constant-time, and case/spacing-insensitive since people type it from the bridge screen.
        return MessageDigest.isEqual(normalizeToken(given).toByteArray(), normalizeToken(token()).toByteArray())
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"]
        return if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    }

    /**
     * A raw PCM body. Read in full even when it's refused: whatever is left unread would be
     * taken as the start of the next request on the same connection.
     */
    private fun readPcm(session: IHTTPSession): ByteArray {
        val length = session.headers["content-length"]?.toLongOrNull() ?: throw BridgeException(400, "Missing Content-Length")
        val input = session.inputStream
        if (length > MAX_PCM_BYTES) {
            var left = length
            val skip = ByteArray(8192)
            while (left > 0) {
                val n = input.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
            throw BridgeException(413, "Recording too long")
        }
        val pcm = ByteArray(length.toInt())
        var read = 0
        while (read < pcm.size) {
            val n = input.read(pcm, read, pcm.size - read)
            if (n < 0) throw BridgeException(400, "Recording cut off")
            read += n
        }
        return pcm
    }

    private fun error(message: String?) = JSONObject().put("ok", false).put("error", message ?: "Error")

    private fun respond(code: Int, json: JSONObject?): Response = cors(
        if (json == null) {
            newFixedLengthResponse(HttpStatus(code), "text/plain", "")
        } else {
            newFixedLengthResponse(HttpStatus(code), "application/json", json.toString())
        },
    )

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        // The token header makes every request need a preflight; let the WebView reuse one.
        response.addHeader("Access-Control-Max-Age", "600")
        return response
    }

    /**
     * No gzip. NanoHTTPD 2.3.1 gzips any text reply when the client accepts it, including an
     * empty 204, which then goes out as a chunked 35-byte body that a 204 must not have. The
     * WebView reads those bytes as the start of the next reply on the same connection and fails
     * that request at once ("Bridge not reachable"). Over loopback gzip saves nothing anyway.
     */
    override fun useGzipWhenAccepted(r: Response) = false
}

fun normalizeToken(token: String) = token.uppercase().filter { it.isLetterOrDigit() }

fun stateJson(state: MirrorState): JSONObject {
    val screen = state.screen
    fun choices(list: List<Choice>) = JSONArray(
        list.map { JSONObject().put("id", it.id).put("label", it.label).put("detail", it.detail ?: JSONObject.NULL) },
    )
    return JSONObject()
        .put("version", state.version)
        .put("foreground", state.foreground)
        .put("locked", state.locked)
        .put("atLatest", state.atLatest)
        .put(
            "notice",
            state.notice?.let { JSONObject().put("title", it.title).put("text", it.text ?: JSONObject.NULL) } ?: JSONObject.NULL,
        )
        .put("screen", screen.kind.name.lowercase())
        .put("title", screen.title ?: JSONObject.NULL)
        .put("sessions", choices(screen.sessions))
        .put("lines", JSONArray(if (screen.kind == ScreenKind.TRANSCRIPT) state.history else screen.lines))
        .put(
            "prompt",
            screen.prompt?.let { JSONObject().put("question", it.question).put("options", choices(it.options)) } ?: JSONObject.NULL,
        )
}
