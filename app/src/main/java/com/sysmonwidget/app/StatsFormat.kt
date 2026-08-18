package com.sysmonwidget.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject

/**
 * StatsFormat turns the raw JSON that the Python agent sends (numbers like
 * "used_mb": 8192) into the actual text rows shown in the widget (like
 * "RAM: 8GB out of 16GB"), styled with colors and bold. Keeping all of this
 * formatting logic in one place means both the widget AND (if we ever add one)
 * a future settings screen could reuse the exact same "what does a RAM row look
 * like" logic without copy-pasting it.
 */
object StatsFormat {

    // These two colors are used to paint every stat row: the label part (e.g.
    // "RAM: ") in cyan, and the value part (e.g. "8GB out of 16GB") in teal.
    // Color.parseColor() reads a hex color string ("#RRGGBB") the same way you'd
    // write one in CSS, and turns it into the Int format Android's drawing APIs
    // expect internally.
    private val LABEL_COLOR = Color.parseColor("#00F0FF")
    private val VALUE_COLOR = Color.parseColor("#1DE9B6")

    // `const val` are compile-time constants — plain string labels we use as keys
    // for "which stats has the user chosen to show on this widget". Using named
    // constants like KEY_RAM instead of typing the raw string "ram" everywhere
    // means a typo becomes a compile error instead of a silent bug.
    const val KEY_RAM = "ram"
    const val KEY_STORAGE = "storage"
    const val KEY_CLAUDE_DAILY = "claude_daily"
    const val KEY_CLAUDE_WEEKLY = "claude_weekly"
    const val KEY_STATUS = "status"

    // The full list of every stat that exists, in the widget's default order —
    // used as a fallback when a widget hasn't customized which stats to show yet.
    val ALL_KEYS = listOf(KEY_RAM, KEY_STORAGE, KEY_CLAUDE_DAILY, KEY_CLAUDE_WEEKLY, KEY_STATUS)

    /**
     * Builds one styled row of text like "RAM: 8GB out of 16GB", where the label
     * ("RAM: ") is bold + cyan, and the value ("8GB out of 16GB") is teal.
     *
     * Plain Strings in Android can only be ONE color/style for their whole length,
     * so to make part of a string bold and another part a different color, we
     * need a SpannableString — think of it as "a string, plus a list of style
     * instructions that apply to specific character ranges within it".
     */
    fun boldLabel(label: String, value: String): SpannableString {
        // Glue label and value into one piece of text first...
        val spannable = SpannableString(label + value)
        val labelEnd = label.length
        val totalEnd = spannable.length

        // ...then paint styles onto specific ranges of it. setSpan(style, start,
        // end, flag) says "apply this style from character `start` up to (but not
        // including) character `end`". SPAN_EXCLUSIVE_EXCLUSIVE is the normal flag
        // to use — it means the style doesn't automatically expand if more text
        // gets inserted right at its edges (which can't happen here anyway, since
        // this string never changes after we build it).
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(LABEL_COLOR), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(VALUE_COLOR), labelEnd, totalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    /**
     * Turns the raw JSON blob from the agent into the actual list of text rows the
     * widget should display, in order, respecting which stats the user enabled
     * for this particular widget.
     *
     * @param json          parsed JSON from the agent, e.g. {"ram": {...}, ...}
     * @param reachable     whether the last request to the agent succeeded — used
     *                      for the Status row.
     * @param enabledStats  which of the KEY_* constants above this widget wants to
     *                      show (the user picks this in the widget setup screen).
     */
    fun buildStatRows(json: JSONObject, reachable: Boolean, enabledStats: Set<String>): List<CharSequence> {
        // mutableListOf makes a list we're allowed to add() to as we go — regular
        // Kotlin lists (like the `ALL_KEYS` list above) are read-only by default.
        val rows = mutableListOf<CharSequence>()

        // `in` here checks set membership: "is the string KEY_RAM present inside
        // the enabledStats set?" — if the user turned this stat off, we skip it
        // entirely and it just won't appear as a row.
        if (KEY_RAM in enabledStats) {
            val ram = json.getJSONObject("ram")
            // The agent reports RAM in megabytes; dividing by 1024.0 (a Double,
            // note the decimal point) converts it to gigabytes with fractional
            // precision, which "%.0f" below then rounds to a whole number to show.
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

    /**
     * Builds the "1.2M in / 340K out" part of a Claude usage row.
     * `private` means this helper is only usable inside StatsFormat itself — it's
     * an implementation detail other files never need to call directly.
     */
    private fun tokenSummary(tokens: JSONObject): String {
        // Cache-read tokens are billed like regular input tokens, so we fold them
        // into the "in" total rather than showing a confusing third number.
        // optLong(key, default) is like getLong but returns a default (0) instead
        // of crashing if that key happens to be missing from the JSON.
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        return "${formatTokens(inTok)} in / ${formatTokens(outTok)} out"
    }

    /**
     * Shrinks a raw token count into a compact human-readable string:
     * 500 -> "500", 45000 -> "45K", 2300000 -> "2.3M".
     */
    fun formatTokens(count: Long): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        if (k < 1000) return "%.0fK".format(k)
        return "%.1fM".format(k / 1000.0)
    }
}
