package com.hidtyper.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var startNum: EditText
    private lateinit var endNum: EditText
    private lateinit var delayMs: EditText

    private var bluetoothAdapter: BluetoothAdapter? = null

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = intent?.getIntExtra(TypingService.EXTRA_CURRENT, 0) ?: 0
            val end = intent?.getIntExtra(TypingService.EXTRA_END_VAL, 0) ?: 0
            progressText.text = "Progress: $current / $end"
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(TypingService.EXTRA_STATUS_TEXT) ?: ""
            applyStatusText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressText = findViewById(R.id.progressText)
        startNum = findViewById(R.id.startNum)
        endNum = findViewById(R.id.endNum)
        delayMs = findViewById(R.id.delayMs)

        val registerBtn: Button = findViewById(R.id.registerBtn)
        val pairBtn: Button = findViewById(R.id.pairBtn)
        val startBtn: Button = findViewById(R.id.startBtn)
        val stopBtn: Button = findViewById(R.id.stopBtn)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        registerBtn.setOnClickListener { requestPermissionsAndProceed() }
        pairBtn.setOnClickListener {
            Toast.makeText(this, "Permissions ready hone ke baad seedha Start/Resume dabao.", Toast.LENGTH_LONG).show()
        }
        startBtn.setOnClickListener { startTyping() }
        stopBtn.setOnClickListener { stopTyping() }

        loadSavedState()
    }

    override fun onResume() {
        super.onResume()
        loadSavedState()
    }

    override fun onStart() {
        super.onStart()
        val filter1 = IntentFilter(TypingService.BROADCAST_PROGRESS)
        val filter2 = IntentFilter(TypingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter1, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(statusReceiver, filter2, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter1)
            registerReceiver(statusReceiver, filter2)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) { }
    }

    private fun loadSavedState() {
        val prefs = getSharedPreferences(TypingService.PREFS_NAME, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(TypingService.KEY_RUNNING, false)
        val current = prefs.getInt(TypingService.KEY_CURRENT, 0)
        val end = prefs.getInt(TypingService.KEY_END, 0)
        val savedStatus = prefs.getString(TypingService.KEY_STATUS, null)

        if (savedStatus != null) applyStatusText(savedStatus)
        if (end > 0) progressText.text = "Progress: $current / $end"

        if (!running && current > 0) {
            startNum.setText((current + 1).toString())
            if (end > 0) endNum.setText(end.toString())
        }
    }

    private fun applyStatusText(text: String) {
        val running = TypingService.isRunning
        val prefix = if (running) "🟢 Running (background OK): " else "⚪ Stopped: "
        statusText.text = prefix + text
    }

    private fun hasPerm(p: String): Boolean =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndProceed() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPerm(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        } else {
            Toast.makeText(this, "Permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTyping() {
        val s = startNum.text.toString().toIntOrNull() ?: 1
        val e = endNum.text.toString().toIntOrNull() ?: 1000000
        val d = delayMs.text.toString().toLongOrNull() ?: 150L

        val intent = Intent(this, TypingService::class.java)
        intent.action = TypingService.ACTION_START
        intent.putExtra(TypingService.EXTRA_START, s)
        intent.putExtra(TypingService.EXTRA_END, e)
        intent.putExtra(TypingService.EXTRA_DELAY, d)
        ContextCompat.startForegroundService(this, intent)
        applyStatusText("Starting from $s...")
    }

    private fun stopTyping() {
        val intent = Intent(this, TypingService::class.java)
        intent.action = TypingService.ACTION_STOP
        startService(intent)
        statusText.postDelayed({ loadSavedState() }, 300)
    }
}
