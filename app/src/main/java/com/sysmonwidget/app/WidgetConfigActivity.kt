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

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val devices = DeviceStore.loadDevices(this)
        val listView = findViewById<ListView>(R.id.configDeviceListView)

        if (devices.isEmpty()) {
            listView.visibility = View.GONE
            findViewById<TextView>(R.id.configEmptyText).visibility = View.VISIBLE
            findViewById<Button>(R.id.configAddDeviceButton).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)) }
            }
            return
        }

        val adapter = DeviceAdapter(this, devices, showDelete = false)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val device = adapter.getItem(position)
            getSharedPreferences("sysmon", MODE_PRIVATE)
                .edit()
                .putString("widget_${appWidgetId}_device_id", device.id)
                .apply()
            showStatsStep()
        }
    }

    private fun showStatsStep() {
        findViewById<View>(R.id.deviceStepGroup).visibility = View.GONE
        findViewById<View>(R.id.statsStepGroup).visibility = View.VISIBLE

        findViewById<Button>(R.id.doneConfigButton).setOnClickListener {
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

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}
