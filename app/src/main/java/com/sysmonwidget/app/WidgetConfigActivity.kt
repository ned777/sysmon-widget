package com.sysmonwidget.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * When you drag a NEW SysMon widget onto your home screen, Android doesn't just
 * plop it down blindly — because we declared `android:configure` in
 * sysmon_widget_info.xml, Android instead launches this Activity first and waits
 * for it to finish, so we can ask "which device should THIS widget watch, and
 * which stats do you want it to show?" before the widget is actually created.
 *
 * It's a two-step wizard packed into one screen: two whole LinearLayouts
 * (deviceStepGroup and statsStepGroup) are both present in activity_widget_config.xml
 * the entire time, and we just toggle their visibility to move between "steps"
 * rather than launching a second Activity.
 */
class WidgetConfigActivity : AppCompatActivity() {

    // Every home-screen widget instance has its own numeric id, handed to us via
    // the Intent that launched this configuration screen. We start with
    // INVALID_APPWIDGET_ID as a safe placeholder in case something goes wrong
    // before we read the real one.
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Very important Android convention for widget config screens: default
        // the result to CANCELED immediately. If the user backs out of this
        // screen (presses Back, swipes away, etc.) without us ever calling
        // setResult(RESULT_OK, ...) ourselves, Android will see this CANCELED
        // result and correctly throw away the half-configured widget instead of
        // adding a broken one to the home screen.
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        // Pull the widget id for THIS specific widget instance out of the launch
        // Intent's extras — Android puts it there automatically when it starts
        // configuration Activities.
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // We were somehow launched without a valid widget id — nothing
            // sensible to configure, so just close immediately (result stays
            // CANCELED from above).
            finish()
            return
        }

        val devices = DeviceStore.loadDevices(this)
        val listView = findViewById<ListView>(R.id.configDeviceListView)

        if (devices.isEmpty()) {
            // No devices registered yet at all — rather than showing an empty
            // list, guide the user to go add one in the main app first.
            listView.visibility = View.GONE
            findViewById<TextView>(R.id.configEmptyText).visibility = View.VISIBLE
            findViewById<Button>(R.id.configAddDeviceButton).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)) }
            }
            return
        }

        // Reuse the exact same DeviceAdapter/list_item_device.xml the main app
        // screen uses — but with showDelete = false and no onEdit callback, since
        // this is just a picker, not a management screen.
        val adapter = DeviceAdapter(this, devices, showDelete = false)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val device = adapter.getItem(position)
            // Remember which device THIS widget id should watch. This is the
            // same SharedPreferences key SysMonWidgetProvider.resolveDevice()
            // reads later to know what to fetch stats for.
            getSharedPreferences("sysmon", MODE_PRIVATE)
                .edit()
                .putString("widget_${appWidgetId}_device_id", device.id)
                .apply()
            showStatsStep()
        }
    }

    /**
     * Moves the wizard to "step 2": hide the device picker, reveal the checkbox
     * list of which stats to show, and wire up the final "Done" button.
     */
    private fun showStatsStep() {
        findViewById<View>(R.id.deviceStepGroup).visibility = View.GONE
        findViewById<View>(R.id.statsStepGroup).visibility = View.VISIBLE

        findViewById<Button>(R.id.doneConfigButton).setOnClickListener {
            // Build up a plain list of "which stat keys got checked", then save
            // them as one comma-separated string — StatsRemoteViewsFactory reads
            // this back later by splitting on "," (see SysMonRemoteViewsService.kt).
            val enabled = mutableListOf<String>()
            if (findViewById<CheckBox>(R.id.checkRam).isChecked) enabled.add(StatsFormat.KEY_RAM)
            if (findViewById<CheckBox>(R.id.checkStorage).isChecked) enabled.add(StatsFormat.KEY_STORAGE)
            if (findViewById<CheckBox>(R.id.checkClaudeDaily).isChecked) enabled.add(StatsFormat.KEY_CLAUDE_DAILY)
            if (findViewById<CheckBox>(R.id.checkClaudeWeekly).isChecked) enabled.add(StatsFormat.KEY_CLAUDE_WEEKLY)
            if (findViewById<CheckBox>(R.id.checkStatus).isChecked) enabled.add(StatsFormat.KEY_STATUS)

            getSharedPreferences("sysmon", MODE_PRIVATE)
                .edit()
                .putString("widget_${appWidgetId}_stats", enabled.joinToString(","))
                .apply()

            // This is the crucial final step of any widget configuration screen:
            // package the widget id back up and call setResult(RESULT_OK, ...).
            // ONLY after this does Android actually go ahead and place the
            // widget on the home screen and call onUpdate() for it. Without this
            // call, the widget placement would be silently cancelled no matter
            // what we did above.
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}
