package com.sysmonwidget.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("sysmon", Context.MODE_PRIVATE)
        val input = findViewById<EditText>(R.id.serverAddressInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        input.setText(prefs.getString("server_address", ""))

        saveButton.setOnClickListener {
            val address = input.text.toString().trim()
            prefs.edit().putString("server_address", address).apply()
            SysMonWidgetProvider.refreshAllWidgets(this)
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
