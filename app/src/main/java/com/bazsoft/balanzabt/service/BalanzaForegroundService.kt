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
        const val ACTION_SELECCIONAR_DISPOSITIVO = "SELECCIONAR_DISPOSITIVO"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BACKEND_URL = "https://bazsoft.sosnegocios.com/apiBalanza/balanza/peso"
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceAddress: String? = null
    private var deviceName: String = "Balanza"
    private var isRunning = false
    private var reconnectThread: Thread? = null

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
        when (intent?.action) {
            ACTION_SELECCIONAR_DISPOSITIVO -> {
                deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                deviceName    = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Balanza"
                iniciarConexion()
            }
        }

        startForeground(NOTIF_ID, construirNotificacion("Iniciando...", deviceName))
        return START_STICKY // El servicio se reinicia automáticamente si Android lo mata
    }

    private fun iniciarConexion() {
        isRunning = true
        reconnectThread = Thread {
            while (isRunning) {
                try {
                    actualizarNotificacion("Conectando a $deviceName...")
                    conectarYLeer()
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                    actualizarNotificacion("Reconectando en 5s...")
                    Thread.sleep(5000)
                }
            }
        }
        reconnectThread?.start()
    }

    private fun conectarYLeer() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

        bluetoothSocket?.close()
        bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        bluetoothSocket?.connect()

        actualizarNotificacion("✅ Conectado a $deviceName")
        Log.d(TAG, "Conectado al dispositivo BT")

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

        Log.d(TAG, "Peso: $pesoReal | Estado: $estado")
        enviarAlBackend(pesoReal, estado, digControl)
    }

    private fun enviarAlBackend(peso: String, estado: String, codigo: String) {
        val json = """{"peso":"$peso","estado":"$estado","codigo":"$codigo"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder()
            .url(BACKEND_URL)
            .post(body)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Enviado OK: $peso - $estado")
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error enviando: ${e.message}")
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
            .setContentTitle("Balanza BT - $titulo")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, construirNotificacion(texto, deviceName))
    }

    private fun crearCanalNotificacion() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Balanza Bluetooth",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Servicio de lectura de balanza en segundo plano"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun adquirirWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BalanzaBT::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 horas máximo
    }

    override fun onDestroy() {
        isRunning = false
        bluetoothSocket?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
