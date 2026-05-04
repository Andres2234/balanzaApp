package com.bazsoft.balanzabt.model

import org.json.JSONObject

data class PatronBalanza(
    val id: String,
    val nombre: String,
    val terminador: String,      // "=", "\n", "\r\n", etc.
    val regex: String,           // expresión regular para extraer datos
    val grupoPeso: Int,          // número de grupo regex que contiene el peso
    val grupoEstado: Int,        // número de grupo regex que contiene el estado
    val codigoEstable: String,   // ej: "B" o "ST"
    val codigoInestable: String, // ej: "@" o "US"
    val codigoCeroEstable: String = "",
    val codigoCeroInestable: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nombre", nombre)
        put("terminador", terminador)
        put("regex", regex)
        put("grupoPeso", grupoPeso)
        put("grupoEstado", grupoEstado)
        put("codigoEstable", codigoEstable)
        put("codigoInestable", codigoInestable)
        put("codigoCeroEstable", codigoCeroEstable)
        put("codigoCeroInestable", codigoCeroInestable)
    }

    companion object {
        fun fromJson(json: JSONObject) = PatronBalanza(
            id                  = json.getString("id"),
            nombre              = json.getString("nombre"),
            terminador          = json.getString("terminador"),
            regex               = json.getString("regex"),
            grupoPeso           = json.getInt("grupoPeso"),
            grupoEstado         = json.getInt("grupoEstado"),
            codigoEstable       = json.getString("codigoEstable"),
            codigoInestable     = json.getString("codigoInestable"),
            codigoCeroEstable   = json.optString("codigoCeroEstable", ""),
            codigoCeroInestable = json.optString("codigoCeroInestable", "")
        )

        // Patrones predefinidos
        fun patronesDefecto() = listOf(
            // Patrón original: "  005.230B="
            PatronBalanza(
                id                  = "original",
                nombre              = "Balanza Original (B/@/C/A)",
                terminador          = "=",
                regex               = "^(.{8})(.)$",
                grupoPeso           = 1,
                grupoEstado         = 2,
                codigoEstable       = "B",
                codigoInestable     = "@",
                codigoCeroEstable   = "C",
                codigoCeroInestable = "A"
            ),
            // Patrón ST/US: "ST,G      0.10  kg"
            PatronBalanza(
                id                  = "stus",
                nombre              = "Balanza ST/US (ST,G / US,G)",
                terminador          = "\\n",
                regex               = "^(ST|US),\\w+\\s+([\\d.]+)\\s*kg",
                grupoPeso           = 2,
                grupoEstado         = 1,
                codigoEstable       = "ST",
                codigoInestable     = "US"
            )
        )
    }
}
