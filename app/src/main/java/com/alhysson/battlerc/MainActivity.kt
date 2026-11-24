package com.alhysson.battlerc

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btManager: BluetoothManager

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var tvConnectLabel: TextView
    private lateinit var tvMusicLabel: TextView
    private lateinit var ivMusic: ImageView
    private lateinit var speedometer: SpeedometerView

    private lateinit var btnSpeed60: TextView
    private lateinit var btnSpeed85: TextView
    private lateinit var btnSpeed100: TextView

    // Estado multi-toque (permite segurar Drive + Left/Right juntos -> envia G/I/H/J)
    private var holdingForward = false
    private var holdingBack = false
    private var holdingLeft = false
    private var holdingRight = false
    private var musicPlaying = false
    private var currentSpeedLimitPct = 100

    // Heartbeat de 60ms para alimentar o Watchdog de 200ms do Arduino
    private val handler = Handler(Looper.getMainLooper())
    private val driveLoop = object : Runnable {
        override fun run() {
            val (cmd, gear, active) = computeMovementTelemetry()
            if (btManager.isConnected) {
                btManager.send(cmd)
            }
            tvTelemetry.text = "TX: $cmd"
            speedometer.setTelemetry(active, currentSpeedLimitPct, gear)
            handler.postDelayed(this, 60L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            showDevicePicker()
        } else {
            Toast.makeText(this, "Permita Dispositivos por perto (Bluetooth) para conectar.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        tvConnectLabel = findViewById(R.id.tvConnectLabel)
        tvMusicLabel = findViewById(R.id.tvMusicLabel)
        ivMusic = findViewById(R.id.ivMusic)
        speedometer = findViewById(R.id.speedometer)

        btnSpeed60 = findViewById(R.id.btnSpeed60)
        btnSpeed85 = findViewById(R.id.btnSpeed85)
        btnSpeed100 = findViewById(R.id.btnSpeed100)

        btManager = BluetoothManager(this) { connected, deviceName, message ->
            runOnUiThread {
                tvStatus.text = if (connected) "${deviceName ?: "HC-06"} • ONLINE" else "UNIMATE • ${message.uppercase()}"
                val dotColor = if (connected) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
                statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
                tvConnectLabel.text = if (connected) "DESCONECTAR" else "CONECTAR"
                if (connected) {
                    selectSpeedPreset('q', 100, btnSpeed100)
                }
            }
        }

        // Controles de Tração e Direção (Multi-Touch)
        bindHoldControl(findViewById(R.id.btnForward)) { holdingForward = it }
        bindHoldControl(findViewById(R.id.btnBack)) { holdingBack = it }
        bindHoldControl(findViewById(R.id.btnLeft)) { holdingLeft = it }
        bindHoldControl(findViewById(R.id.btnRight)) { holdingRight = it }

        // Freio de emergência
        findViewById<View>(R.id.btnBrake).setOnClickListener {
            holdingForward = false
            holdingBack = false
            holdingLeft = false
            holdingRight = false
            btManager.send('S')
            speedometer.setTelemetry(false, currentSpeedLimitPct, "P")
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        // 3 Modos de Potência (ECO / SPORT / RACE)
        btnSpeed60.setOnClickListener { selectSpeedPreset('4', 60, btnSpeed60) }
        btnSpeed85.setOnClickListener { selectSpeedPreset('8', 85, btnSpeed85) }
        btnSpeed100.setOnClickListener { selectSpeedPreset('q', 100, btnSpeed100) }
        updateSpeedTabsUI(btnSpeed100)

        // Toggle Wind Of Change (W / w)
        findViewById<View>(R.id.btnMusic).setOnClickListener {
            musicPlaying = !musicPlaying
            btManager.send(if (musicPlaying) 'W' else 'w')
            val tint = Color.parseColor(if (musicPlaying) "#F59E0B" else "#F8FAFC")
            tvMusicLabel.text = if (musicPlaying) "WIND ON" else "AUDIO"
            tvMusicLabel.setTextColor(tint)
            ivMusic.imageTintList = ColorStateList.valueOf(tint)
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        // Conexão Bluetooth
        findViewById<View>(R.id.btnConnect).setOnClickListener {
            if (btManager.isConnected) {
                btManager.disconnect()
            } else {
                requestPermissionsAndConnect()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(driveLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(driveLoop)
        btManager.send('S')
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldControl(control: View, onHoldChanged: (Boolean) -> Unit) {
        control.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onHoldChanged(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    onHoldChanged(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun computeMovementTelemetry(): Triple<Char, String, Boolean> {
        return when {
            holdingForward && holdingLeft  -> Triple('G', "D-L", true)
            holdingForward && holdingRight -> Triple('I', "D-R", true)
            holdingBack && holdingLeft     -> Triple('H', "R-L", true)
            holdingBack && holdingRight    -> Triple('J', "R-R", true)
            holdingForward                 -> Triple('F', "D", true)
            holdingBack                    -> Triple('B', "R", true)
            holdingLeft                    -> Triple('L', "PIV-L", true)
            holdingRight                   -> Triple('R', "PIV-R", true)
            else                           -> Triple('S', "N", false)
        }
    }

    private fun selectSpeedPreset(cmd: Char, pct: Int, activeBtn: TextView) {
        currentSpeedLimitPct = pct
        btManager.send(cmd)
        updateSpeedTabsUI(activeBtn)
        activeBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun updateSpeedTabsUI(activeBtn: TextView) {
        val all = listOf(btnSpeed60, btnSpeed85, btnSpeed100)
        for (b in all) {
            val selected = (b == activeBtn)
            b.setTextColor(
                Color.parseColor(
                    if (!selected) "#94A3B8"
                    else if (b == btnSpeed100) "#EF4444"
                    else if (b == btnSpeed85) "#F59E0B"
                    else "#10B981"
                )
            )
            b.alpha = if (selected) 1.0f else 0.65f
        }
    }

    private fun requestPermissionsAndConnect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            showDevicePicker()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        if (!btManager.isBluetoothEnabled()) {
            Toast.makeText(this, "Ative o Bluetooth do celular primeiro!", Toast.LENGTH_SHORT).show()
            return
        }
        val devices = btManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Pareie o HC-06 nas Configurações do Android (senha 1234).", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { "${it.name ?: "Dispositivo"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selecionar Robô Bluetooth")
            .setItems(labels) { _, which ->
                btManager.connect(devices[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
