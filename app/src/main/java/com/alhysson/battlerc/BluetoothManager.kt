package com.alhysson.battlerc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBtManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Gerenciador Bluetooth Clássico (SPP / RFCOMM) otimizado para HC-05 e HC-06.
 */
class BluetoothManager(
    context: Context,
    private val onStateChange: (connected: Boolean, deviceName: String?, message: String) -> Unit
) {
    companion object {
        // UUID padrão SPP para módulos HC-05 / HC-06
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBtManager)?.adapter

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val paired = adapter?.bondedDevices?.toList().orEmpty()
        // Coloca HC-06 / HC-05 sempre no topo da lista!
        return paired.sortedByDescending {
            val name = it.name.orEmpty().uppercase()
            name.contains("HC-06") || name.contains("HC-05")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        executor.execute {
            disconnectInternal()
            val name = device.name ?: device.address
            onStateChange(false, name, "Conectando em $name...")
            try {
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                outputStream = s.outputStream
                isConnected = true
                onStateChange(true, name, "$name • CONECTADO")
            } catch (e: IOException) {
                disconnectInternal()
                onStateChange(false, null, "Falha ao conectar em $name")
            }
        }
    }

    fun send(cmd: Char) {
        if (!isConnected) return
        executor.execute {
            try {
                outputStream?.write(cmd.code)
                outputStream?.flush()
            } catch (e: IOException) {
                disconnectInternal()
                onStateChange(false, null, "Conexão perdida!")
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal()
            onStateChange(false, null, "DESCONECTADO")
        }
    }

    private fun disconnectInternal() {
        isConnected = false
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        outputStream = null
        socket = null
    }
}
