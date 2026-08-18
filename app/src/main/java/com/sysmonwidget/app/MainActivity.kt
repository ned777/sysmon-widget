package com.sysmonwidget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * An "Activity" is Android's word for one full screen of your app that the user can
 * see and interact with. Every app has at least one; this is ours — the screen you
 * see when you tap the app's icon. Its job here is to show the list of devices
 * you've registered, and let you add, edit, or remove them.
 *
 * It extends (inherits from) AppCompatActivity, which is a base class the Android
 * support library gives us — it already knows how to draw a title bar, handle
 * lifecycle events (what happens when the screen opens/closes/rotates), etc., so we
 * only have to write the parts that make THIS screen special.
 */
class MainActivity : AppCompatActivity() {

    // `lateinit var` means "trust me, I will definitely set this before anyone
    // tries to read it — just don't make me give it a placeholder value right
    // now". We can't build the adapter until onCreate() runs, so we declare the
    // variable here but only actually create it a few lines below.
    private lateinit var adapter: DeviceAdapter

    // The latest known stats text for each device, keyed by device id. Starts
    // empty for every device; refreshDeviceStats() fills entries in one at a
    // time as each device's network fetch finishes, and DeviceAdapter reads
    // from this map (via the statsFor lambda passed to it below) every time it
    // draws a row — a device with no entry yet just shows a loading placeholder.
    private val deviceStats = mutableMapOf<String, CharSequence>()

    /**
     * onCreate() is called once by Android when this screen is first being built,
     * before the user ever sees it. This is where we set up everything the screen
     * needs: which layout to show, and how its buttons/lists should behave.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tells Android "draw the UI described in res/layout/activity_main.xml
        // onto this screen". Everything below this line assumes those views
        // (deviceListView, addDeviceButton, ...) now exist and can be found.
        setContentView(R.layout.activity_main)

        // Build the adapter that will turn our saved Device list into rows on
        // screen. We pass three little callback functions (lambdas) in as
        // arguments — DeviceAdapter doesn't know or care HOW deleting/editing
        // works, it just calls back into MainActivity when a button is tapped.
        adapter = DeviceAdapter(
            this,
            DeviceStore.loadDevices(this),
            showDelete = true,
            onDelete = { device ->
                DeviceStore.removeDevice(this, device.id)
                adapter.updateDevices(DeviceStore.loadDevices(this))
                // Any home-screen widgets pointing at devices need to know the
                // device list changed too, in case the deleted device was in use.
                SysMonWidgetProvider.refreshAllWidgets(this)
                Toast.makeText(this, "Removed ${device.name}", Toast.LENGTH_SHORT).show()
            },
            onEdit = { device -> showDeviceDialog(device) },
            statsFor = { device -> deviceStats[device.id] }
        )
        // Hook the adapter up to the actual ListView from the layout so it starts
        // drawing rows.
        findViewById<ListView>(R.id.deviceListView).adapter = adapter

        // When "Add device" is tapped, open the same dialog used for editing, but
        // with `null` meaning "there's no existing device — start blank".
        findViewById<Button>(R.id.addDeviceButton).setOnClickListener { showDeviceDialog(null) }
    }

    /**
     * onResume() runs every time this screen becomes visible again — including
     * the very first time, but ALSO when you come back to it (e.g. after backing
     * out of the add/edit dialog, or switching apps and returning). We reload the
     * device list here so the screen always reflects the latest saved data, even
     * if it changed somewhere else in the meantime.
     */
    override fun onResume() {
        super.onResume()
        adapter.updateDevices(DeviceStore.loadDevices(this))
        refreshDeviceStats()
    }

    /**
     * Kicks off a live stats fetch for every registered device, in the
     * background, and updates the list as each one finishes. Unlike the
     * widget (which only fetches for devices it's actually configured to
     * watch, and caches the result to disk), the app fetches for every device
     * every time this screen becomes visible, since its whole purpose here is
     * showing "everything, right now" — see StatsFormat.buildAllStatRows().
     */
    private fun refreshDeviceStats() {
        DeviceStore.loadDevices(this).forEach { device ->
            // Each device gets its own background Thread so a slow/offline
            // device doesn't hold up the others — StatsClient.fetchStats()
            // blocks for up to a few seconds, which would freeze the whole
            // screen if run directly on the main thread here.
            Thread {
                val json = StatsClient.fetchStats(device.address)
                val text = if (json != null) {
                    StatsFormat.joinRows(StatsFormat.buildAllStatRows(json, reachable = true))
                } else {
                    StatsFormat.boldLabel("Status: ", "Offline")
                }
                // Views can only be touched from the main/UI thread — runOnUiThread
                // hands this block back over to it before we update the map and
                // ask the adapter to redraw.
                runOnUiThread {
                    deviceStats[device.id] = text
                    adapter.notifyDataSetChanged()
                }
            }.start()
        }
    }

    /**
     * Shows the "add or edit a device" popup. One function handles both cases:
     *   - Pass `existing = null` to add a brand new device (fields start empty).
     *   - Pass an actual Device to edit it (fields start pre-filled, and saving
     *     updates that device in place instead of creating a new one).
     *
     * An AlertDialog is Android's built-in popup box: a title, some custom content
     * (our two text fields, inflated from dialog_add_device.xml), and buttons.
     */
    private fun showDeviceDialog(existing: Device?) {
        // Inflating with a `null` parent (the second argument) just means "build
        // these views floating in memory, they're not attached to a parent layout
        // yet" — AlertDialog.Builder will attach them itself via setView() below.
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.deviceNameInput)
        val addressInput = dialogView.findViewById<EditText>(R.id.deviceAddressInput)

        // If we're editing, pre-fill the text boxes with the device's current
        // values so the user only has to change what they want to change.
        if (existing != null) {
            nameInput.setText(existing.name)
            addressInput.setText(existing.address)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.edit_device else R.string.add_device)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                // The lambda passed to setPositiveButton takes two arguments (the
                // dialog itself, and which button index was pressed) that we don't
                // need here, so we name them both `_` — Kotlin's "I'm required to
                // accept this parameter, but I'm intentionally ignoring it".
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isEmpty() || address.isEmpty()) {
                    Toast.makeText(this, R.string.device_fields_required, Toast.LENGTH_SHORT).show()
                    // `return@setPositiveButton` exits just this lambda (not the
                    // whole surrounding function) — it's Kotlin's way of doing
                    // "return early" from an inline block, since a plain `return`
                    // would be ambiguous about which function it's returning from.
                    return@setPositiveButton
                }
                if (existing != null) {
                    DeviceStore.updateDevice(this, existing.id, name, address)
                    SysMonWidgetProvider.refreshAllWidgets(this)
                } else {
                    DeviceStore.addDevice(this, name, address)
                }
                adapter.updateDevices(DeviceStore.loadDevices(this))
            }
            // `null` here means "just use the default Cancel behaviour" (close the
            // dialog, do nothing) — we don't need any custom logic when cancelled.
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
