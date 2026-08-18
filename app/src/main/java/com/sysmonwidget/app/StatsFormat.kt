package com.sysmonwidget.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
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
    // "RAM: ") in neon magenta, and the value part (e.g. "8GB out of 16GB")
    // in off-white. Color.parseColor() reads a hex color string ("#RRGGBB")
    // the same way you'd write one in CSS, and turns it into the Int format
    // Android's drawing APIs expect internally. These match @color/retro_magenta
    // and @color/retro_white in colors.xml — kept as literal hex here (rather
    // than a resource lookup) because SpannableString styling happens in plain
    // Kotlin code with no Context/resources readily at hand at this point.
    private val LABEL_COLOR = Color.parseColor("#FF4FA3")
    private val VALUE_COLOR = Color.parseColor("#F2F2FF")

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
        if (KEY_RAM in enabledStats) rows.add(ramRow(json.getJSONObject("ram")))
        if (KEY_STORAGE in enabledStats) rows.add(storageRow(json.getJSONObject("storage")))

        if (KEY_CLAUDE_DAILY in enabledStats) {
            val claude = json.getJSONObject("claude")
            claudeRow("Claude (Daily): ", claude.getJSONObject("tokens_daily"))?.let { rows.add(it) }
        }

        if (KEY_CLAUDE_WEEKLY in enabledStats) {
            val claude = json.getJSONObject("claude")
            claudeRow("Claude (Weekly): ", claude.getJSONObject("tokens_weekly"))?.let { rows.add(it) }
        }

        if (KEY_STATUS in enabledStats) {
            rows.add(boldLabel("Status: ", if (reachable) "Online" else "Offline"))
        }

        return rows
    }

    /**
     * Builds EVERY stat row a device's JSON actually supports — used by the app's
     * own device list, which (unlike a widget) always shows everything rather
     * than letting the user pick a subset. Unlike buildStatRows() above, this
     * never assumes a key is present just because it was "enabled" — it checks
     * `json.has(...)` itself and quietly skips a row if the data isn't there, so
     * a device that doesn't run Claude Code (and therefore never reports a
     * "claude" section) simply shows RAM/Storage/Status with no Claude rows,
     * instead of crashing or showing a broken row.
     */
    fun buildAllStatRows(json: JSONObject, reachable: Boolean): List<CharSequence> {
        val rows = mutableListOf<CharSequence>()

        if (json.has("ram")) rows.add(ramRow(json.getJSONObject("ram")))
        if (json.has("storage")) rows.add(storageRow(json.getJSONObject("storage")))

        if (json.has("claude")) {
            val claude = json.getJSONObject("claude")
            if (claude.has("tokens_daily")) {
                claudeRow("Claude (Daily): ", claude.getJSONObject("tokens_daily"))?.let { rows.add(it) }
            }
            if (claude.has("tokens_weekly")) {
                claudeRow("Claude (Weekly): ", claude.getJSONObject("tokens_weekly"))?.let { rows.add(it) }
            }
        }

        rows.add(boldLabel("Status: ", if (reachable) "Online" else "Offline"))
        return rows
    }

    /**
     * Combines several rows (each its own separately-styled SpannableString)
     * into ONE piece of text with a line break between each — for a widget row
     * this isn't needed (every row gets its own separate TextView), but the
     * app's device list shows a whole device's stats inside a single TextView,
     * so they need to be one CharSequence. SpannableStringBuilder is like
     * SpannableString but mutable/appendable, and appending a CharSequence that
     * already carries color/bold spans preserves those spans automatically —
     * this is what keeps each row's own coloring intact after combining.
     */
    fun joinRows(rows: List<CharSequence>): CharSequence {
        val builder = SpannableStringBuilder()
        rows.forEachIndexed { index, row ->
            if (index > 0) builder.append("\n")
            builder.append(row)
        }
        return builder
    }

    private fun ramRow(ram: JSONObject): SpannableString =
        boldLabel("RAM: ", memoryRange(ram.getInt("used_mb"), ram.getInt("total_mb")))

    /**
     * Picks the biggest unit (GB, then MB, then KB) that still shows the USED
     * amount as a non-zero whole number, and shows BOTH numbers in that same
     * unit. Without this, a lightly-loaded device (say, 150MB used out of
     * 4096MB total) would round down to a confusing "0GB out of 4GB" — this
     * steps down a tier at a time until the used side actually reads as
     * something. Falling all the way through to KB is extremely unlikely in
     * practice (it would mean under 1MB used, which real systems don't do),
     * but handles it cleanly rather than ever showing a bare "0".
     */
    private fun memoryRange(usedMb: Int, totalMb: Int): String {
        val usedGb = Math.round(usedMb / 1024.0)
        if (usedGb > 0) {
            return "%dGB out of %dGB".format(usedGb, Math.round(totalMb / 1024.0))
        }
        if (usedMb > 0) {
            return "${usedMb}MB out of ${totalMb}MB"
        }
        return "${usedMb * 1024}KB out of ${totalMb * 1024}KB"
    }

    private fun storageRow(storage: JSONObject): SpannableString =
        boldLabel(
            "Storage: ",
            "%dGB out of %dGB".format(storage.getInt("used_gb"), storage.getInt("total_gb"))
        )

    /**
     * Builds one Claude usage row ("Claude (Daily): 1.2M in / 340K out") — or
     * returns null to mean "don't show this row at all" when there's simply
     * nothing to report. The agent ALWAYS includes a "claude" section with
     * this same shape, even on a device that's never run Claude Code — it
     * just comes back as all zeros rather than being left out entirely. So
     * checking `json.has("claude")` alone (as buildAllStatRows briefly did)
     * never actually catches that case; checking whether the numbers
     * themselves are zero is what actually hides the row.
     */
    private fun claudeRow(label: String, tokens: JSONObject): CharSequence? {
        // Cache-read tokens are billed like regular input tokens, so we fold them
        // into the "in" total rather than showing a confusing third number.
        // optLong(key, default) is like getLong but returns a default (0) instead
        // of crashing if that key happens to be missing from the JSON.
        val inTok = tokens.getLong("input") + tokens.optLong("cache_read", 0)
        val outTok = tokens.getLong("output")
        if (inTok == 0L && outTok == 0L) return null
        return boldLabel(label, "${formatTokens(inTok)} in / ${formatTokens(outTok)} out")
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
