package com.taller.otalmacen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

private val VERDE = Color(0xFFA5D6A7)
private val ROJO = Color(0xFFEF9A9A)
private val GRIS = Color(0xFFF1F1F1)

@Composable
fun App() {
    val ctx = LocalContext.current
    val base = ctx.filesDir
    var ordenes by remember { mutableStateOf(Store.loadAll(base)) }
    var actual by remember { mutableStateOf<Orden?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }

    val fotoFile = remember {
        val d = File(ctx.cacheDir, "fotos")
        d.mkdirs()
        File(d, "foto.jpg")
    }
    val fotoUri = remember {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", fotoFile)
    }

    fun procesar(uri: Uri) {
        cargando = true
        mensaje = null
        OcrParser.leer(ctx, uri,
            { o ->
                cargando = false
                Store.save(base, o)
                ordenes = Store.loadAll(base)
                actual = o
            },
            { err ->
                cargando = false
                mensaje = "No se pudo leer la foto: $err"
            })
    }

    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) procesar(fotoUri)
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) procesar(uri)
    }

    BackHandler(enabled = actual != null) {
        actual = null
        ordenes = Store.loadAll(base)
    }

    val o = actual
    if (o == null) {
        ListaScreen(
            ordenes = ordenes,
            cargando = cargando,
            mensaje = mensaje,
            onFoto = { camara.launch(fotoUri) },
            onGaleria = { galeria.launch("image/*") },
            onNueva = {
                val nueva = Orden(id = System.currentTimeMillis())
                Store.save(base, nueva)
                ordenes = Store.loadAll(base)
                actual = nueva
            },
            onAbrir = { actual = it },
            onBorrar = {
                Store.delete(base, it.id)
                ordenes = Store.loadAll(base)
            }
        )
    } else {
        DetalleScreen(
            orden = o,
            onCambio = { n ->
                Store.save(base, n)
                actual = n
            },
            onVolver = {
                actual = null
                ordenes = Store.loadAll(base)
            },
            onExportar = { exportar(ctx, o) }
        )
    }
}

fun exportar(ctx: Context, o: Orden) {
    val dir = File(ctx.cacheDir, "export")
    dir.mkdirs()
    val nombre = "OT_" + o.ot.ifBlank { "sin_numero" }.replace(Regex("[^A-Za-z0-9]"), "_") + "_almacen.xlsx"
    val f = File(dir, nombre)
    XlsxWriter.escribir(o, f)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Compartir Excel"))
}

@Composable
fun ListaScreen(
    ordenes: List<Orden>,
    cargando: Boolean,
    mensaje: String?,
    onFoto: () -> Unit,
    onGaleria: () -> Unit,
    onNueva: () -> Unit,
    onAbrir: (Orden) -> Unit,
    onBorrar: (Orden) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(28.dp))
                Text("Órdenes de trabajo", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Button(onClick = onFoto, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Tomar foto de la OT", fontSize = 18.sp)
            }
        }
        item {
            OutlinedButton(onClick = onGaleria, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Elegir foto de la galería")
            }
        }
        item {
            OutlinedButton(onClick = onNueva, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Nueva OT vacía")
            }
        }
        if (cargando) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("Leyendo la foto…")
                }
            }
        }
        if (mensaje != null) {
            item { Text(mensaje, color = Color(0xFFB00020)) }
        }
        items(ordenes, key = { it.id }) { o ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = GRIS, contentColor = Color.Black)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("OT ${o.ot.ifBlank { "(sin número)" }}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${o.unidad} · ${o.seguro} · ${o.items.size} piezas")
                    Row {
                        TextButton(onClick = { onAbrir(o) }) { Text("Abrir") }
                        TextButton(onClick = { onBorrar(o) }) { Text("Borrar") }
                    }
                }
            }
        }
    }
}

private class Campo(val label: String, val valor: String, val set: (String) -> Orden)

@Composable
fun DetalleScreen(
    orden: Orden,
    onCambio: (Orden) -> Unit,
    onVolver: () -> Unit,
    onExportar: () -> Unit
) {
    val campos = listOf(
        Campo("Seguro", orden.seguro) { v -> orden.copy(seguro = v) },
        Campo("Elaboración", orden.elaboracion) { v -> orden.copy(elaboracion = v) },
        Campo("Entrega", orden.entrega) { v -> orden.copy(entrega = v) },
        Campo("OT", orden.ot) { v -> orden.copy(ot = v) },
        Campo("Unidad", orden.unidad) { v -> orden.copy(unidad = v) },
        Campo("VIN", orden.vin) { v -> orden.copy(vin = v.uppercase()) },
        Campo("Modelo", orden.modelo) { v -> orden.copy(modelo = v) },
        Campo("Taller", orden.taller) { v -> orden.copy(taller = v) }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onVolver) { Text("← Volver") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onExportar) { Text("Exportar Excel") }
                }
            }
        }
        items(campos) { c ->
            OutlinedTextField(
                value = c.valor,
                onValueChange = { v -> onCambio(c.set(v)) },
                label = { Text(c.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text("Piezas (${orden.items.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        items(orden.items, key = { it.id }) { p ->
            FilaPieza(
                p = p,
                onChange = { n ->
                    onCambio(orden.copy(items = orden.items.map { x -> if (x.id == n.id) n else x }))
                },
                onBorrar = {
                    onCambio(orden.copy(items = orden.items.filter { x -> x.id != p.id }))
                }
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    val nuevo = Item(id = System.currentTimeMillis(), pz = "1", concepto = "")
                    onCambio(orden.copy(items = orden.items + nuevo))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Agregar pieza") }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
fun FilaPieza(p: Item, onChange: (Item) -> Unit, onBorrar: () -> Unit) {
    val fondo = when {
        p.alta -> VERDE
        p.baja -> ROJO
        else -> GRIS
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = Color.Black)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = p.pz,
                    onValueChange = { v -> onChange(p.copy(pz = v)) },
                    label = { Text("PZ") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp)
                )
                OutlinedTextField(
                    value = p.concepto,
                    onValueChange = { v -> onChange(p.copy(concepto = v)) },
                    label = { Text("Concepto") },
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = p.proveedor,
                onValueChange = { v -> onChange(p.copy(proveedor = v)) },
                label = { Text("Proveedor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = p.alta,
                    onCheckedChange = { v -> onChange(p.copy(alta = v, baja = if (v) false else p.baja)) }
                )
                Text("Alta")
                Spacer(Modifier.width(12.dp))
                Checkbox(
                    checked = p.baja,
                    onCheckedChange = { v -> onChange(p.copy(baja = v, alta = if (v) false else p.alta)) }
                )
                Text("Baja")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBorrar) { Text("Borrar") }
            }
        }
    }
}
