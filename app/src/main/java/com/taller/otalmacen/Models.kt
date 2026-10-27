package com.taller.otalmacen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(
    val id: Long,
    val pz: String,
    val concepto: String,
    val proveedor: String = "",
    val alta: Boolean = false,
    val baja: Boolean = false,
    val fotoBaja: String = "",
    val prestamo: Boolean = false,
    val devolucion: Boolean = false,
    val sobrante: Boolean = false,
    val descPrestamo: String = "",
    val fotoPrestamo: String = ""
)

data class Orden(
    val id: Long,
    val seguro: String = "",
    val elaboracion: String = "",
    val entrega: String = "",
    val ot: String = "",
    val unidad: String = "",
    val vin: String = "",
    val modelo: String = "",
    val taller: String = "",
    val jefeTaller: String = "",
    val items: List<Item> = emptyList()
)

object Store {
    private fun dir(base: File): File = File(base, "ordenes").also { it.mkdirs() }

    fun save(base: File, o: Orden) {
        File(dir(base), "${o.id}.json").writeText(toJson(o).toString())
    }

    fun delete(base: File, id: Long) {
        File(dir(base), "$id.json").delete()
    }

    fun loadAll(base: File): List<Orden> {
        val files = dir(base).listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.id }
    }

    private fun toJson(o: Orden): JSONObject {
        val arr = JSONArray()
        for (i in o.items) {
            arr.put(
                JSONObject()
                    .put("id", i.id)
                    .put("pz", i.pz)
                    .put("concepto", i.concepto)
                    .put("proveedor", i.proveedor)
                    .put("alta", i.alta)
                    .put("baja", i.baja)
                    .put("fotoBaja", i.fotoBaja)
                    .put("prestamo", i.prestamo)
                    .put("devolucion", i.devolucion)
                    .put("sobrante", i.sobrante)
                    .put("descPrestamo", i.descPrestamo)
                    .put("fotoPrestamo", i.fotoPrestamo)
            )
        }
        return JSONObject()
            .put("id", o.id)
            .put("seguro", o.seguro)
            .put("elaboracion", o.elaboracion)
            .put("entrega", o.entrega)
            .put("ot", o.ot)
            .put("unidad", o.unidad)
            .put("vin", o.vin)
            .put("modelo", o.modelo)
            .put("taller", o.taller)
            .put("jefeTaller", o.jefeTaller)
            .put("items", arr)
    }

    private fun fromJson(j: JSONObject): Orden {
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { n ->
            val x = arr.getJSONObject(n)
            Item(
                id = x.optLong("id", n.toLong()),
                pz = x.optString("pz"),
                concepto = x.optString("concepto"),
                proveedor = x.optString("proveedor"),
                alta = x.optBoolean("alta"),
                baja = x.optBoolean("baja"),
                fotoBaja = x.optString("fotoBaja"),
                prestamo = x.optBoolean("prestamo"),
                devolucion = x.optBoolean("devolucion"),
                sobrante = x.optBoolean("sobrante"),
                descPrestamo = x.optString("descPrestamo"),
                fotoPrestamo = x.optString("fotoPrestamo")
            )
        }
        return Orden(
            id = j.getLong("id"),
            seguro = j.optString("seguro"),
            elaboracion = j.optString("elaboracion"),
            entrega = j.optString("entrega"),
            ot = j.optString("ot"),
            unidad = j.optString("unidad"),
            vin = j.optString("vin"),
            modelo = j.optString("modelo"),
            taller = j.optString("taller"),
            jefeTaller = j.optString("jefeTaller"),
            items = items
        )
    }
}

/** Busca en todos los campos del encabezado; cada palabra escrita debe aparecer en alguno. */
fun Orden.coincide(q: String): Boolean {
    val texto = listOf(ot, seguro, elaboracion, entrega, unidad, vin, modelo, taller, jefeTaller).joinToString(" ")
    return q.split(" ").filter { it.isNotBlank() }.all { texto.contains(it, ignoreCase = true) }
}

/** Una pieza solo puede estar en un estado a la vez: alta, baja, préstamo, devolución o sobrante. */
fun Item.conEstado(estado: String, activo: Boolean): Item {
    val limpio = if (activo) {
        copy(alta = false, baja = false, prestamo = false, devolucion = false, sobrante = false)
    } else {
        this
    }
    return when (estado) {
        "alta" -> limpio.copy(alta = activo)
        "baja" -> limpio.copy(baja = activo)
        "prestamo" -> limpio.copy(prestamo = activo)
        "devolucion" -> limpio.copy(devolucion = activo)
        else -> limpio.copy(sobrante = activo)
    }
}
