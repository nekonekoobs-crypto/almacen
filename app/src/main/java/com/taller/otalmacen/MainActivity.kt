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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF81C784),
                    onPrimary = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    App()
                }
            }
        }
    }
}

private val VERDE = Color(0xFF2E7D32)
private val ROJO = Color(0xFFC62828)
private val GRIS = Color(0xFF2B2B2B)
private val ERROR = Color(0xFFFF8A80)

@Composable
fun App() {
    val ctx = LocalContext.current
    val base = ctx.filesDir
    var ordenes by remember { mutableStateOf(Store.loadAll(base)) }
    var actual by remember { mutableStateOf<Orden?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var anexando by remember { mutableStateOf(false) }

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
        val anex = anexando
        OcrParser.leer(ctx, uri,
            { nuevo ->
                cargando = false
                val d = actual
                val res = if (anex && d != null) fusionar(d, nuevo) else nuevo
                Store.save(base, res)
                ordenes = Store.loadAll(base)
                actual = res
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
            onFoto = { anexando = false; camara.launch(fotoUri) },
            onGaleria = { anexando = false; galeria.launch("image/*") },
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
            cargando = cargando,
            mensaje = mensaje,
            onFotoMas = { anexando = true; camara.launch(fotoUri) },
            onGaleriaMas = { anexando = true; galeria.launch("image/*") },
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

fun fusionar(a: Orden, n: Orden): Orden = a.copy(
    seguro = a.seguro.ifBlank { n.seguro },
    elaboracion = a.elaboracion.ifBlank { n.elaboracion },
    entrega = a.entrega.ifBlank { n.entrega },
    ot = a.ot.ifBlank { n.ot },
    unidad = a.unidad.ifBlank { n.unidad },
    vin = a.vin.ifBlank { n.vin },
    modelo = a.modelo.ifBlank { n.modelo },
    taller = a.taller.ifBlank { n.taller },
    items = a.items + n.items
)

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
    var busqueda by remember { mutableStateOf("") }
    var porBorrar by remember { mutableStateOf<Orden?>(null) }
    val filtradas = if (busqueda.isBlank()) ordenes
    else ordenes.filter { it.ot.contains(busqueda.trim(), ignoreCase = true) }

    val pendiente = porBorrar
    if (pendiente != null) {
        AlertDialog(
            onDismissRequest = { porBorrar = null },
            title = { Text("Eliminar OT") },
            text = {
                Text("¿Estás seguro de eliminar la OT ${pendiente.ot.ifBlank { "(sin número)" }}? Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onBorrar(pendiente)
                    porBorrar = null
                }) { Text("Sí, eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { porBorrar = null }) { Text("Cancelar") }
            }
        )
    }

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
        item {
            OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                label = { Text("Buscar por OT") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
            item { Text(mensaje, color = ERROR) }
        }
        if (filtradas.isEmpty() && busqueda.isNotBlank()) {
            item { Text("No hay OT que coincidan con la búsqueda.") }
        }
        items(filtradas, key = { it.id }) { o ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = GRIS, contentColor = Color.White)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("OT ${o.ot.ifBlank { "(sin número)" }}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${o.unidad} · ${o.seguro} · ${o.items.size} piezas")
                    Row {
                        TextButton(onClick = { onAbrir(o) }) { Text("Abrir") }
                        TextButton(onClick = { porBorrar = o }) { Text("Borrar") }
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
    cargando: Boolean,
    mensaje: String?,
    onVolver: () -> Unit,
    onFotoMas: () -> Unit,
    onGaleriaMas: () -> Unit,
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

    var busq by remember { mutableStateOf("") }
    var porBorrarPieza by remember { mutableStateOf<Item?>(null) }
    val visibles = if (busq.isBlank()) orden.items
    else orden.items.filter { it.concepto.contains(busq.trim(), ignoreCase = true) }

    val pend = porBorrarPieza
    if (pend != null) {
        AlertDialog(
            onDismissRequest = { porBorrarPieza = null },
            title = { Text("Eliminar pieza") },
            text = {
                Text("¿Estás seguro de eliminar la pieza \"${pend.concepto.ifBlank { "(sin concepto)" }}\"? Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onCambio(orden.copy(items = orden.items.filter { x -> x.id != pend.id }))
                    porBorrarPieza = null
                }) { Text("Sí, eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { porBorrarPieza = null }) { Text("Cancelar") }
            }
        )
    }

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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFotoMas, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Capturar más fotos", fontSize = 17.sp)
                }
                OutlinedButton(onClick = onGaleriaMas, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Agregar desde galería")
                }
                OutlinedButton(
                    onClick = {
                        val nuevo = Item(id = System.currentTimeMillis(), pz = "1", concepto = "")
                        onCambio(orden.copy(items = orden.items + nuevo))
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Agregar pieza manualmente") }
                if (cargando) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("Leyendo la foto…")
                    }
                }
                if (mensaje != null) {
                    Text(mensaje, color = ERROR)
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
            OutlinedTextField(
                value = busq,
                onValueChange = { busq = it },
                label = { Text("Buscar por concepto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            val titulo = if (busq.isBlank()) "Piezas (${orden.items.size})"
            else "Piezas (${visibles.size} de ${orden.items.size})"
            Text(titulo, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (visibles.isEmpty() && busq.isNotBlank()) {
            item { Text("No hay piezas que coincidan con la búsqueda.") }
        }
        items(visibles, key = { it.id }) { p ->
            FilaPieza(
                p = p,
                onChange = { n ->
                    onCambio(orden.copy(items = orden.items.map { x -> if (x.id == n.id) n else x }))
                },
                onBorrar = { porBorrarPieza = p }
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
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = Color.White)
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
