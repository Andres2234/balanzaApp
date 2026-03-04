package com.bazsoft.balanzabt.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

        // Tiempo máximo sin conectar antes de borrar token (30 segundos)
        const val MAX_SEGUNDOS_DESCONECTADO = 30L

        const val ACTION_SELECCIONAR_DISPOSITIVO = "SELECCIONAR_DISPOSITIVO"
        const val ACTION_PESO_RECIBIDO  = "com.bazsoft.balanzabt.PESO_RECIBIDO"
        const val ACTION_CONECTADO      = "com.bazsoft.balanzabt.CONECTADO"
        const val ACTION_ERROR          = "com.bazsoft.balanzabt.ERROR"
        const val ACTION_RAW            = "com.bazsoft.balanzabt.RAW"
        const val ACTION_DESVINCULADO   = "com.bazsoft.balanzabt.DESVINCULADO"

        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME    = "device_name"
        const val EXTRA_PESO           = "peso"
        const val EXTRA_ESTADO         = "estado"
        const val EXTRA_CODIGO         = "codigo"
        const val EXTRA_HORA           = "hora"
        const val EXTRA_ERROR          = "error"
        const val EXTRA_RAW            = "raw"

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceAddress: String? = null
    private var deviceName: String = "Balanza"
    private var isRunning = false
    private var intentosReconexion = 0
    private lateinit var prefs: SharedPreferences

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
        crearCanalNotificacion()
        adquirirWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, construirNotificacion("Iniciando...", "Balanza BT"))

        if (intent?.action == ACTION_SELECCIONAR_DISPOSITIVO) {
            deviceAddress      = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            deviceName         = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Balanza"
            isRunning          = true
            intentosReconexion = 0
            Thread { loopConexion() }.start()
        }
        return START_STICKY
    }

    private fun loopConexion() {
        while (isRunning) {
            try {
                intentosReconexion++
                actualizarNotificacion("Conectando a $deviceName...")
                conectarYLeer()
                // Si llegó aquí, se desconectó normalmente — resetear intentos
                intentosReconexion = 0
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                enviarBroadcast(ACTION_ERROR, error = "${e.message}")

                // Si lleva más de MAX_SEGUNDOS_DESCONECTADO sin conectar, borrar token
                val segundosDesconectado = intentosReconexion * 5L
                if (segundosDesconectado >= MAX_SEGUNDOS_DESCONECTADO) {
                    Log.w(TAG, "Desconectado por $segundosDesconectado seg — borrando token")
                    borrarToken()
                    actualizarNotificacion("Desvinculado por inactividad")
                    // Detener el servicio
                    stopSelf()
                    return
                }

                actualizarNotificacion("Reconectando en 5s (intento $intentosReconexion)...")
                Thread.sleep(5000)
            }
        }
    }

    private fun borrarToken() {
        prefs.edit()
            .remove("auth_token")
            .remove("usuario")
            .remove("base_url")
            .remove("token_tiempo")
            .apply()
        // Notificar a la UI para que muestre el mensaje
        enviarBroadcast(ACTION_DESVINCULADO)
    }

    private fun conectarYLeer() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)

        bluetoothSocket?.close()
        bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        bluetoothSocket!!.connect()

        // Conexión exitosa — resetear intentos
        intentosReconexion = 0
        Log.d(TAG, "Conectado a $deviceName")
        actualizarNotificacion("Conectado — enviando peso...")
        enviarBroadcast(ACTION_CONECTADO)

        val inputStream = bluetoothSocket!!.inputStream
        val buffer      = ByteArray(256)
        val acumulado   = StringBuilder()

        while (isRunning) {
            val bytes    = inputStream.read(buffer)
            val fragment = String(buffer, 0, bytes, Charsets.US_ASCII)

            val hex = buffer.take(bytes).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "RAW: [${fragment.replace("\r","\\r").replace("\n","\\n")}] HEX:[$hex]")
            enviarBroadcast(ACTION_RAW, raw = fragment.replace("\r","\\r").replace("\n","\\n"))

            acumulado.append(fragment)

            var idx: Int
            while (acumulado.indexOf("=").also { idx = it } >= 0) {
                val trama = acumulado.substring(0, idx)
                acumulado.delete(0, idx + 1)
                procesarTrama(trama)
            }

            if (acumulado.length > 200) {
                Log.w(TAG, "Buffer lleno: [${acumulado.toString().take(100)}]")
                acumulado.clear()
            }
        }
    }

    private fun procesarTrama(trama: String) {
        if (trama.length < 9) return

        val pesoReal   = trama.substring(0, 8).trim()
        val digControl = trama.substring(8, 9)
        val estado = when (digControl) {
            "B"  -> "Estable"
            "@"  -> "Inestable"
            "C"  -> "Cero Estable"
            "A"  -> "Cero Inestable"
            else -> "Desconocido ($digControl)"
        }
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        Log.d(TAG, "Peso=$pesoReal Estado=$estado")
        actualizarNotificacion("$pesoReal kg — $estado")

        enviarBroadcast(ACTION_PESO_RECIBIDO, pesoReal, estado, digControl, hora)
        enviarAlBackend(pesoReal, estado, digControl)
    }

    private fun enviarAlBackend(peso: String, estado: String, codigo: String) {
        val token   = prefs.getString("auth_token", null)
        val baseUrl = prefs.getString("base_url", null)

        if (token == null || baseUrl == null) {
            Log.w(TAG, "Sin token o baseUrl — no se envía al backend")
            return
        }

        val url  = "$baseUrl/api/balanza/peso"
        val json = """{"peso":"$peso","estado":"$estado","codigo":"$codigo"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder()
            .url(url)
            .addHeader("X-Balanza-Token", token)
            .post(body)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Backend OK: $peso — ${response.code}")
                // Si el backend rechaza el token, desvincularse
                if (response.code == 401) {
                    Log.w(TAG, "Token rechazado por el backend — borrando")
                    borrarToken()
                    stopSelf()
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Backend error: ${e.message}")
            }
        })
    }

    private fun enviarBroadcast(
        action: String,
        peso: String = "", estado: String = "", codigo: String = "",
        hora: String = "", error: String = "", raw: String = ""
    ) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_PESO,        peso)
            putExtra(EXTRA_ESTADO,      estado)
            putExtra(EXTRA_CODIGO,      codigo)
            putExtra(EXTRA_HORA,        hora)
            putExtra(EXTRA_ERROR,       error)
            putExtra(EXTRA_RAW,         raw)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun construirNotificacion(texto: String, titulo: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
        val channel = NotificationChannel(
            CHANNEL_ID, "Balanza Bluetooth", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Servicio de lectura de balanza en segundo plano" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
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
