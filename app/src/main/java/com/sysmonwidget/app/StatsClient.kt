package com.sysmonwidget.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object StatsClient {
    fun fetchStats(serverAddress: String): JSONObject? {
        return try {
            val url = URL("http://$serverAddress/stats")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
