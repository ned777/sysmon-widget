package com.sysmonwidget.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject

object StatsFormat {

    private val LABEL_COLOR = Color.parseColor("#00F0FF")
    private val VALUE_COLOR = Color.parseColor("#1DE9B6")

    const val KEY_RAM = "ram"
    const val KEY_STORAGE = "storage"
    const val KEY_CLAUDE_DAILY = "claude_daily"
    const val KEY_CLAUDE_WEEKLY = "claude_weekly"
    const val KEY_STATUS = "status"

    val ALL_KEYS = listOf(KEY_RAM, KEY_STORAGE, KEY_CLAUDE_DAILY, KEY_CLAUDE_WEEKLY, KEY_STATUS)

    fun boldLabel(label: String, value: String): SpannableString {
        val spannable = SpannableString(label + value)
        val labelEnd = label.length
        val totalEnd = spannable.length
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(LABEL_COLOR), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(VALUE_COLOR), labelEnd, totalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    fun buildStatRows(json: JSONObject, reachable: Boolean, enabledStats: Set<String>): List<CharSequence> {
        val rows = mutableListOf<CharSequence>()

        if (KEY_RAM in enabledStats) {
            val ram = json.getJSONObject("ram")
            val ramUsedGb = ram.getInt("used_mb") / 1024.0
            val ramTotalGb = ram.getInt("total_mb") / 1024.0
            rows.add(boldLabel("RAM: ", "%.0fGB out of %.0fGB".format(ramUsedGb, ramTotalGb)))
        }

        if (KEY_STORAGE in enabledStats) {
            val storage = json.getJSONObject("storage")
            rows.add(
                boldLabel(
                    "Storage: ",
                    "%dGB out of %dGB".format(storage.getInt("used_gb"), storage.getInt("total_gb"))
                )
            )
        }

        if (KEY_CLAUDE_DAILY in enabledStats) {
            val claude = json.getJSONObject("claude")
            rows.add(boldLabel("Claude (Daily): ", tokenSummary(claude.getJSONObject("tokens_daily"))))
        }

        if (KEY_CLAUDE_WEEKLY in enabledStats) {
            val claude = json.getJSONObject("claude")
            rows.add(boldLabel("Claude (Weekly): ", tokenSummary(claude.getJSONObject("tokens_weekly"))))
        }

        if (KEY_STATUS in enabledStats) {
            rows.add(boldLabel("Status: ", if (reachable) "Online" else "Offline"))
        }

        return rows
    }

    private fun tokenSummary(tokens: JSONObject): String {
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        return "${formatTokens(inTok)} in / ${formatTokens(outTok)} out"
    }

    fun formatTokens(count: Long): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        if (k < 1000) return "%.0fK".format(k)
        return "%.1fM".format(k / 1000.0)
    }
}
