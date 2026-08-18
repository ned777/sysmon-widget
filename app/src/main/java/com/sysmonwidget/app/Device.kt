package com.sysmonwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A "data class" is a special kind of Kotlin class whose whole job is to hold data.
 * Kotlin automatically writes the boring boilerplate for us (equals(), toString(),
 * copy(), etc.) just because we put the word "data" in front of "class".
 *
 * This one Device represents one computer we're monitoring:
 *   - id:      a random, unique string so we can tell two devices apart even if
 *              they somehow have the exact same name and address.
 *   - name:    the human-friendly label the user typed in, e.g. "Home PC".
 *   - address: where to reach the little Python server on that computer,
 *              e.g. "192.168.1.50:8765" (IP address, colon, port number).
 */
data class Device(val id: String, val name: String, val address: String)

/**
 * "object" in Kotlin creates a singleton: there is only ever ONE DeviceStore in the
 * whole app, and you never type "DeviceStore()" to make one — you just call
 * DeviceStore.loadDevices(...) directly. It's a convenient place to group together
 * functions that don't need their own separate instance, kind of like a toolbox.
 *
 * DeviceStore's whole job is reading and writing the list of devices to disk, using
 * Android's SharedPreferences — the simplest built-in way to save small bits of data
 * (like settings) that survive after the app closes or the phone restarts.
 * SharedPreferences stores everything as text key/value pairs, so to save a whole
 * LIST of devices we convert it to a single JSON string first.
 */
object DeviceStore {
    // The name of the SharedPreferences "file" on disk. Multiple parts of the app
    // (the app screens AND the widget) read/write this same shared file, which is
    // how the widget finds out about devices you added in the app.
    private const val PREFS = "sysmon"

    // The single key inside that file where we store the JSON list of devices.
    private const val KEY_DEVICES = "devices"

    /**
     * Reads the saved devices back off disk and turns them into a List<Device>
     * that the rest of the app can use.
     *
     * @param context Android almost always asks you for a "Context" when you want
     *                to talk to the operating system (open a file, show a Toast,
     *                start another screen, etc). Think of it as "a handle to the
     *                currently running app" that these system APIs need.
     */
    fun loadDevices(context: Context): List<Device> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // getString returns null if we've never saved anything yet (e.g. this is
        // the very first time the app has ever run). The Elvis operator `?:` means
        // "if the thing on the left is null, use the thing on the right instead" —
        // here that just means "give back an empty list, there's nothing to load".
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()

        // JSONArray parses a string like `[{"id":"..."},{"id":"..."}]` into
        // something we can loop over.
        val arr = JSONArray(json)

        // `(0 until arr.length()).map { ... }` walks through every index in the
        // array (0, 1, 2, ...) and builds a brand-new Device object for each one.
        // `.map` is a very common Kotlin pattern: "for each item in this collection,
        // transform it into something else, and give me back the new list."
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(o.getString("id"), o.getString("name"), o.getString("address"))
        }
    }

    /**
     * Overwrites the ENTIRE saved device list with whatever list you pass in.
     * Every other function below (add/remove/update) works by loading the current
     * list, changing it in memory, and calling this to save the new version —
     * there's no "update just one row" API in SharedPreferences, so we always
     * rewrite the whole JSON blob.
     */
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
            .edit()                       // start a batch of changes
            .putString(KEY_DEVICES, arr.toString())
            .apply()                      // save it (apply() is "fire and forget",
                                           // it writes to disk in the background)
    }

    /**
     * Creates a new Device with a freshly generated random ID, adds it to whatever
     * devices already exist, and saves the combined list.
     *
     * UUID.randomUUID() generates a practically-guaranteed-unique ID
     * (something like "3fa85f64-5717-4562-b3fc-2c963f66afa6") — we use this instead
     * of, say, a counter starting at 1, so IDs never collide even across devices
     * that were added at different times.
     */
    fun addDevice(context: Context, name: String, address: String): Device {
        val device = Device(UUID.randomUUID().toString(), name, address)
        // `loadDevices(context) + device` builds a brand new list containing every
        // existing device PLUS the new one on the end. Kotlin lists are immutable
        // by default, so "adding" always means "make a new list", not "mutate the
        // old one in place".
        saveDevices(context, loadDevices(context) + device)
        return device
    }

    /**
     * Deletes the device with a matching id.
     * `filterNot { it.id == id }` reads as "keep every device EXCEPT the one whose
     * id matches" — `it` is Kotlin shorthand for "the current item in this lambda".
     */
    fun removeDevice(context: Context, id: String) {
        saveDevices(context, loadDevices(context).filterNot { it.id == id })
    }

    /**
     * Renames a device / changes its address, keeping the same id (so the widget,
     * which remembers a device by id, doesn't lose track of it).
     *
     * `.map { ... }` walks every device: if its id matches the one we're editing,
     * swap in a copy with the new name/address; otherwise leave it untouched.
     * `.copy(...)` is one of the free functions Kotlin generated for us because
     * Device is a `data class` — it clones the object but lets you override just
     * the fields you name.
     */
    fun updateDevice(context: Context, id: String, name: String, address: String) {
        saveDevices(
            context,
            loadDevices(context).map { if (it.id == id) it.copy(name = name, address = address) else it }
        )
    }

    /**
     * Looks up one device by id, or null if no device has that id.
     * `firstOrNull { ... }` scans the list and returns the first match, or null if
     * nothing matched — much safer than crashing when the id doesn't exist.
     */
    fun findDevice(context: Context, id: String): Device? =
        loadDevices(context).firstOrNull { it.id == id }
}
