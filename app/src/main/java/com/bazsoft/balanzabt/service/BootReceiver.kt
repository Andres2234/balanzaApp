package com.bazsoft.balanzabt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Android reiniciado, verificando configuración guardada...")

            val prefs = context.getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)
            val address = prefs.getString("last_device_address", null)
            val name    = prefs.getString("last_device_name", "Balanza")

            if (address != null) {
                Log.d("BootReceiver", "Reconectando a: $name ($address)")
                val serviceIntent = Intent(context, BalanzaForegroundService::class.java).apply {
                    action = BalanzaForegroundService.ACTION_SELECCIONAR_DISPOSITIVO
                    putExtra(BalanzaForegroundService.EXTRA_DEVICE_ADDRESS, address)
                    putExtra(BalanzaForegroundService.EXTRA_DEVICE_NAME, name)
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
