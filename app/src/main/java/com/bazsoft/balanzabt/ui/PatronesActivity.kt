package com.bazsoft.balanzabt.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bazsoft.balanzabt.R
import com.bazsoft.balanzabt.model.PatronBalanza
import com.bazsoft.balanzabt.model.PatronService
import java.util.UUID

class PatronesActivity : AppCompatActivity() {

    private lateinit var patronService: PatronService
    private lateinit var listView: ListView
    private lateinit var btnNuevo: android.widget.Button
    private var patrones = mutableListOf<PatronBalanza>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patrones)

        supportActionBar?.title = "Patrones de Balanza"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        patronService = PatronService(this)
        listView      = findViewById(R.id.listPatrones)
        btnNuevo      = findViewById(R.id.btnNuevoPatron)

        btnNuevo.setOnClickListener { mostrarDialogoPatron(null) }

        cargarPatrones()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun cargarPatrones() {
        patrones = patronService.obtenerPatrones()
        val activoId = patronService.obtenerPatronActivo().id
        actualizarLista(activoId)
    }

    private fun actualizarLista(activoId: String) {
        val adapter = object : ArrayAdapter<PatronBalanza>(
            this, android.R.layout.simple_list_item_1, patrones
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val patron = patrones[position]
                val view   = layoutInflater.inflate(R.layout.item_patron, parent, false)

                view.findViewById<TextView>(R.id.tvNombrePatron).text = patron.nombre
                view.findViewById<TextView>(R.id.tvDetallePatron).text =
                    "Terminador: ${patron.terminador} | Estable: ${patron.codigoEstable} | Inestable: ${patron.codigoInestable}"

                val radioActivo = view.findViewById<RadioButton>(R.id.radioActivo)
                radioActivo.isChecked = patron.id == activoId
                radioActivo.setOnClickListener {
                    patronService.setPatronActivo(patron.id)
                    actualizarLista(patron.id)
                    Toast.makeText(this@PatronesActivity, "Patrón activo: ${patron.nombre}", Toast.LENGTH_SHORT).show()
                }

                view.findViewById<android.widget.Button>(R.id.btnEditarPatron)
                    .setOnClickListener { mostrarDialogoPatron(patron) }

                val btnEliminar = view.findViewById<android.widget.Button>(R.id.btnEliminarPatron)
                btnEliminar.isEnabled = patron.id != "original" && patron.id != "stus"
                btnEliminar.setOnClickListener {
                    AlertDialog.Builder(this@PatronesActivity)
                        .setTitle("Eliminar patrón")
                        .setMessage("¿Eliminar '${patron.nombre}'?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            patrones.removeAt(position)
                            patronService.guardarPatrones(patrones)
                            if (patron.id == activoId) patronService.setPatronActivo("original")
                            cargarPatrones()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }

                view.findViewById<android.widget.Button>(R.id.btnProbarPatron)
                    .setOnClickListener { mostrarDialogoPrueba(patron) }

                return view
            }
        }
        listView.adapter = adapter
    }

    private fun mostrarDialogoPatron(patronExistente: PatronBalanza?) {
        val esNuevo = patronExistente == null
        val view    = layoutInflater.inflate(R.layout.dialog_patron, null)

        val etNombre    = view.findViewById<EditText>(R.id.etNombre)
        val etTerminador = view.findViewById<EditText>(R.id.etTerminador)
        val etRegex     = view.findViewById<EditText>(R.id.etRegex)
        val etGrupoPeso = view.findViewById<EditText>(R.id.etGrupoPeso)
        val etGrupoEstado = view.findViewById<EditText>(R.id.etGrupoEstado)
        val etEstable   = view.findViewById<EditText>(R.id.etEstable)
        val etInestable = view.findViewById<EditText>(R.id.etInestable)
        val etCeroEst   = view.findViewById<EditText>(R.id.etCeroEstable)
        val etCeroInest = view.findViewById<EditText>(R.id.etCeroInestable)
        val tvAyuda     = view.findViewById<TextView>(R.id.tvAyudaRegex)

        // Ejemplos de ayuda
        tvAyuda.text = "Ejemplos:\n" +
            "Balanza Original:  ^(.{8})(.)$  grupos: peso=1, estado=2\n" +
            "Balanza ST/US:     ^(ST|US),\\w+\\s+([\\d.]+)\\s*kg  grupos: peso=2, estado=1"

        if (patronExistente != null) {
            etNombre.setText(patronExistente.nombre)
            etTerminador.setText(patronExistente.terminador)
            etRegex.setText(patronExistente.regex)
            etGrupoPeso.setText(patronExistente.grupoPeso.toString())
            etGrupoEstado.setText(patronExistente.grupoEstado.toString())
            etEstable.setText(patronExistente.codigoEstable)
            etInestable.setText(patronExistente.codigoInestable)
            etCeroEst.setText(patronExistente.codigoCeroEstable)
            etCeroInest.setText(patronExistente.codigoCeroInestable)
        }

        AlertDialog.Builder(this)
            .setTitle(if (esNuevo) "Nuevo Patrón" else "Editar Patrón")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = etNombre.text.toString().trim()
                val regex  = etRegex.text.toString().trim()

                if (nombre.isEmpty() || regex.isEmpty()) {
                    Toast.makeText(this, "Nombre y Regex son obligatorios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Validar que el regex sea válido
                try { Regex(regex) } catch (e: Exception) {
                    Toast.makeText(this, "Regex inválido: ${e.message}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val nuevo = PatronBalanza(
                    id                  = patronExistente?.id ?: UUID.randomUUID().toString(),
                    nombre              = nombre,
                    terminador          = etTerminador.text.toString().ifEmpty { "\\n" },
                    regex               = regex,
                    grupoPeso           = etGrupoPeso.text.toString().toIntOrNull() ?: 1,
                    grupoEstado         = etGrupoEstado.text.toString().toIntOrNull() ?: 2,
                    codigoEstable       = etEstable.text.toString().trim(),
                    codigoInestable     = etInestable.text.toString().trim(),
                    codigoCeroEstable   = etCeroEst.text.toString().trim(),
                    codigoCeroInestable = etCeroInest.text.toString().trim()
                )

                if (esNuevo) patrones.add(nuevo)
                else {
                    val idx = patrones.indexOfFirst { it.id == patronExistente!!.id }
                    if (idx >= 0) patrones[idx] = nuevo
                }

                patronService.guardarPatrones(patrones)
                cargarPatrones()
                Toast.makeText(this, "Patrón guardado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoPrueba(patron: PatronBalanza) {
        val view = layoutInflater.inflate(R.layout.dialog_prueba_patron, null)
        val etTrama   = view.findViewById<EditText>(R.id.etTramaPrueba)
        val tvResultado = view.findViewById<TextView>(R.id.tvResultadoPrueba)
        val btnProbar = view.findViewById<android.widget.Button>(R.id.btnEjecutarPrueba)

        // Poner trama de ejemplo según el patrón
        etTrama.hint = when (patron.id) {
            "original" -> "Ej:   005.230B"
            "stus"     -> "Ej: ST,G      0.10  kg"
            else       -> "Ingresa una trama de ejemplo"
        }

        btnProbar.setOnClickListener {
            val trama     = etTrama.text.toString()
            val resultado = patronService.probarPatron(patron, trama)
            tvResultado.text      = resultado.mensaje
            tvResultado.setTextColor(
                if (resultado.exitoso)
                    android.graphics.Color.parseColor("#28a745")
                else
                    android.graphics.Color.parseColor("#dc3545")
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Probar Patrón: ${patron.nombre}")
            .setView(view)
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
