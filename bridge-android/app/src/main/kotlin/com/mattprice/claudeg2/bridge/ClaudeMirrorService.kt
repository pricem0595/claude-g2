package com.mattprice.claudeg2.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.content.pm.ActivityInfo
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException

private const val TAG = "ClaudeMirrorService"
private const val DEBOUNCE_MS = 150L

// After a scroll: snapshot this often, for this long, to catch the animation's in-between frames.
private const val SCROLL_CAPTURE_EVERY_MS = 50L
private const val SCROLL_CAPTURE_MS = 700L

// Events can be missed (and none arrive once the user switches away), so also re-check this often.
private const val POLL_MS = 1_500L

// Trees deeper than this are cut off; Compose screens are nowhere near it.
private const val MAX_DEPTH = 60

data class ServiceState(val running: Boolean = false, val error: String? = null)

/**
 * Reads the Claude app's screen and taps in it for the glasses, and hosts the loopback HTTP API.
 * An accessibility service is kept running by the system while it's enabled, so it hosts the
 * server itself; no foreground service is needed. It only ever reads the Claude app's window.
 */
class ClaudeMirrorService : AccessibilityService(), ScreenSource {
    private lateinit var settings: BridgeSettings
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var server: BridgeHttpServer? = null

    /** When the glasses app last reached the bridge, in epoch ms; 0 if never since it started. */
    val lastGlassesContact: Long get() = server?.lastRequestAt ?: 0L
    private var overlay: View? = null

    /** Live nodes from the latest capture, by UiNode id, so taps reach the real control. */
    @Volatile private var liveNodes: Map<String, AccessibilityNodeInfo> = emptyMap()

    val controller = MirrorController(this)

