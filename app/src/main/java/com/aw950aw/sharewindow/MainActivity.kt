package com.aw950aw.sharewindow

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("smartshare", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            setBackgroundColor(Color.rgb(245, 247, 251))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(text("SmartShare", 28f, Color.rgb(28, 39, 60), true))
        content.addView(text("שכבת שיתוף מעל החלון המקורי", 15f, Color.DKGRAY, false).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val enabled = isAccessibilityEnabled()
        content.addView(section("מצב השירות"))
        content.addView(text(
            if (enabled) "שירות הנגישות פעיל" else "שירות הנגישות עדיין לא הופעל",
            16f,
            if (enabled) Color.rgb(30, 130, 75) else Color.rgb(180, 80, 35),
            true
        ))
        content.addView(text(
            "כדי שהשכבה תופיע, יש להפעיל את SmartShare בהגדרות הנגישות של Android.",
            13f, Color.DKGRAY, false
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        content.addView(button("פתיחת הגדרות נגישות") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        content.addView(section("התנהגות שכבת־העל"))
        val overlaySwitch = Switch(this).apply {
            text = "להציג את SmartShare מעל חלון השיתוף"
            textSize = 15f
            isChecked = prefs.getBoolean("overlay_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("overlay_enabled", checked).apply()
            }
        }
        content.addView(overlaySwitch)

        val filterSwitch = Switch(this).apply {
            text = "להציג רק יעדים שסימנתי"
            textSize = 15f
            isChecked = prefs.getBoolean("filter_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("filter_enabled", checked).apply()
            }
        }
        content.addView(filterSwitch)
        content.addView(text(
            "בגרסה זו הסינון הוא כללי. חלון הנגישות אינו מוסר לשירות את סוג ה־MIME המקורי, ולכן עדיין אין סינון אמין ונפרד לפי סוג קובץ.",
            13f, Color.DKGRAY, false
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        content.addView(section("יעדים שזוהו בחלון השיתוף האחרון"))
        content.addView(text(
            "פתח חלון שיתוף בכל אפליקציה, המתן להצגת SmartShare, ואז חזור לכאן ולחץ על רענון.",
            13f, Color.DKGRAY, false
        ).apply { setPadding(0, 0, 0, dp(8)) })

        val candidates = prefs.getStringSet("last_candidates", emptySet()).orEmpty().toList().sorted()
        val selected = prefs.getStringSet("selected_targets", emptySet()).orEmpty().toMutableSet()
        if (candidates.isEmpty()) {
            content.addView(text("עדיין לא זוהו יעדים.", 14f, Color.GRAY, false))
        } else {
            content.addView(text("סמן את האפליקציות שיישארו ברשימה המצומצמת:", 13f, Color.DKGRAY, true))
            candidates.forEach { label ->
                val check = CheckBox(this).apply {
                    text = label
                    textSize = 15f
                    isChecked = selected.contains(label)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(label) else selected.remove(label)
                        prefs.edit().putStringSet("selected_targets", selected.toSet()).apply()
                    }
                }
                content.addView(check)
            }
        }

        content.addView(section("אבחון אחרון"))
        val eventClass = prefs.getString("last_class", "—") ?: "—"
        val eventPackage = prefs.getString("last_package", "—") ?: "—"
        val eventTime = prefs.getString("last_event_time", "—") ?: "—"
        val summary = "מחלקה: $eventClass\nחבילה: $eventPackage\nזמן: $eventTime"
        content.addView(text(summary, 12f, Color.DKGRAY, false).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
        })
        val tree = prefs.getString("last_tree", "אין עדיין נתוני עץ נגישות.") ?: "אין נתונים."
        content.addView(text(tree, 11f, Color.rgb(55, 65, 80), false).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(10))
        })

        page.addView(button("רענון האבחון") { render() })
        page.addView(button("ניקוי נתוני האבחון") {
            prefs.edit()
                .remove("last_candidates")
                .remove("last_tree")
                .remove("last_class")
                .remove("last_package")
                .remove("last_event_time")
                .apply()
            render()
        })
        setContentView(page)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val target = ComponentName(this, ShareWindowAccessibilityService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(target, ignoreCase = true) }
    }

    private fun section(value: String): TextView =
        text(value, 17f, Color.rgb(35, 54, 88), true).apply {
            setPadding(0, dp(18), 0, dp(8))
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
