package com.aw950aw.sharewindow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ShareWindowAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("smartshare", MODE_PRIVATE) }
    private var overlayView: View? = null
    private var overlayListContainer: LinearLayout? = null
    private var overlaySubtitleView: TextView? = null
    private var cachedShareAppIcons: List<ShareAppIcon>? = null
    private var lastCandidates: List<String> = emptyList()
    private data class ShareAppIcon(val label: String, val packageName: String, val drawable: Drawable)
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

            // Do not cover file-upload pickers: first inspect the chooser's visible content.
            val initialRoot = findChooserRoot()
            val initialVerdict = initialRoot?.let { classifyShareChooser(it) }
            if (initialVerdict != "file-picker" && prefs.getBoolean("overlay_enabled", true)) {
                // ChooserActivity is the strong signal for ACTION_SEND. Cover immediately,
                // without waiting for the text tree to finish rendering.
                showOverlay(emptyList(), loading = true)
            } else {
                chooserExpected = false
                removeOverlay()
            }

            // Build the target list only after the native chooser has been covered.
            handler.postDelayed(scanRunnable, 15)
            return
        }

        // Opening Android's notification shade changes the active window to SystemUI.
        // Keep the sharesheet overlay attached; when the shade closes it will be visible again.
        if (eventPackage == "com.android.systemui" ||
            eventClass.contains("NotificationShade", ignoreCase = true) ||
            eventClass.contains("StatusBar", ignoreCase = true)
        ) {
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
            handler.postDelayed(scanRunnable, 80)
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
            prefs.edit().putString(
                "last_tree",
                "ChooserActivity זוהתה, אך שירות הנגישות עדיין לא קיבל עץ חלון. הבדיקה תנסה שוב באירוע התוכן הבא."
            ).apply()
            return
        }

        val verdict = classifyShareChooser(root)
        if (verdict == "file-picker") {
            chooserExpected = false
            removeOverlay()
            prefs.edit().putString(
                "last_tree",
                "החלון זוהה כמסך בחירת קובץ/העלאה; SmartShare לא הוצגה."
            ).apply()
            return
        }

        val treeLines = ArrayList<String>()
        val candidates = LinkedHashSet<String>()
        val counter = intArrayOf(0)
        collectTree(root, 0, treeLines, candidates, counter)

        lastCandidates = candidates.toList()
        prefs.edit()
            .putStringSet("last_candidates", lastCandidates.toSet())
            .putString(
                "last_tree",
                "סיווג: מסך שיתוף\nחלון: ${root.className}\nחבילה: ${root.packageName}\nצמתים שנבדקו: ${counter[0]}\nיעדים/פעולות לחיצים: ${lastCandidates.size}\n\n" +
                    treeLines.joinToString("\n")
            )
            .apply()

        if (prefs.getBoolean("overlay_enabled", true)) {
            showOverlay(lastCandidates)
        } else {
            removeOverlay()
        }
    }

    /**
     * Accessibility does not expose the launching Intent/action to this service.
     * Use visible chooser labels to positively identify a share sheet and reject
     * recognizable document/file-picker screens. Unknown layouts are ignored.
     */
    private fun classifyShareChooser(root: AccessibilityNodeInfo): String {
        val text = StringBuilder()
        val visited = intArrayOf(0)

        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (visited[0] >= 180 || depth > 18) return
            visited[0]++
            if (node.isVisibleToUser) {
                node.text?.toString()?.let { text.append(' ').append(it) }
                node.contentDescription?.toString()?.let { text.append(' ').append(it) }
                node.viewIdResourceName?.let { text.append(' ').append(it) }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                collect(child, depth + 1)
            }
        }
        collect(root, 0)

        val value = text.toString().lowercase(java.util.Locale.ROOT)
        val filePickerSignals = listOf(
            "choose a file", "select a file", "select files", "choose file",
            "browse files", "file picker", "upload a file", "choose photos",
            "select photos", "photos picker", "recent files", "open from",
            "בחירת קובץ", "בחר קובץ", "בחר קבצים", "בחירת תמונות",
            "בחר תמונות", "העלאת קובץ", "העלאת קבצים", "עיון בקבצים",
            "קבצים אחרונים", "מסמכים אחרונים"
        )
        if (filePickerSignals.any { value.contains(it) }) return "file-picker"

        val shareSignals = listOf(
            "sharing", "share with", "share", "nearby share", "quick share",
            "send to", "שיתוף", "שתף", "לשתף", "שליחה", "שלח אל"
        )
        return if (shareSignals.any { value.contains(it) }) "share" else "unknown"
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

    private fun showOverlay(
        candidates: List<String>,
        loading: Boolean = false,
        unavailable: Boolean = false
    ) {
        lastCandidates = candidates

        // Keep one attached accessibility window to prevent the native chooser flashing through.
        var screen = overlayView as? FrameLayout
        var list = overlayListContainer
        if (screen == null || list == null) {
            screen = FrameLayout(this).apply {
                // Fully black scrim: sliding the sheet down must never reveal
                // the native Android chooser underneath it.
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = false
                setOnClickListener { closeChooser() }
            }

            val sheetHeight = (resources.displayMetrics.heightPixels - dp(20)).coerceAtLeast(dp(420))
            val peekHeight = (resources.displayMetrics.heightPixels * 0.70f).toInt().coerceAtMost(sheetHeight)
            val sheet = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(16), dp(7), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(249, 250, 253))
                    cornerRadii = floatArrayOf(
                        dp(28).toFloat(), dp(28).toFloat(),
                        dp(28).toFloat(), dp(28).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                }
                elevation = dp(16).toFloat()
            }

            val dragArea = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                isClickable = true
            }
            val handle = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(165, 171, 181))
                    cornerRadius = dp(4).toFloat()
                }
                contentDescription = "גרירת חלונית השיתוף"
            }
            dragArea.addView(handle, LinearLayout.LayoutParams(dp(38), dp(5)))
            sheet.addView(dragArea, LinearLayout.LayoutParams(-1, dp(28)))

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            val title = TextView(this).apply {
                text = "SmartShare"
                textSize = 21f
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
                        renderOverlayContents(lastCandidates, false, false)
                    }
                    true
                }
            }
            topRow.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))

            val close = TextView(this).apply {
                text = "✕"
                textSize = 22f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(4), 0)
                setOnClickListener { closeChooser() }
            }
            topRow.addView(close, LinearLayout.LayoutParams(dp(42), dp(48)))
            sheet.addView(topRow)

            val subtitle = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.rgb(95, 104, 120))
                setPadding(dp(2), 0, dp(2), dp(9))
            }
            overlaySubtitleView = subtitle
            sheet.addView(subtitle)

            val scroll = ScrollView(this).apply { isFillViewport = true }
            list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            scroll.addView(list)
            sheet.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            val params = FrameLayout.LayoutParams(-1, sheetHeight, Gravity.BOTTOM)
            screen.addView(sheet, params)
            sheet.translationY = (sheetHeight - peekHeight).toFloat()

            var downY = 0f
            var startTranslation = 0f
            dragArea.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        startTranslation = sheet.translationY
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - downY
                        val maxDrag = sheetHeight.toFloat()
                        sheet.translationY = (startTranslation + dy).coerceIn(0f, maxDrag)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        val dy = event.rawY - downY
                        val dismissAt = (sheetHeight * 0.78f)
                        val movedDownFar = sheet.translationY >= dismissAt
                        when {
                            movedDownFar -> closeChooser()
                            dy < -dp(65) -> sheet.animate().translationY(0f).setDuration(180).start()
                            dy > dp(65) && sheet.translationY > (sheetHeight - peekHeight + dp(100)) ->
                                closeChooser()
                            else -> {
                                val target = if (sheet.translationY < (sheetHeight - peekHeight) / 2f) {
                                    0f
                                } else {
                                    (sheetHeight - peekHeight).toFloat()
                                }
                                sheet.animate().translationY(target).setDuration(180).start()
                            }
                        }
                        true
                    }
                    else -> true
                }
            }

            try {
                val wmParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(screen, wmParams)
                overlayView = screen
                overlayListContainer = list
            } catch (error: Exception) {
                prefs.edit().putString(
                    "last_tree",
                    (prefs.getString("last_tree", "").orEmpty()) +
                        "\n\nשגיאה בהצגת שכבת־העל: ${error.javaClass.simpleName}: ${error.message}"
                ).apply()
                overlayView = null
                overlayListContainer = null
                overlaySubtitleView = null
                return
            }
        }

        renderOverlayContents(candidates, loading, unavailable)
    }

    private fun renderOverlayContents(
        candidates: List<String>,
        loading: Boolean,
        unavailable: Boolean
    ) {
        val list = overlayListContainer ?: return
        val filterEnabled = prefs.getBoolean("filter_enabled", false)
        val selected = prefs.getStringSet("selected_targets", emptySet()).orEmpty()
        val visibleCandidates = if (filterEnabled && !showHiddenList) {
            candidates.filter { selected.contains(it) }
        } else {
            candidates
        }

        overlaySubtitleView?.text = when {
            showHiddenList -> "מצב נסתר — כל הפריטים שזוהו"
            filterEnabled -> "הרשימה המצומצמת שלך"
            else -> "הפריטים שנמצאו בחלון השיתוף"
        }

        list.removeAllViews()
        if (visibleCandidates.isEmpty()) {
            val empty = TextView(this).apply {
                text = when {
                    loading -> "מכין את רשימת יעדי השיתוף…"
                    unavailable -> "חלון השיתוף זוהה, אך כרגע Android לא מאפשר לקרוא את רשימת היעדים."
                    filterEnabled && !showHiddenList ->
                        "לא סומנו יעדים להצגה. אפשר לבחור יעדים במסך ההגדרות או לבצע ארבע לחיצות ארוכות על הכותרת כדי לחשוף את הרשימה המלאה."
                    else -> "לא זוהו פריטים לחיצים בחלון. חזור למסך האבחון ובדוק את עץ הנגישות."
                }
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(22), dp(10), dp(22))
                gravity = Gravity.CENTER
            }
            list.addView(empty)
            return
        }

        // Native Android sharesheet presents app targets in icon tiles. Preserve
        // their accessibility-tree order and render four columns per row.
        var row: LinearLayout? = null
        visibleCandidates.forEachIndexed { index, label ->
            if (index % 4 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.TOP
                }
                list.addView(row, LinearLayout.LayoutParams(-1, dp(120)))
            }

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(4), dp(3), dp(4))
                setOnClickListener { clickOriginalTarget(label) }
            }

            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val drawable = findShareAppIcon(label)
                    ?: getDrawable(android.R.drawable.sym_def_app_icon)
                setImageDrawable(drawable)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(237, 240, 247))
                }
                elevation = dp(1).toFloat()
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))

            val caption = TextView(this).apply {
                text = label
                textSize = 11f
                maxLines = 2
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(35, 43, 58))
                setPadding(0, dp(4), 0, 0)
            }
            tile.addView(caption, LinearLayout.LayoutParams(-1, dp(36)))

            row?.addView(tile, LinearLayout.LayoutParams(0, dp(104), 1f))
        }
    }

    private fun findShareAppIcon(label: String): Drawable? {
        val normalized = label.trim().lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty()) return null
        val apps = getShareAppIcons()
        val exact = apps.firstOrNull {
            it.label.trim().lowercase(java.util.Locale.ROOT) == normalized
        }
        val match = exact ?: apps.firstOrNull {
            val appLabel = it.label.trim().lowercase(java.util.Locale.ROOT)
            appLabel.isNotEmpty() &&
                (normalized.contains(appLabel) || appLabel.contains(normalized))
        }
        return match?.drawable
    }

    private fun getShareAppIcons(): List<ShareAppIcon> {
        cachedShareAppIcons?.let { return it }
        val byPackage = LinkedHashMap<String, ShareAppIcon>()
        val intents = listOf(
            Intent(Intent.ACTION_SEND).apply { type = "*/*" },
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*" }
        )
        intents.forEach { intent ->
            try {
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .forEach { info ->
                        val activityInfo = info.activityInfo
                        if (activityInfo != null) {
                            val packageName = activityInfo.packageName
                            if (!byPackage.containsKey(packageName)) {
                                val appLabel = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                                if (appLabel.isNotEmpty()) {
                                    val icon = try {
                                        info.loadIcon(packageManager)
                                    } catch (_: Exception) {
                                        null
                                    }
                                    if (icon != null) {
                                        byPackage[packageName] = ShareAppIcon(appLabel, packageName, icon)
                                    }
                                }
                            }
                        }
                    }
            } catch (_: Exception) {
            }
        }
        return byPackage.values.toList().also { cachedShareAppIcons = it }
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

    private fun closeChooser() {
        val shouldGoBack = chooserExpected
        chooserExpected = false
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(hideRunnable)
        removeOverlay()
        if (shouldGoBack) {
            // The accessibility overlay is not the native chooser activity.
            // Send Back after removing our window so Android dismisses the chooser too.
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayListContainer = null
        overlaySubtitleView = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
