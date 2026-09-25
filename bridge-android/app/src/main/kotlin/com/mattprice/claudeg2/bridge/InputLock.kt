package com.mattprice.claudeg2.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

private const val TAG = "InputLock"

/** How long the lock button must be held to lock or unlock. A tap does nothing. */
private const val HOLD_MS = 700L

private const val BUTTON_DP = 44
private const val MARGIN_DP = 8

private const val LOCKED_ICON = "🔒" // 🔒
private const val UNLOCKED_ICON = "🔓" // 🔓

/**
 * The phone stays awake while the Claude app is on screen, so in a pocket it can take stray
 * touches. This puts a small lock button in the top right corner while the Claude app is in
 * front; holding it covers the screen with a shield that swallows every touch, until it's held
 * again. The glasses keep working: their taps are accessibility actions, which don't go through
 * the touch screen, and the gesture fallback lifts the shield for its moment (see [letThrough]).
 *
 * The system's own gestures (the navigation bar, pulling down the notification shade) still get
 * through: an app's window can't block those. Main thread only.
 */
class InputLock(
    private val context: Context,
    /** Called with true when the shield goes up over the Claude app, false when it comes down. */
    private val onShieldChange: (Boolean) -> Unit = {},
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    /** Stays set while the Claude app is away (a call came in), and applies again when it's back. */
    var locked = false
        private set

    private var shown = false
    private var button: TextView? = null
    private var shield: View? = null
    private var letThroughCount = 0

    /** Shows the lock (and, if locked, the shield) while the Claude app is in front; hides both otherwise. */
    fun show(claudeInFront: Boolean) {
        if (shown == claudeInFront) return
        shown = claudeInFront
        if (claudeInFront) {
            if (locked) addShield()
            addButton()
        } else {
            removeShield()
            removeButton()
        }
    }

    /** Removes everything, e.g. when the service stops. */
    fun hide() {
        shown = false
        removeShield()
        removeButton()
    }

    /**
     * Lets touches through the shield while [block] runs: for the bridge's own injected gestures,
     * which would otherwise land on the shield like any other touch.
     */
    suspend fun <T> letThrough(block: suspend () -> T): T {
        onMain { if (letThroughCount++ == 0) setShieldTouchable(false) }
        try {
            return block()
        } finally {
            onMain { if (--letThroughCount == 0) setShieldTouchable(true) }
        }
    }

    private fun toggle() {
        locked = !locked
        if (locked) {
            addShield()
            // The shield was added last: put the button back above it.
            removeButton()
            addButton()
        } else {
            removeShield()
        }
        button?.let(::style)
        button?.performHapticFeedback(if (locked) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        if (button != null) return
        val view = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            contentDescription = "Hold to lock or unlock the screen"
        }
        style(view)
        val hold = Runnable { toggle() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(HOLD_MS).start()
                    main.postDelayed(hold, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            true
        }
        val size = (BUTTON_DP * density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (MARGIN_DP * density).toInt()
            y = statusBarHeight() + (MARGIN_DP * density).toInt()
        }
        runCatching { windows.addView(view, params) }
            .onSuccess { button = view }
            .onFailure { Log.w(TAG, "Couldn't add the lock button", it) }
    }

    private fun style(view: TextView) {
        view.text = if (locked) LOCKED_ICON else UNLOCKED_ICON
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (locked) Color.argb(220, 200, 60, 50) else Color.argb(140, 40, 40, 40))
        }
        view.alpha = if (locked) 1f else 0.75f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addShield() {
        if (shield != null) return
        val view = View(context).apply {
            setBackgroundColor(Color.argb(40, 0, 0, 0))
            // Swallow the touch, and nudge the lock so it's clear why nothing happened.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) pulseButton()
                true
            }
        }
        runCatching { windows.addView(view, shieldParams(touchable = letThroughCount == 0)) }
            .onSuccess {
                shield = view
                onShieldChange(true)
            }
            .onFailure { Log.w(TAG, "Couldn't add the input shield", it) }
    }

    private fun shieldParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun setShieldTouchable(touchable: Boolean) {
        val view = shield ?: return
        runCatching { windows.updateViewLayout(view, shieldParams(touchable)) }
    }

    private fun pulseButton() {
        val view = button ?: return
        view.animate().cancel()
        view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    private fun removeButton() {
        button?.let { runCatching { windows.removeView(it) } }
        button = null
    }

    private fun removeShield() {
        val view = shield ?: return
        runCatching { windows.removeView(view) }
        shield = null
        onShieldChange(false)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * density).toInt()
    }

    private suspend fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        main.post {
            block()
            done.complete(Unit)
        }
        done.await()
    }
}
