package com.sysmonwidget.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class DeviceAdapter(
    private val context: Context,
    private var devices: List<Device>,
    private val showDelete: Boolean,
    private val onDelete: ((Device) -> Unit)? = null,
    private val onEdit: ((Device) -> Unit)? = null
) : BaseAdapter() {

    fun updateDevices(newDevices: List<Device>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun getCount(): Int = devices.size
    override fun getItem(position: Int): Device = devices[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_item_device, parent, false)
        val device = devices[position]
        view.findViewById<TextView>(R.id.deviceNameText).text = device.name
        view.findViewById<TextView>(R.id.deviceAddressText).text = device.address

        val deleteButton = view.findViewById<View>(R.id.deleteButton)
        if (showDelete) {
            deleteButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener { onDelete?.invoke(device) }
        } else {
            deleteButton.visibility = View.GONE
        }

        val editButton = view.findViewById<View>(R.id.editButton)
        if (onEdit != null) {
            editButton.visibility = View.VISIBLE
            editButton.setOnClickListener { onEdit.invoke(device) }
        } else {
            editButton.visibility = View.GONE
        }
        return view
    }
}
