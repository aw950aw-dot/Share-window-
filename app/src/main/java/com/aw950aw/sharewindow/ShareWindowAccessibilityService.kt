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
    private var overlayPreviewView: TextView? = null
    private var overlayQuickShareContainer: LinearLayout? = null
    private var overlayDimView: View? = null
    private var cachedShareAppIcons: List<ShareAppIcon>? = null
    private var lastCandidates: List<String> = emptyList()
    private var lastPreviewSummary: String = ""
    private var quickShareCandidate: String? = null
    private val accumulatedCandidates = LinkedHashSet<String>()
    private var scanRetryCount = 0
    private var showHiddenList = false
    private var suppressChooserUntil = 0L
    private data class ShareAppIcon(val label: String, val packageName: String, val drawable: Drawable)

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
            if (SystemClock.elapsedRealtime() < suppressChooserUntil) return
            chooserExpected = true
            scanRetryCount = 0
            accumulatedCandidates.clear()
            lastCandidates = emptyList()
            lastPreviewSummary = ""
            quickShareCandidate = null
            prefs.edit()
                .putString("last_class", eventClass)
                .putString("last_package", eventPackage)
                .putString("last_event_time", System.currentTimeMillis().toString())
                .apply()
            handler.removeCallbacks(hideRunnable)
            handler.removeCallbacks(scanRunnable)

            // Cover the native chooser immediately, then inspect the tree to reject upload pickers.
            val initialRoot = findChooserRoot()
            val initialVerdict = initialRoot?.let { classifyShareChooser(it) }
            if (initialVerdict != "file-picker" && prefs.getBoolean("overlay_enabled", true)) {
                showOverlay(emptyList(), loading = true)
            } else if (initialVerdict == "file-picker") {
                chooserExpected = false
                removeOverlay()
                return
            }

            handler.postDelayed(scanRunnable, 15)
            return
        }

        // Opening Android's notification shade changes the active window to SystemUI.
        // Keep the sharesheet overlay attached; when the shade closes it will be visible again.
        if (eventPackage == "com.android.systemui" ||
            eventClass.contains("NotificationShade", ignoreCase = true) ||
            eventClass.contains("StatusBar", ignoreCase = true)
        ) {
            // Cancel a pending delayed hide from an adjacent window event.
            handler.removeCallbacks(hideRunnable)
            return
        }

        if (eventPackage.isNotBlank() &&
            eventPackage != "android" &&
            eventPackage != packageName &&
            eventPackage != "com.android.systemui"
        ) {
            // Returning to the source app is the new chooser lifecycle boundary.
            suppressChooserUntil = 0L
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage.isNotBlank() &&
            eventPackage != "android" &&
            eventPackage != packageName &&
            eventPackage != "com.android.systemui"
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
            scheduleAnotherScan()
            return
        }

        if (classifyShareChooser(root) == "file-picker") {
            chooserExpected = false
            removeOverlay()
            prefs.edit().putString(
                "last_tree",
                "החלון זוהה כמסך בחירת קובץ/העלאה; חלונית השיתוף לא הוצגה."
            ).apply()
            return
        }

        val treeLines = ArrayList<String>()
        val candidates = LinkedHashSet<String>()
        val counter = intArrayOf(0)
        collectTree(root, 0, treeLines, candidates, counter)

        accumulatedCandidates.addAll(candidates)
        lastCandidates = accumulatedCandidates.toList()
        lastPreviewSummary = findSharedContentSummary(root, lastCandidates)
        quickShareCandidate = lastCandidates.firstOrNull { isQuickShareLabel(it) }

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

        // Android populates some OEM chooser targets after the first frame.
        // Keep collecting incremental rows for a short period instead of freezing a partial list.
        if (lastCandidates.size < 10) scheduleAnotherScan()
    }

    private fun scheduleAnotherScan() {
        if (!chooserExpected || scanRetryCount >= 7) return
        val delays = longArrayOf(70, 120, 180, 260, 380, 550, 750)
        val delay = delays[scanRetryCount.coerceIn(0, delays.lastIndex)]
        scanRetryCount++
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delay)
    }

    private fun isQuickShareLabel(label: String): Boolean {
        val value = label.lowercase(java.util.Locale.ROOT)
        return value.contains("quick share") ||
            value.contains("nearby share") ||
            value.contains("שיתוף מהיר") ||
            value.contains("שיתוף בקרבת מקום")
    }

    private fun findSharedContentSummary(
        root: AccessibilityNodeInfo,
        targetLabels: List<String>
    ): String {
        val candidates = ArrayList<Pair<Int, String>>()
        val screenHeight = resources.displayMetrics.heightPixels
        val targetSet = targetLabels.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 700 || depth > 28) return
            visited++
            if (node.isVisibleToUser) {
                val raw = node.text?.toString()?.trim().orEmpty()
                    .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
                val value = raw.replace("\\n", " ").trim()
                val lower = value.lowercase(java.util.Locale.ROOT)
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val generic = listOf(
                    "share", "sharing", "share with", "send to", "quick share",
                    "nearby share", "שיתוף", "שתף", "שיתוף מהיר", "שליחה", "שלח אל"
                )
                if (value.length in 2..140 &&
                    lower !in targetSet &&
                    generic.none { lower == it } &&
                    !hasClickableAncestor(node) &&
                    bounds.top < (screenHeight * 0.68f).toInt()
                ) {
                    candidates.add(bounds.top to value)
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return candidates.sortedBy { it.first }.firstOrNull()?.second.orEmpty()
    }

    private fun findNativeSheetBounds(root: AccessibilityNodeInfo): Rect {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        var best: Rect? = null
        var bestScore = Double.MAX_VALUE
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 900 || depth > 30) return
            visited++
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val widthRatio = rect.width().toFloat() / screenWidth.coerceAtLeast(1)
                val heightRatio = rect.height().toFloat() / screenHeight.coerceAtLeast(1)
                if (widthRatio >= 0.78f &&
                    heightRatio in 0.22f..0.88f &&
                    rect.top >= (screenHeight * 0.10f).toInt() &&
                    rect.bottom >= (screenHeight * 0.58f).toInt() &&
                    rect.bottom <= screenHeight + dp(30)
                ) {
                    val score = kotlin.math.abs(heightRatio - 0.48f) +
                        kotlin.math.abs(widthRatio - 1.0f) * 0.5 +
                        kotlin.math.abs(rect.bottom - screenHeight) / screenHeight.toDouble() * 0.25
                    if (score < bestScore) {
                        bestScore = score
                        best = Rect(rect)
                    }
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)

        return best ?: Rect(
            0,
            (screenHeight * 0.38f).toInt(),
            screenWidth,
            screenHeight
        )
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
        if (counter[0] >= 1400 || depth > 30) return
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
            if (counter[0] >= 1400) break
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 16) {
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

        var screen = overlayView as? FrameLayout
        var list = overlayListContainer
        if (screen == null || list == null) {
            screen = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
            }

            // The dim layer is limited to the native chooser's actual bounds.
            val dim = View(this).apply {
                setBackgroundColor(Color.argb(150, 0, 0, 0))
                isClickable = false
            }
            screen.addView(dim)
            overlayDimView = dim
            updateDimLayerBounds(screen, findChooserRoot())

            val sheetHeight = (resources.displayMetrics.heightPixels - dp(12)).coerceAtLeast(dp(420))
            val peekHeight = (resources.displayMetrics.heightPixels * 0.68f).toInt().coerceAtMost(sheetHeight)
            val sheet = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(17), dp(7), dp(17), dp(15))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(249, 250, 253))
                    cornerRadii = floatArrayOf(
                        dp(28).toFloat(), dp(28).toFloat(),
                        dp(28).toFloat(), dp(28).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                }
                elevation = dp(18).toFloat()
            }

            val dragArea = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                isClickable = true
            }
            val handle = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(175, 180, 190))
                    cornerRadius = dp(4).toFloat()
                }
                contentDescription = "גרירת חלונית השיתוף"
            }
            dragArea.addView(handle, LinearLayout.LayoutParams(dp(38), dp(5)))
            sheet.addView(dragArea, LinearLayout.LayoutParams(-1, dp(24)))

            // Preview header: display the shared filename/description when Android exposes it.
            val preview = TextView(this).apply {
                textSize = 15f
                setTextColor(Color.rgb(42, 49, 63))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                setPadding(dp(3), dp(3), dp(3), dp(9))
            }
            overlayPreviewView = preview
            sheet.addView(preview)

            // The Quick Share destination is featured above the regular app grid.
            val quickRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(2), 0, dp(10))
            }
            overlayQuickShareContainer = quickRow
            sheet.addView(quickRow)

            val divider = View(this).apply {
                setBackgroundColor(Color.rgb(231, 234, 240))
            }
            sheet.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

            val subtitle = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.rgb(100, 108, 122))
                setPadding(dp(2), dp(9), dp(2), dp(5))
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
                        sheet.translationY = (startTranslation + dy).coerceIn(0f, sheetHeight.toFloat())
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        val dy = event.rawY - downY
                        val movedDownFar = sheet.translationY >= (sheetHeight * 0.78f)
                        when {
                            movedDownFar -> closeChooser()
                            dy < -dp(65) ->
                                sheet.animate().translationY(0f).setDuration(180).start()
                            dy > dp(65) &&
                                sheet.translationY > (sheetHeight - peekHeight + dp(100)) ->
                                closeChooser()
                            else -> {
                                val collapsed = (sheetHeight - peekHeight).toFloat()
                                val target = if (sheet.translationY < collapsed / 2f) 0f else collapsed
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
                overlayPreviewView = null
                overlayQuickShareContainer = null
                overlayDimView = null
                return
            }
        }

        updateDimLayerBounds(screen, findChooserRoot())
        renderOverlayContents(candidates, loading, unavailable)
    }

    private fun updateDimLayerBounds(screen: FrameLayout, root: AccessibilityNodeInfo?) {
        val dim = overlayDimView ?: return
        val bounds = root?.let { findNativeSheetBounds(it) } ?: Rect(
            0,
            (resources.displayMetrics.heightPixels * 0.38f).toInt(),
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val params = FrameLayout.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        ).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
            gravity = Gravity.TOP or Gravity.START
        }
        dim.layoutParams = params
        dim.bringToFront()
        // Put the sheet back above the dim region.
        if (screen.childCount > 1) screen.getChildAt(screen.childCount - 1).bringToFront()
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

        val previewText = lastPreviewSummary.ifBlank {
            if (loading) "מכין תצוגה מקדימה של התוכן המשותף…" else "התוכן שבחרת לשתף"
        }
        overlayPreviewView?.text = previewText

        val quickLabel = quickShareCandidate
        val quickRow = overlayQuickShareContainer
        quickRow?.removeAllViews()
        if (!quickLabel.isNullOrBlank()) {
            quickRow?.visibility = View.VISIBLE
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(239, 244, 255))
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.rgb(218, 228, 250))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { clickOriginalTarget(quickLabel) }
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    findShareAppIcon(quickLabel) ?: getDrawable(android.R.drawable.sym_def_app_icon)
                )
            }
            pill.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            val label = TextView(this).apply {
                text = "Quick Share"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(32, 55, 96))
                setPadding(dp(10), 0, dp(5), 0)
            }
            pill.addView(label)
            quickRow?.addView(pill, LinearLayout.LayoutParams(-1, dp(60)))
        } else {
            // No explicit Quick Share entry was exposed by this device's chooser tree.
            // Do not create a fake, non-working action.
            quickRow?.visibility = View.GONE
        }

        overlaySubtitleView?.text = when {
            loading -> " "
            filterEnabled -> "יישומים"
            else -> "שיתוף באמצעות"
        }

        list.removeAllViews()
        val appCandidates = visibleCandidates.filterNot { isQuickShareLabel(it) }
        if (appCandidates.isEmpty()) {
            val empty = TextView(this).apply {
                text = when {
                    loading -> "טוען יישומים זמינים לשיתוף…"
                    unavailable -> "Android עדיין לא חשף את יעדי השיתוף."
                    else -> "לא נמצאו יעדים נוספים בחלון השיתוף."
                }
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(10), dp(22), dp(10), dp(22))
                gravity = Gravity.CENTER
            }
            list.addView(empty)
            return
        }

        var row: LinearLayout? = null
        appCandidates.forEachIndexed { index, label ->
            if (index % 4 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.TOP
                }
                list.addView(row, LinearLayout.LayoutParams(-1, dp(116)))
            }

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener { clickOriginalTarget(label) }
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(findShareAppIcon(label) ?: getDrawable(android.R.drawable.sym_def_app_icon))
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(237, 240, 247))
                }
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

            val caption = TextView(this).apply {
                text = label
                textSize = 11f
                maxLines = 2
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(35, 43, 58))
                setPadding(0, dp(4), 0, 0)
            }
            tile.addView(caption, LinearLayout.LayoutParams(-1, dp(36)))
            row?.addView(tile, LinearLayout.LayoutParams(0, dp(108), 1f))
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
        val mimeTypes = listOf(
            "*/*", "text/plain", "image/*", "image/jpeg", "image/png",
            "video/*", "video/mp4", "audio/*", "application/pdf",
            "application/zip"
        )
        val actions = listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        actions.forEach { action ->
            mimeTypes.forEach { mime ->
                try {
                    val intent = Intent(action).apply { type = mime }
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
                                            byPackage[packageName] =
                                                ShareAppIcon(appLabel, packageName, icon)
                                        }
                                    }
                                }
                            }
                        }
                } catch (_: Exception) {
                }
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
            while (current != null && steps <= 16) {
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
        suppressChooserUntil = SystemClock.elapsedRealtime() + 1800L
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
        overlayPreviewView = null
        overlayQuickShareContainer = null
        overlayDimView = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
