package com.bazsoft.balanzabt.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bazsoft.balanzabt.R
import com.bazsoft.balanzabt.service.BalanzaForegroundService

class MainActivity : AppCompatActivity() {

    private val PERM_REQUEST = 100
    private lateinit var listView: ListView
    private lateinit var btnIniciar: Button
    private lateinit var btnDetener: Button
    private lateinit var tvEstado: TextView
    private lateinit var layoutPeso: LinearLayout
    private lateinit var tvPeso: TextView
    private lateinit var tvEstadoPeso: TextView
    private lateinit var tvHora: TextView
    private lateinit var tvRaw: TextView
    private lateinit var tvVinculado: TextView
    private var selectedDevice: BluetoothDevice? = null

    private val pesoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BalanzaForegroundService.ACTION_PESO_RECIBIDO -> {
                    val peso   = intent.getStringExtra(BalanzaForegroundService.EXTRA_PESO) ?: "---"
                    val estado = intent.getStringExtra(BalanzaForegroundService.EXTRA_ESTADO) ?: "---"
                    val codigo = intent.getStringExtra(BalanzaForegroundService.EXTRA_CODIGO) ?: ""
                    val hora   = intent.getStringExtra(BalanzaForegroundService.EXTRA_HORA) ?: ""
                    mostrarPeso(peso, estado, codigo, hora)
                }
                BalanzaForegroundService.ACTION_CONECTADO -> {
                    val nombre = intent.getStringExtra(BalanzaForegroundService.EXTRA_DEVICE_NAME) ?: "Balanza"
                    layoutPeso.visibility = android.view.View.VISIBLE
                    tvEstado.text = "Conectado a: $nombre"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Conectado con exito")
                        .setMessage("Balanza: $nombre\nEnviando peso al backend...")
                        .setPositiveButton("OK", null)
                        .show()
                }
                BalanzaForegroundService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(BalanzaForegroundService.EXTRA_ERROR) ?: ""
                    tvEstado.text = "Reconectando... ($error)"
                }
                BalanzaForegroundService.ACTION_RAW -> {
                    val raw = intent.getStringExtra(BalanzaForegroundService.EXTRA_RAW) ?: ""
                    layoutPeso.visibility = android.view.View.VISIBLE
                    tvRaw.text = "Datos BT:\n$raw"
                }
                BalanzaForegroundService.ACTION_DESVINCULADO -> {
                    // Balanza desconectada por mucho tiempo — limpiar token
                    limpiarVinculacion()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView     = findViewById(R.id.listDispositivos)
        btnIniciar   = findViewById(R.id.btnIniciar)
        btnDetener   = findViewById(R.id.btnDetener)
        tvEstado     = findViewById(R.id.tvEstado)
        layoutPeso   = findViewById(R.id.layoutPeso)
        tvPeso       = findViewById(R.id.tvPeso)
        tvEstadoPeso = findViewById(R.id.tvEstadoPeso)
        tvHora       = findViewById(R.id.tvHora)
        tvRaw        = findViewById(R.id.tvRaw)
        tvVinculado  = findViewById(R.id.tvVinculado)

        layoutPeso.visibility = android.view.View.GONE

        // SIEMPRE manejar deep link — sobreescribe lo guardado
        manejarDeepLink()

        verificarYPedirPermisos()
    }

    private fun manejarDeepLink() {
        intent?.data?.let { uri ->
            if (uri.scheme == "balanzabt" && uri.host == "vincular") {
                val token   = uri.getQueryParameter("token")
                val usuario = uri.getQueryParameter("usuario")?.trim()
                val baseUrl = uri.getQueryParameter("baseUrl")

                if (token != null && usuario != null && baseUrl != null) {
                    // SIEMPRE sobreescribir — nunca usar datos viejos
                    getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("auth_token", token)
                        .putString("usuario",    usuario)
                        .putString("base_url",   baseUrl)
                        .putLong("token_tiempo", System.currentTimeMillis())
                        .apply()

                    tvVinculado.text       = "Vinculado: $usuario | $baseUrl"
                    tvVinculado.visibility = android.view.View.VISIBLE

                    AlertDialog.Builder(this)
                        .setTitle("Vinculado con exito")
                        .setMessage("Usuario: $usuario\nServidor: $baseUrl\n\nSelecciona tu balanza y presiona Iniciar.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun limpiarVinculacion() {
        getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("auth_token")
            .remove("usuario")
            .remove("base_url")
            .remove("token_tiempo")
            .apply()

        tvVinculado.text       = "Sin vincular — abre la web para vincular"
        tvVinculado.visibility = android.view.View.VISIBLE
        tvEstado.text          = "Desvinculado por inactividad"
        layoutPeso.visibility  = android.view.View.GONE

        AlertDialog.Builder(this)
            .setTitle("Sesion expirada")
            .setMessage("La balanza estuvo desconectada demasiado tiempo.\n\nVuelve a la web y presiona Vincular Balanza.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BalanzaForegroundService.ACTION_PESO_RECIBIDO)
            addAction(BalanzaForegroundService.ACTION_CONECTADO)
            addAction(BalanzaForegroundService.ACTION_ERROR)
            addAction(BalanzaForegroundService.ACTION_RAW)
            addAction(BalanzaForegroundService.ACTION_DESVINCULADO)
        }
        registerReceiver(pesoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Mostrar info de vinculación guardada
        val prefs   = getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
        val usuario = prefs.getString("usuario", null)
        val baseUrl = prefs.getString("base_url", null)
        if (usuario != null && baseUrl != null) {
            tvVinculado.text       = "Vinculado: $usuario | $baseUrl"
            tvVinculado.visibility = android.view.View.VISIBLE
        } else {
            tvVinculado.text       = "Sin vincular — abre la web para vincular"
            tvVinculado.visibility = android.view.View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(pesoReceiver) } catch (e: Exception) {}
    }

    private fun mostrarPeso(peso: String, estado: String, codigo: String, hora: String) {
        layoutPeso.visibility = android.view.View.VISIBLE
        tvPeso.text = "$peso kg"
        tvHora.text = "Ultima lectura: $hora"
        val (color, texto) = when (codigo) {
            "B"  -> Pair("#28a745", "Estable")
            "@"  -> Pair("#dc3545", "Inestable")
            "C"  -> Pair("#007bff", "Cero Estable")
            "A"  -> Pair("#fd7e14", "Cero Inestable")
            else -> Pair("#888888", estado)
        }
        tvEstadoPeso.text = texto
        tvEstadoPeso.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun verificarYPedirPermisos() {
        val faltantes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                faltantes.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                faltantes.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (faltantes.isNotEmpty())
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), PERM_REQUEST)
        else
            cargarDispositivos()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            cargarDispositivos()
        else
            tvEstado.text = "Permisos Bluetooth denegados"
    }

    private fun cargarDispositivos() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            tvEstado.text = "Activa el Bluetooth"
            return
        }
        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            tvEstado.text = "No hay dispositivos emparejados"
            return
        }

        val nombres = paired.map { it.name ?: it.address }.toTypedArray()
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_single_choice, nombres
        )
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        val prefs = getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
        val savedAddress = prefs.getString("last_device_address", null)
        if (savedAddress != null) {
            val idx = paired.indexOfFirst { it.address == savedAddress }
            if (idx >= 0) {
                listView.setItemChecked(idx, true)
                selectedDevice = paired[idx]
            }
        }
        tvEstado.text = "Selecciona tu balanza y presiona Iniciar"

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = paired[position]
        }

        btnIniciar.setOnClickListener {
            val dev = selectedDevice ?: run {
                Toast.makeText(this, "Selecciona una balanza primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Verificar que esté vinculado antes de iniciar
            val token = prefs.getString("auth_token", null)
            if (token == null) {
                AlertDialog.Builder(this)
                    .setTitle("Sin vincular")
                    .setMessage("Primero debes vincular la app desde la web.\n\nAbre la web y presiona 'Vincular Balanza'.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("last_device_address", dev.address)
                .putString("last_device_name",    dev.name ?: dev.address)
                .apply()

            val serviceIntent = Intent(this, BalanzaForegroundService::class.java).apply {
                action = BalanzaForegroundService.ACTION_SELECCIONAR_DISPOSITIVO
                putExtra(BalanzaForegroundService.EXTRA_DEVICE_ADDRESS, dev.address)
                putExtra(BalanzaForegroundService.EXTRA_DEVICE_NAME,    dev.name ?: "Balanza")
            }
            startForegroundService(serviceIntent)
            tvEstado.text = "Conectando a ${dev.name}..."
        }

        btnDetener.setOnClickListener {
            stopService(Intent(this, BalanzaForegroundService::class.java))
            prefs.edit()
                .remove("last_device_address")
                .remove("auth_token")
                .remove("usuario")
                .remove("base_url")
                .apply()
            tvEstado.text          = "Servicio detenido"
            tvVinculado.text       = "Sin vincular — abre la web para vincular"
            layoutPeso.visibility  = android.view.View.GONE
        }
    }
}
