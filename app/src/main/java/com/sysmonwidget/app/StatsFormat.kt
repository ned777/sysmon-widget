package com.sysmonwidget.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One stat: a short [label] ("CPU"), a compact
 * [value] ("12%" / "1.2/15GB"), and — for the usage-style stats — a 0..100
 * [percent] that drives the widget's usage bar. [percent] is null
 * for stats with no natural "how full" (uptime, load, Claude tokens, status).
 * [detail] is extra text only the app's device cards show (e.g. "20 cores").
 */
data class StatRow(val label: String, val value: String, val percent: Int? = null, val detail: String? = null)

/**
 * StatsFormat turns the raw JSON that the Python agent sends (numbers like
 * "used_mb": 8192) into the actual text rows shown in the widget (like
 * "RAM 8/16GB" plus a usage bar), styled with colors and bold. Keeping all of this
 * formatting logic in one place means both the widget AND (if we ever add one)
 * a future settings screen could reuse the exact same "what does a RAM row look
 * like" logic without copy-pasting it.
 */
object StatsFormat {

    // Label (teal) / value (off-white) colors for the APP's text rows; they
    // match @color/retro_teal and @color/retro_white in colors.xml.
    private val LABEL_COLOR = Color.parseColor("#00E0B8")
    private val VALUE_COLOR = Color.parseColor("#F5F5FF")

    const val KEY_CPU = "cpu"
    const val KEY_RAM = "ram"
    const val KEY_STORAGE = "storage"
    const val KEY_TEMP = "temp"
    const val KEY_LOAD = "load"
    const val KEY_UPTIME = "uptime"
    const val KEY_CLAUDE_DAILY = "claude_daily"
    const val KEY_CLAUDE_WEEKLY = "claude_weekly"
    const val KEY_STATUS = "status"

    /** Every stat, in the order the widget shows them, top to bottom. */
    val ALL_KEYS = listOf(
        KEY_CPU, KEY_RAM, KEY_STORAGE, KEY_TEMP, KEY_LOAD, KEY_UPTIME,
        KEY_CLAUDE_DAILY, KEY_CLAUDE_WEEKLY, KEY_STATUS
    )

    /**
     * Stats added in pref version 2. Widgets configured before they existed
     * have a saved stats list without them — StatsRemoteViewsFactory switches
     * these on for such widgets so nobody has to re-add their widget.
     */
    val KEYS_ADDED_V2 = setOf(KEY_CPU, KEY_TEMP, KEY_LOAD, KEY_UPTIME)
    const val STATS_PREF_VERSION = 2

