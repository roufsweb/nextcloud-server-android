package com.nextcloud.android.server

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            val intent = Intent(this, NextcloudServerService::class.java)
            startService(intent)
            updateStatus("Server starting...")
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, NextcloudServerService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            updateStatus("Server stopping...")
        }
    }

    private fun updateStatus(status: String) {
        statusText.text = status
    }
}
