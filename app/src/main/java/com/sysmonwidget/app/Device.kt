package com.sysmonwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Device(val id: String, val name: String, val address: String)

object DeviceStore {
    private const val PREFS = "sysmon"
    private const val KEY_DEVICES = "devices"

    fun loadDevices(context: Context): List<Device> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(o.getString("id"), o.getString("name"), o.getString("address"))
        }
    }

    fun saveDevices(context: Context, devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("address", d.address)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICES, arr.toString())
            .apply()
    }

    fun addDevice(context: Context, name: String, address: String): Device {
        val device = Device(UUID.randomUUID().toString(), name, address)
        saveDevices(context, loadDevices(context) + device)
        return device
    }

    fun removeDevice(context: Context, id: String) {
        saveDevices(context, loadDevices(context).filterNot { it.id == id })
    }

    fun updateDevice(context: Context, id: String, name: String, address: String) {
        saveDevices(
            context,
            loadDevices(context).map { if (it.id == id) it.copy(name = name, address = address) else it }
        )
    }

    fun findDevice(context: Context, id: String): Device? =
        loadDevices(context).firstOrNull { it.id == id }
}
