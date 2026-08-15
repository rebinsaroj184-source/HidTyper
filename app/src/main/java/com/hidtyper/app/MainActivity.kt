package com.hidtyper.app

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var startNum: EditText
    private lateinit var endNum: EditText
    private lateinit var delayMs: EditText

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    // Standard USB HID boot keyboard descriptor
    private val descriptor: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x06.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x06.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x65.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(),
        0x81.toByte(), 0x00.toByte(),
        0xC0.toByte()
    )

    private val executor = Executor { command -> handler.post(command) }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                statusText.text = "Status: Registered = $registered"
            }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device
                    statusText.text = "Status: Connected to ${device?.name}"
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedDevice == device) connectedDevice = null
                    statusText.text = "Status: Disconnected"
                }
            }
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

        registerBtn.setOnClickListener { requestPermissionsAndRegister() }
        pairBtn.setOnClickListener { pickPairedDevice() }
        startBtn.setOnClickListener { startTyping() }
        stopBtn.setOnClickListener { running = false }
    }

    private fun hasPerm(p: String): Boolean =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndRegister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    1
                )
                return
            }
        }
        connectHidProfile()
    }

    private fun connectHidProfile() {
        bluetoothAdapter?.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HID Typer",
            "Phone as keyboard",
            "HidTyper",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            descriptor
        )
        try {
            hidDevice?.registerApp(sdp, null, null, executor, hidCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission missing: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickPairedDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_SHORT).show()
            return
        }
        val paired = try { bluetoothAdapter?.bondedDevices } catch (e: SecurityException) { null }
        if (paired.isNullOrEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair in system Bluetooth settings first.", Toast.LENGTH_LONG).show()
            return
        }
        // Connect to first paired device automatically; for multiple devices,
        // replace this with a list dialog if needed.
        val target = paired.first()
        try {
            hidDevice?.connect(target)
            Toast.makeText(this, "Connecting to ${target.name}...", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startTyping() {
        val s = startNum.text.toString().toIntOrNull() ?: 1
        val e = endNum.text.toString().toIntOrNull() ?: 1000000
        val delay = delayMs.text.toString().toLongOrNull() ?: 150L
        val device = connectedDevice
        if (device == null) {
            Toast.makeText(this, "No connected device", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        Thread {
            var i = s
            while (i <= e && running) {
                sendNumber(device, i)
                val n = i
                handler.post { progressText.text = "Progress: $n / $e" }
                i++
                try { Thread.sleep(delay) } catch (ex: InterruptedException) { }
            }
            handler.post { Toast.makeText(this, "Done or stopped", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    // USB HID usage IDs: digits '1'-'9' = 0x1E..0x26, '0' = 0x27, Enter = 0x28
    private fun keycodeForDigit(c: Char): Byte {
        return if (c == '0') 0x27 else (0x1E + (c - '1')).toByte()
    }

    private fun sendNumber(device: BluetoothDevice, number: Int) {
        val digits = number.toString()
        for (c in digits) {
            sendKey(device, keycodeForDigit(c))
        }
        sendKey(device, 0x28) // Enter
    }

    private fun sendKey(device: BluetoothDevice, keycode: Byte) {
        val press = byteArrayOf(0x00, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val release = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        try {
            hidDevice?.sendReport(device, 0, press)
            Thread.sleep(15)
            hidDevice?.sendReport(device, 0, release)
            Thread.sleep(15)
        } catch (e: SecurityException) {
            // permission revoked mid-run
        }
    }
}
