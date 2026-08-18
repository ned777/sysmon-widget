package com.sysmonwidget.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * A ListView on Android doesn't know how to draw a `Device` — it only knows how to
 * ask an "Adapter" for a View for each row. This class is that adapter: it's the
 * bridge between our list of Device data objects and the actual rows drawn on
 * screen. The same adapter (and the same row layout, list_item_device.xml) is
 * reused in two places: the main device-management screen, and the widget's
 * "pick a device" step, which is why several options (showDelete, onEdit) are
 * switchable rather than hard-coded.
 *
 * @param context      needed to "inflate" (turn XML into real View objects).
 * @param devices      the list of devices to show, one row each.
 * @param showDelete   if true, each row gets a visible ✕ button.
 * @param onDelete     called with the tapped Device when its ✕ is pressed.
 * @param onEdit       called with the tapped Device when its ✎ is pressed.
 *                     Left null (the default) on screens where editing doesn't make
 *                     sense — e.g. the widget's device-picker list only shows the
 *                     ✎ button when this is actually supplied.
 */
class DeviceAdapter(
    private val context: Context,
    private var devices: List<Device>,
    private val showDelete: Boolean,
    private val onDelete: ((Device) -> Unit)? = null,
    private val onEdit: ((Device) -> Unit)? = null
) : BaseAdapter() {

    /**
     * Swaps in a new list of devices (e.g. after adding/removing one) and tells the
     * ListView to redraw itself. notifyDataSetChanged() is the signal Android's
     * list system uses to know "the data underneath you changed, please refresh".
     */
    fun updateDevices(newDevices: List<Device>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    // These three are required by BaseAdapter — the ListView calls them to find out
    // how many rows to draw, and how to look up the data/id behind a given row.
    override fun getCount(): Int = devices.size
    override fun getItem(position: Int): Device = devices[position]
    override fun getItemId(position: Int): Long = position.toLong()

    /**
     * This is the important one: Android calls getView() once per visible row,
     * asking "give me the View to draw at this position". ListViews are clever
     * about recycling rows that have scrolled off screen (passed in as
     * `convertView`) instead of creating brand new Views constantly, which is why
     * we reuse `convertView` when it's available instead of always inflating.
     */
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // "Inflating" a layout means turning the XML file (list_item_device.xml)
        // into real, live View objects in memory. If Android handed us a recycled
        // row via convertView, reuse it instead of inflating a brand new one —
        // that's a big performance win in long scrolling lists.
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_item_device, parent, false)

        val device = devices[position]

        // findViewById looks up a specific child view inside our row layout by its
        // android:id, so we can push data/behaviour into it.
        view.findViewById<TextView>(R.id.deviceNameText).text = device.name
        view.findViewById<TextView>(R.id.deviceAddressText).text = device.address

        val deleteButton = view.findViewById<View>(R.id.deleteButton)
        if (showDelete) {
            deleteButton.visibility = View.VISIBLE
            // setOnClickListener takes a lambda (a small inline function) that
            // runs whenever this exact button is tapped. `onDelete?.invoke(device)`
            // means "if a callback was actually supplied, call it with this row's
            // device" — the `?.` is Kotlin's null-safe call, so if onDelete is null
            // this line just quietly does nothing instead of crashing.
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
