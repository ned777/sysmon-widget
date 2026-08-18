package com.sysmonwidget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = DeviceAdapter(
            this,
            DeviceStore.loadDevices(this),
            showDelete = true,
            onDelete = { device ->
                DeviceStore.removeDevice(this, device.id)
                adapter.updateDevices(DeviceStore.loadDevices(this))
                SysMonWidgetProvider.refreshAllWidgets(this)
                Toast.makeText(this, "Removed ${device.name}", Toast.LENGTH_SHORT).show()
            },
            onEdit = { device -> showDeviceDialog(device) }
        )
        findViewById<ListView>(R.id.deviceListView).adapter = adapter

        findViewById<Button>(R.id.addDeviceButton).setOnClickListener { showDeviceDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        adapter.updateDevices(DeviceStore.loadDevices(this))
    }

    private fun showDeviceDialog(existing: Device?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.deviceNameInput)
        val addressInput = dialogView.findViewById<EditText>(R.id.deviceAddressInput)
        if (existing != null) {
            nameInput.setText(existing.name)
            addressInput.setText(existing.address)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.edit_device else R.string.add_device)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (name.isEmpty() || address.isEmpty()) {
                    Toast.makeText(this, R.string.device_fields_required, Toast.LENGTH_SHORT).show()
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
