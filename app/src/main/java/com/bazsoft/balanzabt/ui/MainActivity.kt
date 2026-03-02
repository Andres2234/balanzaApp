package com.bazsoft.balanzabt.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bazsoft.balanzabt.R
import com.bazsoft.balanzabt.service.BalanzaForegroundService

class MainActivity : AppCompatActivity() {

    private val PERM_REQUEST = 100
    private lateinit var listView: ListView
    private lateinit var btnIniciar: Button
    private lateinit var btnDetener: Button
    private lateinit var tvEstado: TextView
    private var selectedDevice: BluetoothDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView  = findViewById(R.id.listDispositivos)
        btnIniciar = findViewById(R.id.btnIniciar)
        btnDetener = findViewById(R.id.btnDetener)
        tvEstado  = findViewById(R.id.tvEstado)

        solicitarPermisos()
    }

    private fun solicitarPermisos() {
        val permisos = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT)
            permisos.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val faltantes = permisos.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), PERM_REQUEST)
        } else {
            cargarDispositivosEmparejados()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            cargarDispositivosEmparejados()
        } else {
            tvEstado.text = "⚠️ Permisos de Bluetooth requeridos"
        }
    }

    private fun cargarDispositivosEmparejados() {
        val adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null || !adapter.isEnabled) {
            tvEstado.text = "⚠️ Activa el Bluetooth del dispositivo"
            return
        }

        val paired: Set<BluetoothDevice> = adapter.bondedDevices
        if (paired.isEmpty()) {
            tvEstado.text = "⚠️ No hay dispositivos Bluetooth emparejados"
            return
        }

        val nombres = paired.map { it.name ?: it.address }.toTypedArray()
        val devices = paired.toList()

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, nombres)
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        // Restaurar selección guardada
        val prefs = getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
        val savedAddress = prefs.getString("last_device_address", null)
        if (savedAddress != null) {
            val idx = devices.indexOfFirst { it.address == savedAddress }
            if (idx >= 0) {
                listView.setItemChecked(idx, true)
                selectedDevice = devices[idx]
                tvEstado.text = "✅ Servicio activo con: ${devices[idx].name}"
            }
        } else {
            tvEstado.text = "Selecciona tu balanza y presiona Iniciar"
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = devices[position]
        }

        btnIniciar.setOnClickListener {
            val dev = selectedDevice ?: run {
                Toast.makeText(this, "Selecciona una balanza primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Guardar para reconexión automática al reiniciar
            prefs.edit()
                .putString("last_device_address", dev.address)
                .putString("last_device_name", dev.name ?: dev.address)
                .apply()

            val serviceIntent = Intent(this, BalanzaForegroundService::class.java).apply {
                action = BalanzaForegroundService.ACTION_SELECCIONAR_DISPOSITIVO
                putExtra(BalanzaForegroundService.EXTRA_DEVICE_ADDRESS, dev.address)
                putExtra(BalanzaForegroundService.EXTRA_DEVICE_NAME, dev.name ?: "Balanza")
            }
            startForegroundService(serviceIntent)

            tvEstado.text = "✅ Servicio iniciado con: ${dev.name}"
            Toast.makeText(this, "Servicio iniciado. Puedes minimizar la app.", Toast.LENGTH_LONG).show()
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, BalanzaForegroundService::class.java))
            prefs.edit().remove("last_device_address").apply()
            tvEstado.text = "🔴 Servicio detenido"
        }
    }
}
