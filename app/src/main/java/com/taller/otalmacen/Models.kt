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
    val baja: Boolean = false
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
                baja = x.optBoolean("baja")
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
            items = items
        )
    }
}
