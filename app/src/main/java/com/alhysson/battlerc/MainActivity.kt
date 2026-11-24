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
import android.widget.Button
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
    private lateinit var tvAction: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnMusic: Button
    private lateinit var btnSpeed60: Button
    private lateinit var btnSpeed85: Button
    private lateinit var btnSpeed100: Button

    // Estado multi-toque (permite segurar Frente + Esquerda juntos -> envia 'G'!)
    private var holdingForward = false
    private var holdingBack = false
    private var holdingLeft = false
    private var holdingRight = false
    private var musicPlaying = false

    // Heartbeat de 60ms para manter o Watchdog de 200ms do Arduino sempre alimentado
    private val handler = Handler(Looper.getMainLooper())
    private val driveLoop = object : Runnable {
        override fun run() {
            val (cmd, label) = computeMovementCommand()
            if (btManager.isConnected) {
                btManager.send(cmd)
            }
            tvTelemetry.text = "TX: $cmd"
            tvAction.text = label
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
        tvAction = findViewById(R.id.tvAction)
        btnConnect = findViewById(R.id.btnConnect)
        btnMusic = findViewById(R.id.btnMusic)
        btnSpeed60 = findViewById(R.id.btnSpeed60)
        btnSpeed85 = findViewById(R.id.btnSpeed85)
        btnSpeed100 = findViewById(R.id.btnSpeed100)

        btManager = BluetoothManager(this) { connected, _, message ->
            runOnUiThread {
                tvStatus.text = message
                val dotColor = if (connected) Color.parseColor("#34D399") else Color.parseColor("#F43F5E")
                statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
                btnConnect.text = if (connected) "DESCONECTAR" else "CONECTAR HC-06"
                if (connected) {
                    // Garante velocidade máxima ao conectar
                    selectSpeedPreset('q', btnSpeed100)
                }
            }
        }

        // Botões de Tração e Giro com suporte a Multi-Touch
        bindHoldButton(findViewById(R.id.btnForward)) { holdingForward = it }
        bindHoldButton(findViewById(R.id.btnBack)) { holdingBack = it }
        bindHoldButton(findViewById(R.id.btnLeft)) { holdingLeft = it }
        bindHoldButton(findViewById(R.id.btnRight)) { holdingRight = it }

        // Freio de emergência
        findViewById<Button>(R.id.btnBrake).setOnClickListener {
            holdingForward = false
            holdingBack = false
            holdingLeft = false
            holdingRight = false
            btManager.send('S')
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        // 3 Presets instantâneos de velocidade (estilo Kino)
        btnSpeed60.setOnClickListener { selectSpeedPreset('4', btnSpeed60) }
        btnSpeed85.setOnClickListener { selectSpeedPreset('8', btnSpeed85) }
        btnSpeed100.setOnClickListener { selectSpeedPreset('q', btnSpeed100) }

        // Botão dedicado Wind Of Change (W / w)
        btnMusic.setOnClickListener {
            musicPlaying = !musicPlaying
            val cmd = if (musicPlaying) 'W' else 'w'
            btManager.send(cmd)
            btnMusic.text = if (musicPlaying) "🔊 PAUSAR MÚSICA" else "🎸 WIND OF CHANGE"
            btnMusic.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (musicPlaying) "#14532D" else "#2B2110")
            )
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        // Conexão / Desconexão Bluetooth
        btnConnect.setOnClickListener {
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
    private fun bindHoldButton(button: Button, onHoldChanged: (Boolean) -> Unit) {
        button.setOnTouchListener { view, event ->
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

    private fun computeMovementCommand(): Pair<Char, String> {
        return when {
            holdingForward && holdingLeft  -> 'G' to "FRENTE + ESQ"
            holdingForward && holdingRight -> 'I' to "FRENTE + DIR"
            holdingBack && holdingLeft     -> 'H' to "RÉ + ESQ"
            holdingBack && holdingRight    -> 'J' to "RÉ + DIR"
            holdingForward                 -> 'F' to "FRENTE"
            holdingBack                    -> 'B' to "RÉ"
            holdingLeft                    -> 'L' to "GIRO ESQUERDA"
            holdingRight                   -> 'R' to "GIRO DIREITA"
            else                           -> 'S' to "PARADO"
        }
    }

    private fun selectSpeedPreset(cmd: Char, activeBtn: Button) {
        btManager.send(cmd)
        val all = listOf(btnSpeed60, btnSpeed85, btnSpeed100)
        for (b in all) {
            val selected = (b == activeBtn)
            b.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (selected) "#34D399" else "#222834")
            )
            b.setTextColor(Color.parseColor(if (selected) "#0E1116" else "#94A3B8"))
        }
        activeBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
