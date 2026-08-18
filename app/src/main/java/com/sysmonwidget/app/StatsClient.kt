package com.sysmonwidget.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * StatsClient's only job is to make one HTTP request: "hey little Python server
 * running on this computer, what are your current stats?" and hand back the
 * answer as parsed JSON.
 *
 * It's an `object` (singleton) rather than a `class` because there's no state to
 * keep between calls — every call is a fresh, independent request, so there's
 * nothing to gain from creating separate instances of "a client".
 */
object StatsClient {

    /**
     * Talks to http://<serverAddress>/stats (e.g. "192.168.1.50:8765/stats") and
     * returns the parsed JSON body, or null if anything at all went wrong —
     * server offline, wrong address, request timed out, bad JSON, etc.
     *
     * Returning null-on-failure (instead of throwing an exception up to the
     * caller) keeps the calling code simple: SysMonWidgetProvider just checks
     * "did I get stats back or not?" and shows Online/Offline accordingly,
     * without needing a big try/catch of its own.
     */
    fun fetchStats(serverAddress: String): JSONObject? {
        return try {
            val url = URL("http://$serverAddress/stats")
            val connection = url.openConnection() as HttpURLConnection

            // Don't let a single dead/unreachable device hang the widget update
            // forever — give up after 3 seconds either waiting to connect, or
            // waiting for the response body to arrive.
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            try {
                // Anything other than HTTP 200 OK counts as failure for our
                // purposes — we don't need to handle redirects/auth/etc, this is
                // talking to our own tiny trusted server.
                if (connection.responseCode != 200) return null

                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body)
            } finally {
                // `finally` runs whether the block above succeeded or threw —
                // we always want to close the network connection when we're done
                // with it, to free up the underlying socket.
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Catches anything that went wrong along the way: no network, DNS/host
            // unreachable, connection refused (server not running), malformed
            // JSON, etc. We deliberately don't care WHICH error happened here —
            // from the widget's point of view they all mean the same thing:
            // "couldn't reach this device right now".
            null
        }
    }
}
