package io.github.patrickdalton.craft.hub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8FA8FF)) else lightColorScheme(primary = Color(0xFF2F5BEA))
            MaterialTheme(colorScheme = scheme) { HubScreen() }
        }
    }
}

/** Everything the screen shows about one app. */
private data class AppRow(
    val app: CatalogApp,
    val release: Release? = null,
    val installed: Installed? = null,
    val icon: Bitmap? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var hubUpdate by remember { mutableStateOf<Release?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }

    fun refreshInstalled() {
        rows = rows.map { it.copy(installed = Store.installed(context, it.app.packageName)) }
    }

    suspend fun load() {
        loading = true
        error = null
        try {
            val catalog = withContext(Dispatchers.IO) { Store.catalog(context) }
            rows = catalog.map { AppRow(it, installed = Store.installed(context, it.packageName)) }
            val releases = withContext(Dispatchers.IO) { catalog.associate { it.id to (if (it.available) Store.release(it.id) else null) } }
            rows = rows.map { it.copy(release = releases[it.app.id]) }
            hubUpdate = withContext(Dispatchers.IO) { Store.release(HUB_ID) }?.takeIf { it.versionCode > currentVersionCode(context) }
            for (row in rows) {
                scope.launch {
                    val icon = withContext(Dispatchers.IO) { Store.icon(row.app) }
                    rows = rows.map { if (it.app.id == row.app.id) it.copy(icon = icon) else it }
                }
            }
        } catch (e: Exception) {
            error = "Não foi possível carregar a lista de apps: ${e.message}"
        } finally {
            loading = false
        }
    }

    fun install(id: String) {
        if (progress.containsKey(id)) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            notice = "Para instalar os apps, permita que o Craft Hub instale apps desconhecidos na tela que vai abrir. Depois toque em Baixar de novo."
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        progress[id] = 0f
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { Store.download(context, id) { p -> scope.launch { progress[id] = p } } }
                openInstaller(context, apk)
            } catch (e: Exception) {
                notice = "Falha ao baixar: ${e.message}"
            } finally {
                progress.remove(id)
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshInstalled() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Craft Hub") },
                actions = {
                    IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar lista")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && rows.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { scope.launch { load() } }) { Text("Tentar de novo") }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    hubUpdate?.let { update ->
                        item {
                            HubUpdateCard(update, progress[HUB_ID]) { install(HUB_ID) }
                        }
                    }
                    items(rows, key = { it.app.id }) { row ->
                        AppCard(
                            row = row,
                            progress = progress[row.app.id],
                            onInstall = { install(row.app.id) },
                            onOpen = { context.packageManager.getLaunchIntentForPackage(row.app.packageName)?.let(context::startActivity) },
                            onUninstall = { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${row.app.packageName}"))) },
                        )
                    }
                    item {
                        Text(
                            "Versões para Android dos apps Craft (código aberto, Apache-2.0) da storytold, compiladas a partir do código original.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
            text = { Text(text) },
        )
    }
}

@Composable
private fun HubUpdateCard(update: Release, progress: Float?, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nova versão do Craft Hub", fontWeight = FontWeight.SemiBold)
                Text("Versão ${update.versionName}", style = MaterialTheme.typography.bodySmall)
            }
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, Modifier.size(32.dp))
            } else {
                Button(onClick = onUpdate) { Text("Atualizar") }
            }
        }
    }
}

@Composable
private fun AppCard(row: AppRow, progress: Float?, onInstall: () -> Unit, onOpen: () -> Unit, onUninstall: () -> Unit) {
    val context = LocalContext.current
    val app = row.app
    val installed = row.installed
    val release = row.release
    val updatable = installed != null && release != null && release.versionCode > installed.versionCode
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row.icon, app.name)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(app.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusLine(context, app, installed, release),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installed != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Desinstalar") }, onClick = { menu = false; onUninstall() })
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            when {
                progress != null -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Baixando… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                !app.available -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Em breve") }
                release == null && installed == null -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Ainda sem versão publicada") }
                installed == null -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Baixar") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (updatable) {
                        FilledTonalButton(onClick = onInstall, modifier = Modifier.weight(1f)) { Text("Atualizar") }
                    }
                    Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Abrir") }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: Bitmap?, name: String) {
    val shape = RoundedCornerShape(12.dp)
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = name, modifier = Modifier.size(56.dp).clip(shape))
    } else {
        Box(
            Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private fun statusLine(context: Context, app: CatalogApp, installed: Installed?, release: Release?): String {
    val inspired = if (app.inspired.isNotEmpty()) "Alternativa ao ${app.inspired}" else ""
    val state = when {
        installed != null && release != null && release.versionCode > installed.versionCode ->
            "Instalado ${installed.versionName} · nova versão ${release.versionName}"
        installed != null -> "Instalado ${installed.versionName}"
        release != null -> "${release.versionName} · ${Formatter.formatShortFileSize(context, release.size)}"
        else -> ""
    }
    return listOf(inspired, state).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun currentVersionCode(context: Context): Long =
    Store.installed(context, context.packageName)?.versionCode ?: 0

/** Hands the downloaded APK to Android's installer, which asks the user to confirm. */
private fun openInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
