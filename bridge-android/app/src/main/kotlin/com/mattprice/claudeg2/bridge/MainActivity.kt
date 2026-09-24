package com.mattprice.claudeg2.bridge

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

private const val REFRESH_MS = 1_000L

// The glasses app long-polls every 25 s at most, so older than this means it isn't running.
private const val GLASSES_SEEN_MS = 40_000L

private val DONE = Color.parseColor("#4CAF50")
private val TODO = Color.parseColor("#FFB300")
private val PROBLEM = Color.parseColor("#EF5350")

/**
 * Setup screen: four steps to get the mirror working, each with a live check. Refreshes every
 * second while visible, since the mirror, the glasses app and the Claude app change on their own.
 */
class MainActivity : Activity() {
    private lateinit var settings: BridgeSettings
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }
    private var pad = 0

    private lateinit var mirrorStatus: TextView
    private lateinit var restrictedHelp: View
    private lateinit var tokenView: TextView
    private lateinit var glassesStatus: TextView
    private lateinit var claudeStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BridgeSettings(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        pad = (16 * resources.displayMetrics.density).toInt()

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Claude G2 Bridge", 26f, bold = true))
            addView(text("v$version", 14f, alpha = 0.6f))
            addView(
                text(
                    "Mirrors the Claude app on your Even G2 glasses. Follow the steps below once; " +
                        "after that it runs by itself.",
                    15f,
                ).apply { setPadding(0, pad / 2, 0, 0) },
            )
        }

        // 1. Turn on the mirror.
        mirrorStatus = text("", 15f, bold = true)
        restrictedHelp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                text(
                    "If Android says \"Restricted setting\", tap below, open the ⋮ menu at the top " +
                        "right, choose \"Allow restricted settings\", then try again.",
                    14f,
                    alpha = 0.8f,
                ),
            )
            addView(button("Open app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        val step1 = step(
            1, "Turn on the mirror",
            "In Accessibility settings, open \"Installed apps\" (or \"Downloaded apps\"), choose " +
                "\"Claude G2 mirror\", and turn it on.",
            mirrorStatus,
            button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            restrictedHelp,
        )

        // 2. Pair the glasses app.
        tokenView = text("", 34f, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        glassesStatus = text("", 15f, bold = true)
        val step2 = step(
            2, "Pair the glasses app",
            "Open Claude G2 on your glasses from the Even app. On its phone page, enter this " +
                "pairing token and tap Save.",
            tokenView,
            glassesStatus,
            button("New pairing token", secondary = true) {
                settings.resetToken()
                render()
            },
        )

        // 3. Open a Claude Code session.
        claudeStatus = text("", 15f, bold = true)
        val step3 = step(
            3, "Open a Claude Code session",
            "In the Claude app, open Code and pick a Remote Control session. The glasses show " +
                "whatever the Claude app shows, so leave it open on screen.",
            claudeStatus,
            button("Open the Claude app") {
                packageManager.getLaunchIntentForPackage(settings.claudePackage)?.let(::startActivity)
            },
        )

        // 4. Keep it running.
        val keepAwake = Switch(this).apply {
            text = "Keep the screen on while mirroring"
            textSize = 15f
            isChecked = settings.keepAwake
            // On: the same green as the ✓ lines; the theme's default was too dark to read as on.
            val checked = intArrayOf(android.R.attr.state_checked)
            thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(DONE, Color.LTGRAY))
            trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(Color.argb(140, Color.red(DONE), Color.green(DONE), Color.blue(DONE)), Color.GRAY),
            )
            setOnCheckedChangeListener { _, on ->
                settings.keepAwake = on
                ClaudeMirrorService.instance?.applyKeepAwake()
            }
        }
        val step4 = step(
            4, "Keep it running",
            "While the mirror is on, the phone stays in portrait and, with this switch on, " +
                "awake, so the Claude app keeps drawing. If the phone locks, the glasses switch to " +
                "notification-only mode until you unlock it.",
            keepAwake,
        )

        val footer = text(
            "Privacy: the mirror only reads the Claude app. It serves it at 127.0.0.1:$BRIDGE_PORT " +
                "on this phone, only to apps that send the pairing token.",
            13f,
            alpha = 0.6f,
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (view in listOf(header, step1, step2, step3, step4, footer)) {
                addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
            }
        }
        content.setPadding(0, 0, 0, pad * 2)
        // Android 15+ draws apps edge to edge, under the status and navigation bars. Padding the
        // scroll view itself (which clips to its padding) keeps scrolled content out from under
        // them; padding the content would scroll away with it. No action bar: see the manifest.
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(pad + bars.left, bars.top, pad + bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val service = ClaudeMirrorService.instance
        val serviceState = ClaudeMirrorService.state.value

        when {
            serviceState.error != null -> status(mirrorStatus, PROBLEM, "Mirror stopped: ${serviceState.error}")
            service != null && serviceState.running -> status(mirrorStatus, DONE, "✓ The mirror is on")
            else -> status(mirrorStatus, TODO, "• The mirror is off")
        }
        restrictedHelp.visibility = if (service == null) View.VISIBLE else View.GONE

        tokenView.text = settings.token
        val sinceGlasses = System.currentTimeMillis() - (service?.lastGlassesContact ?: 0L)
        when {
            service == null -> status(glassesStatus, TODO, "• Turn on the mirror first")
            sinceGlasses < GLASSES_SEEN_MS -> status(glassesStatus, DONE, "✓ The glasses app is connected")
            else -> status(glassesStatus, TODO, "• Waiting for the glasses app")
        }

        val seen = service?.controller?.state?.value?.screen?.kind
        when {
            service == null -> status(claudeStatus, TODO, "• Turn on the mirror first")
            seen != null && seen != ScreenKind.UNKNOWN -> status(claudeStatus, DONE, "✓ The Claude app is mirrored")
            else -> status(claudeStatus, TODO, "• Waiting for the Claude app")
        }
    }

    private fun status(view: TextView, color: Int, text: String) {
        view.text = text
        view.setTextColor(color)
    }

    /** A numbered step: title, explanation, then its own views, on a rounded card. */
    private fun step(number: Int, title: String, body: String, vararg views: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = pad.toFloat()
                setColor(Color.argb(24, 128, 128, 128))
            }
            addView(text("$number.  $title", 19f, bold = true))
            addView(text(body, 15f, alpha = 0.85f).apply { setPadding(0, pad / 3, 0, pad / 3) })
            for (view in views) addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 3 })
        }

    private fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        this.alpha = alpha
    }

    private fun button(label: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        if (secondary) alpha = 0.8f
        setOnClickListener { onClick() }
    }
}
