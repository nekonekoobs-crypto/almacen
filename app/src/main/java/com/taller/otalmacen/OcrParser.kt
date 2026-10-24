package com.taller.otalmacen

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot

object OcrParser {

    private class L(val text: String, val x: Int, val y: Int, val h: Int)

    /** Inclinación típica del texto en la foto (pendiente), para enderezar las alturas. */
    private fun pendiente(t: Text): Double {
        val lista = mutableListOf<Double>()
        for (b in t.textBlocks) {
            for (l in b.lines) {
                val p = l.cornerPoints
                if (p == null || p.size < 2 || l.text.length < 8) continue
                val dx = (p[1].x - p[0].x).toDouble()
                if (dx < 100) continue
                lista.add((p[1].y - p[0].y) / dx)
            }
        }
        if (lista.isEmpty()) return 0.0
        lista.sort()
        val m = lista[lista.size / 2]
        return if (m > 0.25 || m < -0.25) 0.0 else m
    }

    fun leer(context: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val mejorada: Bitmap? = try {
                Imagen.mejorar(context, uri)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                if (mejorada == null) {
                    leerOriginal(context, uri, onDone, onError)
                } else {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(mejorada, 0))
                        .addOnSuccessListener { t ->
                            val o = parse(t)
                            // Si con el filtro no se encontró nada, se prueba con la foto original
                            if (o.items.isEmpty()) leerOriginal(context, uri, onDone, onError) else onDone(o)
                        }
                        .addOnFailureListener { leerOriginal(context, uri, onDone, onError) }
                }
            }
        }.start()
    }

    private fun leerOriginal(context: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
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
        val k = pendiente(t)
        val lines = t.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val b = l.boundingBox
            if (b == null) {
                null
            } else {
                val cx = (b.left + b.right) / 2
                val p = l.cornerPoints
                val alto = if (p != null && p.size >= 4) {
                    hypot((p[3].x - p[0].x).toDouble(), (p[3].y - p[0].y).toDouble()).toInt()
                } else {
                    b.height()
                }
                L(l.text.trim(), b.left, (b.centerY() - k * cx).toInt(), alto)
            }
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

        val altos = body.filter { it.text.length >= 6 }.map { it.h }.sorted()
        val altura = if (altos.isEmpty()) 30 else altos[altos.size / 2]

        val items = mutableListOf<Item>()
        val base = System.currentTimeMillis()
        var ultX = 0
        var ultY = Int.MIN_VALUE
        var continuo = false
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
                    .filter { it.x < l.x && abs(it.y - l.y) < altura * 0.7 && l.x - it.x < altura * 3.5 }
                    .maxByOrNull { it.x }
                if (q != null) pz = q.text.replace(" ", "")
            }
            if (pz != null) {
                items.add(Item(base + items.size, pz, texto.trim()))
                ultX = l.x
                ultY = l.y
                continuo = false
            } else {
                val dy = l.y - ultY
                val esContinuacion = items.isNotEmpty() && !continuo &&
                    dy > 0 && dy < altura * 1.25 && abs(l.x - ultX) < 50
                if (esContinuacion) {
                    val last = items.removeAt(items.size - 1)
                    items.add(last.copy(concepto = (last.concepto + " " + texto).trim()))
                    ultY = l.y
                    continuo = true
                } else if (texto.length >= 4) {
                    items.add(Item(base + items.size, "1", texto.trim()))
                    ultX = l.x
                    ultY = l.y
                    continuo = false
                }
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
