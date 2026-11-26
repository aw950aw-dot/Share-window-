package com.aw950aw.sharewindow

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ShareWindowAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("smartshare", MODE_PRIVATE) }
    private var overlayView: View? = null
    private var lastCandidates: List<String> = emptyList()
    private var showHiddenList = false
    private var secretPresses = 0
    private var lastSecretPressAt = 0L

    private val scanRunnable = Runnable { scanChooserAndShow() }
    private val hideRunnable = Runnable {
        chooserExpected = false
        removeOverlay()
    }

    private var chooserExpected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs.edit().putString("service_state", "connected").apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventClass = event.className?.toString().orEmpty()
        val eventPackage = event.packageName?.toString().orEmpty()

        if (eventClass.contains("ChooserActivity", ignoreCase = true)) {
            chooserExpected = true
            prefs.edit()
                .putString("last_class", eventClass)
                .putString("last_package", eventPackage)
                .putString("last_event_time", System.currentTimeMillis().toString())
                .apply()
            handler.removeCallbacks(hideRunnable)
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, 220)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage.isNotBlank() &&
            eventPackage != "android" &&
            eventPackage != packageName
        ) {
            handler.removeCallbacks(scanRunnable)
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, 350)
        } else if (chooserExpected &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventPackage == "android"
        ) {
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, 450)
        }
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scanChooserAndShow() {
        if (!chooserExpected) return
        val root = findChooserRoot()
        if (root == null) {
            val message = "ChooserActivity זוהתה, אבל שירות הנגישות לא קיבל root לחלון android."
            prefs.edit().putString("last_tree", message).apply()
            if (prefs.getBoolean("overlay_enabled", true)) {
                showOverlay(emptyList())
            }
            return
        }

        val treeLines = ArrayList<String>()
        val candidates = LinkedHashSet<String>()
        val counter = intArrayOf(0)
        collectTree(root, 0, treeLines, candidates, counter)

        lastCandidates = candidates.toList().sorted()
        prefs.edit()
            .putStringSet("last_candidates", lastCandidates.toSet())
            .putString(
                "last_tree",
                "חלון: ${root.className}\nחבילה: ${root.packageName}\nצמתים שנבדקו: ${counter[0]}\nיעדים/פעולות לחיצים: ${lastCandidates.size}\n\n" +
                    treeLines.joinToString("\n")
            )
            .apply()

        if (prefs.getBoolean("overlay_enabled", true)) {
            showOverlay(lastCandidates)
        } else {
            removeOverlay()
        }
    }

    private fun findChooserRoot(): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == "android") return root
            }
        } catch (_: Exception) {
            // Some OEM builds temporarily reject window enumeration. Fall back below.
        }
        val active = rootInActiveWindow
        return if (active?.packageName?.toString() == "android") active else null
    }

    private fun collectTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        lines: MutableList<String>,
        candidates: MutableSet<String>,
        counter: IntArray
    ) {
        if (counter[0] >= 320 || depth > 22) return
        counter[0]++
        val text = node.text?.toString().orEmpty().replace("\n", " ").trim()
        val description = node.contentDescription?.toString().orEmpty().replace("\n", " ").trim()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val label = if (text.isNotBlank()) text else description
        val safeLabel = label.take(100)
        lines.add(
            "${"  ".repeat(depth)}${node.className} " +
                "click=${node.isClickable} visible=${node.isVisibleToUser} " +
                "bounds=${bounds.flattenToString()} label=${safeLabel}"
        )

        if (label.length in 2..80 && node.isVisibleToUser && hasClickableAncestor(node)) {
            candidates.add(label)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectTree(child, depth + 1, lines, candidates, counter)
            if (counter[0] >= 320) break
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 7) {
            if (current.isClickable) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun showOverlay(candidates: List<String>) {
        lastCandidates = candidates
        removeOverlay()

        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(205, 8, 14, 28))
            isClickable = true
            isFocusable = false
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 251, 254))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(12).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val title = TextView(this).apply {
            text = "SmartShare"
            textSize = 23f
            setTextColor(Color.rgb(27, 42, 69))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setOnLongClickListener {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSecretPressAt > 10_000L) secretPresses = 0
                lastSecretPressAt = now
                secretPresses++
                if (secretPresses >= 4) {
                    secretPresses = 0
                    showHiddenList = !showHiddenList
                    Toast.makeText(
                        this@ShareWindowAccessibilityService,
                        if (showHiddenList) "הרשימה המלאה נפתחה" else "חזרה לרשימה המצומצמת",
                        Toast.LENGTH_SHORT
                    ).show()
                    showOverlay(lastCandidates)
                }
                true
            }
        }
        topRow.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))

        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { removeOverlay() }
        }
        topRow.addView(close, LinearLayout.LayoutParams(dp(42), dp(46)))
        card.addView(topRow)

        val filterEnabled = prefs.getBoolean("filter_enabled", false)
        val selected = prefs.getStringSet("selected_targets", emptySet()).orEmpty()
        val visibleCandidates = if (filterEnabled && !showHiddenList) {
            candidates.filter { selected.contains(it) }
        } else {
            candidates
        }

        val subtitle = TextView(this).apply {
            text = when {
                showHiddenList -> "מצב נסתר — כל הפריטים שזוהו"
                filterEnabled -> "הרשימה המצומצמת שלך"
                else -> "הפריטים שנמצאו בחלון השיתוף"
            }
            textSize = 13f
            setTextColor(Color.rgb(95, 104, 120))
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(subtitle)

        val listScroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        if (visibleCandidates.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (filterEnabled && !showHiddenList) {
                    "לא סומנו יעדים להצגה. אפשר לבחור יעדים במסך ההגדרות או לבצע ארבע לחיצות ארוכות על הכותרת כדי לחשוף את הרשימה המלאה."
                } else {
                    "לא זוהו פריטים לחיצים בחלון. חזור למסך האבחון ובדוק את עץ הנגישות."
                }
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(22), dp(10), dp(22))
            }
            list.addView(empty)
        } else {
            visibleCandidates.forEach { label ->
                val target = Button(this).apply {
                    text = label
                    textSize = 14f
                    isAllCaps = false
                    setOnClickListener { clickOriginalTarget(label) }
                }
                list.addView(target, LinearLayout.LayoutParams(-1, dp(52)).apply {
                    bottomMargin = dp(5)
                })
            }
        }

        listScroll.addView(list)
        card.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = TextView(this).apply {
            text = "ארבע לחיצות ארוכות על הכותרת פותחות את כל הרשימה"
            textSize = 11f
            setTextColor(Color.rgb(110, 118, 132))
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }
        card.addView(footer)

        screen.addView(card, FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(16), dp(28), dp(16), dp(28))
        })

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "SmartShare accessibility overlay"
        }

        try {
            wm.addView(screen, params)
            overlayView = screen
        } catch (error: Exception) {
            prefs.edit().putString(
                "last_tree",
                (prefs.getString("last_tree", "").orEmpty()) +
                    "\n\nשגיאה בהצגת שכבת־העל: ${error.javaClass.simpleName}: ${error.message}"
            ).apply()
            overlayView = null
        }
    }

    private fun clickOriginalTarget(label: String) {
        val root = findChooserRoot()
        if (root == null) {
            Toast.makeText(this, "חלון השיתוף המקורי כבר לא זמין.", Toast.LENGTH_SHORT).show()
            removeOverlay()
            return
        }

        val clicked = clickMatchingNode(root, label, 0)
        if (clicked) {
            handler.postDelayed({ removeOverlay() }, 280)
        } else {
            Toast.makeText(
                this,
                "לא הצלחתי להפעיל את הפריט הזה. ייתכן שמבנה חלון השיתוף שונה.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clickMatchingNode(node: AccessibilityNodeInfo, label: String, depth: Int): Boolean {
        if (depth > 22) return false
        val nodeText = node.text?.toString()?.trim().orEmpty()
        val nodeDescription = node.contentDescription?.toString()?.trim().orEmpty()
        if (nodeText == label || nodeDescription == label) {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps <= 7) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                current = current.parent
                steps++
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickMatchingNode(child, label, depth + 1)) return true
        }
        return false
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
