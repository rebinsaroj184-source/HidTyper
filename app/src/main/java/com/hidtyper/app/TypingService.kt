package com.hidtyper.app

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executor

class TypingService : Service() {

    companion object {
        const val CHANNEL_ID = "hidtyper_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.hidtyper.app.START"
        const val ACTION_STOP = "com.hidtyper.app.STOP"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_DELAY = "delay"

        const val BROADCAST_PROGRESS = "com.hidtyper.app.PROGRESS"
        const val BROADCAST_STATUS = "com.hidtyper.app.STATUS"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_END_VAL = "endVal"
        const val EXTRA_STATUS_TEXT = "statusText"

        @Volatile var isRunning = false
        @Volatile var isPaused = false
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var workerThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }

    private var currentNumber = 0
    private var endNumber = 0
    private var delayMs = 150L

    private val descriptor: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x06.toByte(),
        0xA1.toByte(), 0x01.toByte(), 0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(), 0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(), 0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x08.toByte(), 0x81.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x06.toByte(), 0x75.toByte(), 0x08.toByte(),
        0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x65.toByte(),
        0x05.toByte(), 0x07.toByte(), 0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(), 0x81.toByte(), 0x00.toByte(),
        0xC0.toByte()
    )

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
            if (registered) connectToPaired()
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                isPaused = false
                broadcastStatus("Connected to ${safeName(device)}")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice == device) connectedDevice = null
                isPaused = true
                broadcastStatus("Disconnected - paused at $currentNumber")
                updateNotification("Paused (disconnected) at $currentNumber")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentNumber = intent.getIntExtra(EXTRA_START, 1)
                endNumber = intent.getIntExtra(EXTRA_END, 1000000)
                delayMs = intent.getLongExtra(EXTRA_DELAY, 150L)
                startForeground(NOTIF_ID, buildNotification("Starting..."))
                acquireWakeLock()
                isRunning = true
                isPaused = false
                connectHidProfile()
                startLoop()
            }
            ACTION_STOP -> {
                isRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun connectHidProfile() {
        bluetoothAdapter?.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HID Typer", "Phone as keyboard", "HidTyper",
            BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
        )
        try {
            hidDevice?.registerApp(sdp, null, null, executor, hidCallback)
        } catch (e: SecurityException) { }
    }

    private fun connectToPaired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val paired = try { bluetoothAdapter?.bondedDevices } catch (e: SecurityException) { null }
        val target = paired?.firstOrNull() ?: return
        try {
            hidDevice?.connect(target)
        } catch (e: SecurityException) { }
    }

    private fun safeName(device: BluetoothDevice?): String {
        return try { device?.name ?: "device" } catch (e: SecurityException) { "device" }
    }

    private fun startLoop() {
        workerThread = Thread {
            while (currentNumber <= endNumber && isRunning) {
                if (isPaused || connectedDevice == null) {
                    Thread.sleep(500)
                    continue
                }
                sendNumber(connectedDevice!!, currentNumber)
                val n = currentNumber
                broadcastProgress(n, endNumber)
                updateNotification("Typing: $n / $endNumber")
                currentNumber++
                try { Thread.sleep(delayMs) } catch (e: InterruptedException) { }
            }
            isRunning = false
            broadcastStatus("Done or stopped at $currentNumber")
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        workerThread?.start()
    }

    private fun keycodeForDigit(c: Char): Byte {
        return if (c == '0') 0x27 else (0x1E + (c - '1')).toByte()
    }

    private fun sendNumber(device: BluetoothDevice, number: Int) {
        for (c in number.toString()) sendKey(device, keycodeForDigit(c))
        sendKey(device, 0x28)
    }

    private fun sendKey(device: BluetoothDevice, keycode: Byte) {
        val press = byteArrayOf(0, 0, keycode, 0, 0, 0, 0, 0)
        val release = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        try {
            hidDevice?.sendReport(device, 0, press)
            Thread.sleep(4)
            hidDevice?.sendReport(device, 0, release)
            Thread.sleep(4)
        } catch (e: SecurityException) { }
    }

    private fun broadcastProgress(current: Int, end: Int) {
        val i = Intent(BROADCAST_PROGRESS)
        i.putExtra(EXTRA_CURRENT, current)
        i.putExtra(EXTRA_END_VAL, end)
        sendBroadcast(i)
    }

    private fun broadcastStatus(text: String) {
        val i = Intent(BROADCAST_STATUS)
        i.putExtra(EXTRA_STATUS_TEXT, text)
        sendBroadcast(i)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HidTyper::TypingLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "HID Typer", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HID Typer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()
    }
}
