package com.taller.otalmacen

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

object OcrParser {

    private class L(val text: String, val x: Int, val y: Int, val h: Int)

    fun leer(context: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            onError(e.message ?: "No se pudo abrir la imagen")
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { t -> onDone(parse(t)) }
            .addOnFailureListener { e -> onError(e.message ?: "Error de lectura") }
    }

    private fun primero(regex: Regex, texto: String): String {
        val m = regex.find(texto) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun parse(t: Text): Orden {
        val lines = t.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val b = l.boundingBox
            if (b == null) null else L(l.text.trim(), b.left, b.centerY(), b.height())
        }
        val completo = t.text.uppercase()
        val cabeza = if (completo.contains("CONCEPTO")) completo.substringBefore("CONCEPTO") else completo

        val etiquetas = listOf("ELABORACION", "ELABORACIÓN", "ENTREGA", "SEGURO", "MODELO", "VIN")
        var seguro = primero(Regex("SEGURO\\s*:?\\s*(\\p{L}{3,})"), cabeza)
        if (etiquetas.contains(seguro)) seguro = ""
        val elaboracion = primero(Regex("ELABORACI.N\\s*:?\\s*(\\S+)"), cabeza)
        val entrega = primero(Regex("ENTREGA\\s*:?\\s*(\\S+)"), cabeza)
        val ot = primero(Regex("\\bOT\\.?\\s*(\\d{3,6})"), cabeza)
        val modelo = primero(Regex("MODELO\\s*:?\\s*(\\d{4})"), cabeza)
        val vinCrudo = Regex("[A-Z0-9]{17}").find(cabeza.replace(" ", ""))?.value ?: ""
        val vin = vinCrudo.replace('O', '0').replace('I', '1').replace('Q', '0')

        val ancla = lines.firstOrNull { it.text.uppercase().contains("CONCEPTO") }
        val headerY = ancla?.y ?: Int.MIN_VALUE
        val cabLineas = lines.filter { it.y < headerY }

        val unidadRegex = Regex("^\\p{L}{3,}(\\s\\p{L}+)*\\s+[A-Z]?\\d{2,4}[A-Z]*$")
        val unidad = cabLineas.firstOrNull {
            !it.text.contains(":") && unidadRegex.matches(it.text.uppercase())
        }?.text ?: ""

        val prohibidas = listOf("ELABORACION", "ENTREGA", "SEGURO", "MODELO", "VIN", "TRAZO")
        val taller = cabLineas
            .filter { l ->
                val u = l.text.uppercase()
                u.length >= 4 && u.all { it.isLetter() } && prohibidas.none { u.contains(it) }
            }
            .maxByOrNull { it.x }?.text ?: ""

        val body = lines.filter { it.y > headerY + (ancla?.h ?: 0) / 2 }.sortedBy { it.y }
        val soloNum = Regex("^\\d{1,2}$")
        val conQty = Regex("^(\\d{1,2})\\s+(\\p{L}.*)$")
        val numeros = body.filter { soloNum.matches(it.text.replace(" ", "")) }

        val items = mutableListOf<Item>()
        val base = System.currentTimeMillis()
        var ultX = 0
        var ultY = Int.MIN_VALUE
        for (l in body) {
            val up = l.text.trim()
            val u = up.uppercase()
            if (!up.any { it.isLetter() }) continue
            if (u.contains("PROVEEDOR") || u.contains("COSTOS") || u.contains("CONCEPTO")) continue
            var texto = up.replaceFirst(Regex("^\\d{1,2}[.,]\\s+"), "")
            var pz: String? = null
            val m = conQty.find(texto)
            if (m != null) {
                pz = m.groupValues[1]
                texto = m.groupValues[2]
            } else {
                val q = numeros
                    .filter { it.x < l.x && abs(it.y - l.y) < l.h * 0.9 && l.x - it.x < 300 }
                    .maxByOrNull { it.x }
                if (q != null) pz = q.text.replace(" ", "")
            }
            if (pz != null) {
                items.add(Item(base + items.size, pz, texto.trim()))
                ultX = l.x
                ultY = l.y
            } else if (items.isNotEmpty() && l.y - ultY < l.h * 2.4 && abs(l.x - ultX) < 60) {
                val last = items.removeAt(items.size - 1)
                items.add(last.copy(concepto = (last.concepto + " " + texto).trim()))
                ultY = l.y
            }
        }

        return Orden(
            id = base,
            seguro = seguro,
            elaboracion = elaboracion,
            entrega = entrega,
            ot = ot,
            unidad = unidad,
            vin = vin,
            modelo = modelo,
            taller = taller,
            items = items
        )
    }
}