    private val capture = Runnable { captureNow() }
    private val poll = object : Runnable {
        override fun run() {
            captureNow()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = BridgeSettings(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        // Only the Claude app's events; the package can be changed in the bridge app.
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(settings.claudePackage) }

        worker = HandlerThread("mirror").also { it.start() }
        handler = Handler(worker.looper)

        val http = BridgeHttpServer(controller, { settings.token }, status = {
            JSONObject().put("service", true).put("package", settings.claudePackage)
        })
        try {
            http.start(IDLE_CONNECTION_MS, false)
            server = http
            _state.value = ServiceState(running = true)
        } catch (e: IOException) {
            Log.e(TAG, "Can't listen on port $BRIDGE_PORT", e)
            _state.value = ServiceState(error = "Port $BRIDGE_PORT is in use")
        }
        instance = this
        applyKeepAwake()
        handler.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::handler.isInitialized) return
        // The service only receives the Claude app's events, so this is a Claude notification.
        // It arrives even while the phone is locked, when nothing else can be read.
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val extras = (event.parcelableData as? Notification)?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            controller.onLockState(isLocked())
            controller.onNotification(title, text)
            return
        }
        handler.removeCallbacks(capture)
        handler.postDelayed(capture, DEBOUNCE_MS)
    }

    /** Locked, or the screen is off: either way the Claude app isn't being drawn. */
    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(PowerManager::class.java).isInteractive

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        server?.stop()
        server = null
        removeOverlay()
        if (::worker.isInitialized) worker.quitSafely()
        _state.value = ServiceState()
        super.onDestroy()
    }

    /** Re-applies the keep-screen-on setting after the bridge app changes it. */
    fun applyKeepAwake() {
        val view = overlay ?: return addOverlay()
        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(view, overlayParams()) }
    }

    private fun captureNow() {
        val locked = isLocked()
        controller.onLockState(locked)
        // Behind the lock screen the Claude window isn't there; keep the last screen as it was.
        if (locked) return
        val pkg = settings.claudePackage
        val root = try {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName == pkg }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't read windows", e)
            null
        }
        if (root == null) {
            controller.onSnapshot(null)
            return
        }
        val live = HashMap<String, AccessibilityNodeInfo>()
        val tree = copy(root, "0", 0, live)
        liveNodes = live
        controller.onSnapshot(tree)
    }

    private fun copy(info: AccessibilityNodeInfo, path: String, depth: Int, live: MutableMap<String, AccessibilityNodeInfo>): UiNode {
        val rect = Rect().also { info.getBoundsInScreen(it) }
        val children = if (depth >= MAX_DEPTH) emptyList() else (0 until info.childCount).mapNotNull { i ->
            info.getChild(i)?.takeIf { it.isVisibleToUser }?.let { copy(it, "$path.$i", depth + 1, live) }
        }
        val node = UiNode(
            className = info.className?.toString() ?: "",
            text = info.text?.toString(),
            desc = info.contentDescription?.toString(),
            viewId = info.viewIdResourceName,
            packageName = info.packageName?.toString(),
            clickable = info.isClickable,
            scrollable = info.isScrollable,
            canScrollForward = info.isScrollable && info.actionList.any { it.id == AccessibilityAction.ACTION_SCROLL_FORWARD.id },
            editable = info.isEditable,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
            path = path,
        )
        live[node.id] = info
        return node
    }

    override suspend fun click(node: UiNode): Boolean {
        val info = liveNodes[node.id]?.takeIf { it.refresh() }
        val target = info?.let { generateSequence(it) { n -> n.parent }.firstOrNull { n -> n.isClickable } }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Some Compose controls don't take ACTION_CLICK; tap where the control is drawn instead.
        return tap(node.bounds.centerX.toFloat(), node.bounds.centerY.toFloat())
    }

    private fun mainList(): AccessibilityNodeInfo? = liveNodes.values.filter { it.isScrollable && it.refresh() }
        .maxByOrNull { Rect().also(it::getBoundsInScreen).let { r -> r.width().toLong() * r.height() } }

    override suspend fun scroll(up: Boolean): Boolean {
        val list = mainList() ?: return false
        val action = if (up) AccessibilityAction.ACTION_SCROLL_BACKWARD else AccessibilityAction.ACTION_SCROLL_FORWARD
        if (!list.performAction(action.id)) return false
        // The app animates the scroll, and each frame's event restarts the capture debounce, so
        // only the end position would be seen: often a whole screen on, sharing no message with
        // the last snapshot. Capture the frames in between so the history stitches without a gap.
        val until = System.currentTimeMillis() + SCROLL_CAPTURE_MS
        while (System.currentTimeMillis() < until) {
            captureNow()
            delay(SCROLL_CAPTURE_EVERY_MS)
        }
        return true
    }

    override suspend fun pageToLatest(maxPages: Int): Int {
        var pages = 0
        while (pages < maxPages) {
            val list = mainList() ?: break
            if (!list.performAction(AccessibilityAction.ACTION_SCROLL_FORWARD.id)) break
            pages++
            delay(DEBOUNCE_MS)
            captureNow()
        }
        return pages
    }

    override suspend fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private suspend fun tap(x: Float, y: Float): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 50))
            .build()
        val done = CompletableDeferred<Boolean>()
        val sent = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
            },
            null,
        )
        return sent && done.await()
    }

    /**
     * A 1x1 invisible overlay, present while the service runs. It asks for portrait, so the
     * phone doesn't rotate the Claude app into a landscape layout the parser has never seen,
     * and, if that setting is on, holds the screen on.
     */
    private fun overlayParams() = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            (if (settings.keepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
        PixelFormat.TRANSLUCENT,
    ).apply { screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

    private fun addOverlay() {
        if (overlay != null) return
        val view = View(this)
        runCatching { getSystemService(WindowManager::class.java).addView(view, overlayParams()) }
            .onSuccess { overlay = view }
            .onFailure { Log.w(TAG, "Couldn't add the portrait / keep-awake overlay", it) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    companion object {
        private val _state = MutableStateFlow(ServiceState())

        /** Service status, for the bridge app's screen. */
        val state: StateFlow<ServiceState> = _state

        /** The running service, or null when it's disabled. Same process only. */
        @Volatile var instance: ClaudeMirrorService? = null
            private set
    }
}