    /** Bold teal "Label: " followed by off-white value — used by the app's device cards. */
    fun boldLabel(label: String, value: String): SpannableString {
        val spannable = SpannableString(label + value)
        val labelEnd = label.length
        val totalEnd = spannable.length
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(LABEL_COLOR), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(VALUE_COLOR), labelEnd, totalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    /**
     * Which stats this widget instance shows, from what WidgetConfigActivity
     * saved. Widgets set up before CPU/Temp/Load/Uptime existed get those
     * switched on automatically (they never had the chance to pick them).
     */
    fun enabledStatsFor(prefs: android.content.SharedPreferences, appWidgetId: Int): Set<String> {
        val saved = prefs.getString("widget_${appWidgetId}_stats", null)
        if (saved.isNullOrBlank()) return ALL_KEYS.toSet()
        val keys = saved.split(",").toSet()
        val version = prefs.getInt("widget_${appWidgetId}_stats_version", 1)
        return if (version < STATS_PREF_VERSION) keys + KEYS_ADDED_V2 else keys
    }

    /** Every stat the device reports, as "Label: value" lines for the app's device cards. */
    fun buildAllStatRows(json: JSONObject, reachable: Boolean): List<CharSequence> =
        ALL_KEYS.mapNotNull { rowFor(it, json, reachable) }.map { row ->
            var value = if (row.percent != null && !row.value.contains("%")) "${row.value}  (${row.percent}%)" else row.value
            if (row.detail != null) value += " · ${row.detail}"
            boldLabel("${longLabel(row.label)}: ", value)
        }

    fun joinRows(rows: List<CharSequence>): CharSequence {
        val builder = SpannableStringBuilder()
        rows.forEachIndexed { index, row ->
            if (index > 0) builder.append("\n")
            builder.append(row)
        }
        return builder
    }

    private fun longLabel(short: String): String = when (short) {
        "Disk" -> "Storage"
        "Claude 1d" -> "Claude (Daily)"
        "Claude 7d" -> "Claude (Weekly)"
        else -> short
    }

    fun rowFor(key: String, json: JSONObject, reachable: Boolean): StatRow? = try {
        when (key) {
            KEY_CPU -> json.optJSONObject("cpu")?.let { cpu ->
                val pct = cpu.getDouble("percent")
                StatRow("CPU", "%.0f%%".format(pct), pct.toPercent(), detail = "${cpu.optInt("cores", 1)} cores")
            }
            KEY_RAM -> json.optJSONObject("ram")?.let { ram ->
                val used = ram.getInt("used_mb"); val total = ram.getInt("total_mb")
                StatRow("RAM", memoryRange(used, total), percentOf(used.toDouble(), total.toDouble()))
            }
            KEY_STORAGE -> json.optJSONObject("storage")?.let { st ->
                val used = st.getInt("used_gb"); val total = st.getInt("total_gb")
                val pct = if (st.has("percent")) st.getDouble("percent").toPercent() else percentOf(used.toDouble(), total.toDouble())
                StatRow("Disk", "$used/${total}GB", pct)
            }
            KEY_TEMP -> if (json.isNull("temp_c") || !json.has("temp_c")) null else {
                val c = json.getDouble("temp_c")
                // Bar scaled to 0..100°C so it fills/turns warn/hot at sensible CPU temps.
                StatRow("Temp", "%.0f°C".format(c), c.toPercent())
            }
            KEY_LOAD -> json.optJSONObject("cpu")?.optJSONArray("load")?.let { load ->
                StatRow("Load", "%.2f %.2f".format(load.getDouble(0), load.getDouble(1)))
            }
            KEY_UPTIME -> if (json.isNull("uptime_s") || !json.has("uptime_s")) null
                else StatRow("Uptime", formatUptime(json.getLong("uptime_s")))
            KEY_CLAUDE_DAILY -> json.optJSONObject("claude")?.optJSONObject("tokens_daily")?.let { claudeRow("Claude 1d", it) }
            KEY_CLAUDE_WEEKLY -> json.optJSONObject("claude")?.optJSONObject("tokens_weekly")?.let { claudeRow("Claude 7d", it) }
            KEY_STATUS -> StatRow("Status", if (reachable) "Online" else "Offline")
            else -> null
        }
    } catch (e: Exception) {
        null // a malformed/partial field from some agent version shouldn't hide every other stat
    }

    private fun Double.toPercent(): Int = Math.round(this).toInt().coerceIn(0, 100)

    private fun percentOf(used: Double, total: Double): Int? =
        if (total > 0) (used * 100 / total).toPercent() else null

    /** Compact "used/total" in GB, e.g. "1.2/15GB" or "0.3/3.7GB". */
    private fun memoryRange(usedMb: Int, totalMb: Int): String {
        fun gb(mb: Int): String {
            val g = mb / 1024.0
            return if (g < 10) "%.1f".format(g) else "%.0f".format(g)
        }
        // Always GB with at most one decimal ("0.3/3.7GB", "3.9/15GB") so the
        // value fits the widget's fixed-width value column.
        return "${gb(usedMb)}/${gb(totalMb)}GB"
    }

    private fun claudeRow(label: String, tokens: JSONObject): StatRow? {
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        if (inTok == 0L && outTok == 0L) return null
        return StatRow(label, "${formatTokens(inTok)}↓ ${formatTokens(outTok)}↑")
    }

    /** "3d 4h", "5h 12m", or "42m". */
    fun formatUptime(seconds: Long): String {
        val d = seconds / 86400; val h = (seconds % 86400) / 3600; val m = (seconds % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun timestampString(millis: Long): String =
        SimpleDateFormat("MMM d, HH:mm zzz", Locale.getDefault()).format(Date(millis))

    fun formatTokens(count: Long): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        if (k < 1000) return "%.0fK".format(k)
        return "%.1fM".format(k / 1000.0)
    }
}
