package com.bazsoft.balanzabt.model

import android.content.Context
import org.json.JSONArray

class PatronService(private val context: Context) {

    private val prefs = context.getSharedPreferences("BalanzaPrefs", Context.MODE_PRIVATE)

    fun obtenerPatrones(): MutableList<PatronBalanza> {
        val json = prefs.getString("patrones", null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { PatronBalanza.fromJson(arr.getJSONObject(it)) }.toMutableList()
            } catch (e: Exception) {
                PatronBalanza.patronesDefecto().toMutableList()
            }
        }
        // Primera vez — guardar patrones por defecto
        val defecto = PatronBalanza.patronesDefecto().toMutableList()
        guardarPatrones(defecto)
        return defecto
    }

    fun guardarPatrones(patrones: List<PatronBalanza>) {
        val arr = JSONArray()
        patrones.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("patrones", arr.toString()).apply()
    }

    fun obtenerPatronActivo(): PatronBalanza {
        val id      = prefs.getString("patron_activo_id", "original") ?: "original"
        val patrones = obtenerPatrones()
        return patrones.firstOrNull { it.id == id } ?: patrones.first()
    }

    fun setPatronActivo(id: String) {
        prefs.edit().putString("patron_activo_id", id).apply()
    }

    fun probarPatron(patron: PatronBalanza, trama: String): ResultadoPrueba {
        return try {
            val regex   = Regex(patron.regex)
            val match   = regex.find(trama.trim())
                ?: return ResultadoPrueba(false, "No coincide con la trama ingresada")

            val peso    = match.groupValues.getOrNull(patron.grupoPeso)?.trim()
                ?: return ResultadoPrueba(false, "Grupo de peso (${patron.grupoPeso}) no encontrado")

            val estado  = match.groupValues.getOrNull(patron.grupoEstado)?.trim()
                ?: return ResultadoPrueba(false, "Grupo de estado (${patron.grupoEstado}) no encontrado")

            val estadoTexto = when (estado) {
                patron.codigoEstable       -> "Estable"
                patron.codigoInestable     -> "Inestable"
                patron.codigoCeroEstable   -> "Cero Estable"
                patron.codigoCeroInestable -> "Cero Inestable"
                else                       -> "Desconocido ($estado)"
            }

            ResultadoPrueba(true, "✅ Peso: $peso | Estado: $estadoTexto")
        } catch (e: Exception) {
            ResultadoPrueba(false, "Error en regex: ${e.message}")
        }
    }
}

data class ResultadoPrueba(val exitoso: Boolean, val mensaje: String)
