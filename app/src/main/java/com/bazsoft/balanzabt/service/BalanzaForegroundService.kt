package com.bazsoft.balanzabt.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bazsoft.balanzabt.ui.MainActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class BalanzaForegroundService : Service() {

    companion object {
        const val TAG = "BalanzaService"
        const val CHANNEL_ID = "balanza_channel"
        const val NOTIF_ID = 1

        // Acciones para comunicarse con MainActivity
        const val ACTION_SELECCIONAR_DISPOSITIVO = "SELECCIONAR_DISPOSITIVO"
        const val ACTION_PESO_RECIBIDO = "com.bazsoft.balanzabt.PESO_RECIBIDO"
        const val ACTION_CONECTADO     = "com.bazsoft.balanzabt.CONECTADO"
        const val ACTION_ERROR         = "com.bazsoft.balanzabt.ERROR"

        // Extras
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME    = "device_name"
        const val EXTRA_PESO           = "peso"
        const val EXTRA_ESTADO         = "estado"
        const val EXTRA_CODIGO         = "codigo"
        const val EXTRA_HORA           = "hora"
        const val EXTRA_ERROR          = "error"

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BACKEND_URL = "https://bazsoft.sosnegocios.com/apiBalanza/balanza/peso"
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceAddress: String? = null
    private var deviceName: String = "Balanza"
    private var isRunning = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        adquirirWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, construirNotificacion("Iniciando...", "Balanza BT"))

        if (intent?.action == ACTION_SELECCIONAR_DISPOSITIVO) {
            deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            deviceName    = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Balanza"
            isRunning     = true
            Thread { loopConexion() }.start()
        }
        return START_STICKY
    }

    private fun loopConexion() {
        while (isRunning) {
            try {
                actualizarNotificacion("Conectando a $deviceName...")
                conectarYLeer()
            } catch (e: Exception) {
                Log.e(TAG, "Error de conexión: ${e.message}")
                enviarBroadcast(ACTION_ERROR, error = "Reconectando en 5s... (${e.message})")
                actualizarNotificacion("Reconectando en 5s...")
                Thread.sleep(5000)
            }
        }
    }

    private fun conectarYLeer() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

        bluetoothSocket?.close()
        bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        bluetoothSocket!!.connect()

        // Notificar conexión exitosa
        Log.d(TAG, "✅ Conectado a $deviceName")
        actualizarNotificacion("✅ Conectado a $deviceName")
        enviarBroadcast(ACTION_CONECTADO)

        val inputStream = bluetoothSocket!!.inputStream
        val buffer      = ByteArray(256)
        val acumulado   = StringBuilder()

        while (isRunning) {
            val bytes    = inputStream.read(buffer)
            val fragment = String(buffer, 0, bytes, Charsets.US_ASCII)
            acumulado.append(fragment)

            var idx: Int
            while (acumulado.indexOf("=").also { idx = it } >= 0) {
                val trama = acumulado.substring(0, idx)
                acumulado.delete(0, idx + 1)
                procesarTrama(trama)
            }
        }
    }

    private fun procesarTrama(trama: String) {
        if (trama.length < 9) return

        val pesoReal   = trama.substring(0, 8).trim()
        val digControl = trama.substring(8, 1)
        val estado = when (digControl) {
            "B"  -> "Estable"
            "@"  -> "Inestable"
            "C"  -> "Cero Estable"
            "A"  -> "Cero Inestable"
            else -> "Desconocido"
        }
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        Log.d(TAG, "Peso: $pesoReal | Estado: $estado")
        actualizarNotificacion("$pesoReal kg — $estado")

        // Enviar a la UI
        enviarBroadcast(ACTION_PESO_RECIBIDO, pesoReal, estado, digControl, hora)

        // Enviar al backend
        enviarAlBackend(pesoReal, estado, digControl)
    }

    private fun enviarBroadcast(
        action: String,
        peso: String = "",
        estado: String = "",
        codigo: String = "",
        hora: String = "",
        error: String = ""
    ) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_PESO, peso)
            putExtra(EXTRA_ESTADO, estado)
            putExtra(EXTRA_CODIGO, codigo)
            putExtra(EXTRA_HORA, hora)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun enviarAlBackend(peso: String, estado: String, codigo: String) {
        val json = """{"peso":"$peso","estado":"$estado","codigo":"$codigo"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(BACKEND_URL).post(body).build()
        httpClient.newCall(req).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Backend OK: $peso")
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Backend no disponible aún: ${e.message}")
            }
        })
    }

    private fun construirNotificacion(texto: String, titulo: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, construirNotificacion("Balanza BT — $deviceName", texto))
    }

    private fun crearCanalNotificacion() {
        val channel = NotificationChannel(CHANNEL_ID, "Balanza Bluetooth", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Servicio de lectura de balanza en segundo plano"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun adquirirWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BalanzaBT::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onDestroy() {
        isRunning = false
        bluetoothSocket?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
