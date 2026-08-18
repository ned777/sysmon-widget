package com.sysmonwidget.app

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import org.json.JSONObject

object StatsFormat {

    fun boldLabel(label: String, value: String): SpannableString {
        val spannable = SpannableString(label + value)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    fun buildStatRows(json: JSONObject, reachable: Boolean): List<CharSequence> {
        val ram = json.getJSONObject("ram")
        val ramUsedGb = ram.getInt("used_mb") / 1024.0
        val ramTotalGb = ram.getInt("total_mb") / 1024.0
        val ramRow = boldLabel("RAM: ", "%.0fGB out of %.0fGB".format(ramUsedGb, ramTotalGb))

        val storage = json.getJSONObject("storage")
        val storageRow = boldLabel(
            "Storage: ",
            "%dGB out of %dGB".format(storage.getInt("used_gb"), storage.getInt("total_gb"))
        )

        val tempValue = if (json.isNull("cpu_temp_c")) "N/A" else "%.0f°C".format(json.getDouble("cpu_temp_c"))
        val tempRow = boldLabel("Temp: ", tempValue)

        val claude = json.getJSONObject("claude")
        val dailyRow = boldLabel("Claude (Daily): ", tokenSummary(claude.getJSONObject("tokens_daily")))
        val weeklyRow = boldLabel("Claude (Weekly): ", tokenSummary(claude.getJSONObject("tokens_weekly")))

        val statusRow = boldLabel("Status: ", if (reachable) "Online" else "Offline")

        return listOf(ramRow, storageRow, tempRow, dailyRow, weeklyRow, statusRow)
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
