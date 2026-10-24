package com.taller.otalmacen

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot

object OcrParser {

    private class L(val text: String, val x: Int, val cx: Int, val y: Int, val h: Int)

    private val ETIQUETAS = listOf("SEGURO", "ELABORACI", "ENTREGA", "MODELO", "VIN", "TRAZO")
    private val MARCAS = listOf(
        "KENWORTH", "FREIGHTLINER", "VOLVO", "INTERNATIONAL", "PETERBILT", "MACK", "SCANIA",
        "MERCEDES", "HINO", "ISUZU", "FORD", "CHEVROLET", "DODGE", "NISSAN", "TOYOTA",
        "FOTON", "JAC", "IVECO", "DINA", "MAN", "KW"
    )
    private val MARCA_RX = Regex("\\b(" + MARCAS.joinToString("|") + ")\\b")
    private val FECHA = Regex("\\d{1,2}\\s*[/\\-.]\\s*(?:\\d{1,2}|[A-ZÁÉÍÓÚ]{3,10})\\s*[/\\-.]\\s*\\d{2,4}")

    // ---------- Lectura ----------
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
                    leerOriginal(context, uri, null, onDone, onError)
                } else {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(mejorada, 0))
                        .addOnSuccessListener { t ->
                            val o = parse(t)
                            // Si falta algo importante se lee también la foto original y se completan los huecos
                            if (completo(o)) onDone(o) else leerOriginal(context, uri, o, onDone, onError)
                        }
                        .addOnFailureListener { leerOriginal(context, uri, null, onDone, onError) }
                }
            }
        }.start()
    }

    private fun completo(o: Orden): Boolean =
        o.items.isNotEmpty() && o.ot.isNotBlank() && o.unidad.isNotBlank() && o.modelo.isNotBlank()

    private fun leerOriginal(
        context: Context,
        uri: Uri,
        previo: Orden?,
        onDone: (Orden) -> Unit,
        onError: (String) -> Unit
    ) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            if (previo != null) onDone(previo) else onError(e.message ?: "No se pudo abrir la imagen")
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { t ->
                val o = parse(t)
                onDone(if (previo == null) o else combinar(previo, o))
            }
            .addOnFailureListener { e ->
                if (previo != null) onDone(previo) else onError(e.message ?: "Error de lectura")
            }
    }

    private fun combinar(a: Orden, b: Orden): Orden = a.copy(
        seguro = a.seguro.ifBlank { b.seguro },
        elaboracion = a.elaboracion.ifBlank { b.elaboracion },
        entrega = a.entrega.ifBlank { b.entrega },
        ot = a.ot.ifBlank { b.ot },
        unidad = a.unidad.ifBlank { b.unidad },
        vin = a.vin.ifBlank { b.vin },
        modelo = a.modelo.ifBlank { b.modelo },
        taller = a.taller.ifBlank { b.taller },
        items = if (a.items.isNotEmpty()) a.items else b.items
    )

    // ---------- Utilidades ----------
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

    private fun esEtiqueta(s: String): Boolean {
        val u = s.uppercase()
        return ETIQUETAS.any { u.contains(it) }
    }

    /**
     * Busca el valor de una etiqueta: primero en la misma línea ("SEGURO: MAFRE"),
     * y si no, la línea más cercana a la derecha o justo debajo ("ELABORACION" / "20/10/2026").
     */
    private fun buscar(cab: List<L>, etiqueta: Regex, valor: Regex, mismaFila: Boolean = false): String {
        val lab = cab.firstOrNull { etiqueta.containsMatchIn(it.text.uppercase()) } ?: return ""
        val mismo = Regex(etiqueta.pattern + "\\W{0,4}(" + valor.pattern + ")").find(lab.text.uppercase())
        if (mismo != null) return mismo.groupValues[1]
        var mejor = ""
        var mejorD = Double.MAX_VALUE
        for (l in cab) {
            if (l === lab) continue
            if (esEtiqueta(l.text)) continue
            val txt = l.text.uppercase().trim()
            // El valor debe ser la línea completa: así nunca se toma un pedazo de otro texto
            if (!valor.matches(txt)) continue
            val dy = (l.y - lab.y).toDouble()
            val dx = (l.cx - lab.cx).toDouble()
            val d = if (abs(dy) < lab.h * 0.7 && dx > 0) {
                dx
            } else if (!mismaFila && dy > 0 && dy < lab.h * 4) {
                dy * 1.5 + abs(dx)
            } else {
                continue
            }
            if (d < mejorD) {
                mejorD = d
                mejor = txt
            }
        }
        return mejor
    }

    // ---------- Interpretación ----------
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
                L(l.text.trim(), b.left, cx, (b.centerY() - k * cx).toInt(), alto)
            }
        }

        if (lines.isEmpty()) return Orden(id = System.currentTimeMillis())

        // El encabezado termina donde empieza la tabla: títulos PZ / CONCEPTO / PROVEEDOR / COSTOS
        val titulos = Regex("CONC|PROVEED|COSTO")
        val soloPz = Regex("^PZS?\\W*$")
        val ancla = lines
            .filter {
                val u = it.text.uppercase().trim()
                titulos.containsMatchIn(u) || soloPz.matches(u)
            }
            .minByOrNull { it.y }
        // Si no se leyeron los títulos, el encabezado termina en la primera pieza (renglón con cantidad)
        val primeraPieza = lines
            .filter { Regex("^\\d{1,2}(\\s+\\p{L}.*)?$").matches(it.text.trim()) }
            .minByOrNull { it.y }
        val headerY = when {
            ancla != null -> ancla.y
            primeraPieza != null -> primeraPieza.y - primeraPieza.h
            else -> {
                val mn = lines.minOf { it.y }
                val mx = lines.maxOf { it.y }
                mn + ((mx - mn) * 0.10).toInt()
            }
        }
        val cab = lines.filter { it.y < headerY }

        // Seguro
        var seguro = buscar(cab, Regex("SEGURO"), Regex("\\p{L}{3,}"), true)
        if (esEtiqueta(seguro)) seguro = ""

        // Fechas
        var elaboracion = buscar(cab, Regex("ELABORACI.N"), FECHA)
        var entrega = buscar(cab, Regex("ENTREGA"), FECHA)
        if (elaboracion.isBlank() || entrega.isBlank()) {
            val fechas = cab.sortedBy { it.cx }
                .mapNotNull { FECHA.find(it.text.uppercase())?.value }
            if (elaboracion.isBlank() && fechas.isNotEmpty()) elaboracion = fechas[0]
            if (entrega.isBlank() && fechas.size > 1) entrega = fechas[1]
        }

        // OT
        var ot = buscar(cab, Regex("\\b[O0][T7]\\b"), Regex("\\d{3,6}"), true)
        if (ot.isBlank()) {
            ot = cab.firstNotNullOfOrNull {
                Regex("^[O0][T7]\\W*(\\d{3,6})$").find(it.text.uppercase().trim())?.groupValues?.get(1)
            } ?: ""
        }

        // Modelo (año)
        var modelo = buscar(cab, Regex("MODEL[O0]"), Regex("(?:19|20)\\d{2}"), true)
        if (modelo.isBlank()) {
            modelo = cab.firstNotNullOfOrNull {
                Regex("^((?:19|20)\\d{2})$").find(it.text.trim())?.groupValues?.get(1)
            } ?: ""
        }

        // VIN: 17 caracteres con letras y números
        var vin = ""
        for (l in cab) {
            val s = l.text.uppercase().replace(" ", "")
            val m = Regex("[A-Z0-9]{17}").find(s) ?: continue
            val v = m.value
            if (v.count { it.isDigit() } >= 3 && v.count { it.isLetter() } >= 3) {
                vin = v
                break
            }
        }
        vin = vin.replace('O', '0').replace('I', '1').replace('Q', '0')

        // Unidad: línea con marca conocida o letras + números (ej. KENWORTH T880)
        fun candidata(l: L): Boolean {
            val u = l.text.uppercase().trim()
            if (u.length < 4) return false
            if (esEtiqueta(u)) return false
            if (FECHA.containsMatchIn(u)) return false
            if (u.replace(" ", "").length >= 15) return false
            if (MARCA_RX.containsMatchIn(u)) return true
            return u.count { it.isLetter() } >= 3 && u.any { it.isDigit() }
        }
        val candidatas = cab.filter { candidata(it) }
        val unidad = (candidatas.firstOrNull { MARCA_RX.containsMatchIn(it.text.uppercase()) }
            ?: candidatas.minWithOrNull(compareBy({ it.y }, { it.x })))?.text ?: ""

        // Taller: palabra suelta (solo letras) más a la derecha del encabezado
        val taller = cab.filter { l ->
            val u = l.text.uppercase().trim()
            u.length >= 4 && u.all { it.isLetter() } && !esEtiqueta(u) &&
                u != seguro.uppercase() && !MARCA_RX.containsMatchIn(u)
        }.maxByOrNull { it.cx }?.text ?: ""

        // ---------- Piezas ----------
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
            elaboracion = normalizarFecha(elaboracion),
            entrega = normalizarFecha(entrega),
            ot = ot,
            unidad = unidad,
            vin = vin,
            modelo = modelo,
            taller = taller,
            items = items
        )
    }
}
