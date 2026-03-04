package com.bazsoft.balanzabt.service

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class BalanzaWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val TAG = "BalanzaWS"
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Cliente conectado: ${conn.remoteSocketAddress}")
        conn.send("""{"tipo":"conectado","mensaje":"Balanza BT WebSocket activo"}""")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Cliente desconectado: $reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // La web puede enviar "ping" para verificar conexión
        if (message == "ping") conn.send("""{"tipo":"pong"}""")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Error WebSocket: ${ex.message}")
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server iniciado en puerto ${port}")
        connectionLostTimeout = 0
    }

    // Enviar peso a TODOS los clientes web conectados
    fun enviarPeso(peso: String, estado: String, codigo: String, hora: String) {
        val json = """{"tipo":"peso","peso":"$peso","estado":"$estado","codigo":"$codigo","hora":"$hora"}"""
        broadcast(json)
        Log.d(TAG, "Enviado a ${connections.size} cliente(s): $json")
    }
}
