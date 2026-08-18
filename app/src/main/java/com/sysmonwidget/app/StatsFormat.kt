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
        val statusRow = boldLabel("Status: ", if (reachable) "Online" else "Offline")

        val ram = json.getJSONObject("ram")
        val ramUsedGb = ram.getInt("used_mb") / 1024.0
        val ramTotalGb = ram.getInt("total_mb") / 1024.0
        val ramRow = boldLabel("RAM: ", "%.0fGB out of %.0fGB".format(ramUsedGb, ramTotalGb))

        val storage = json.getJSONObject("storage")
        val storageRow = boldLabel(
            "Storage: ",
            "%dGB out of %dGB".format(storage.getInt("used_gb"), storage.getInt("total_gb"))
        )

        val net = json.getJSONObject("network")
        val netRow = boldLabel(
            "Network: ",
            "↓${formatRate(net.getLong("rx_bytes_per_sec"))} ↑${formatRate(net.getLong("tx_bytes_per_sec"))}"
        )

        val claude = json.getJSONObject("claude")
        val tokens = claude.getJSONObject("tokens_today")
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        val cost = claude.getDouble("est_cost_today_usd")
        val claudeRow = boldLabel(
            "Claude today: ",
            "%s in / %s out · ~$%.2f".format(formatTokens(inTok), formatTokens(outTok), cost)
        )

        return listOf(statusRow, ramRow, storageRow, netRow, claudeRow)
    }

    fun formatRate(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "${bytesPerSec}B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return "%.0fKB/s".format(kb)
        return "%.1fMB/s".format(kb / 1024.0)
    }

    fun formatTokens(count: Long): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        if (k < 1000) return "%.0fK".format(k)
        return "%.1fM".format(k / 1000.0)
    }
}
