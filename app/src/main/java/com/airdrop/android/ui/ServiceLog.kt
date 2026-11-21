package com.airdrop.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airdrop.android.R
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.RadioInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One "== title" block of the service log. */
private class LogSection(val title: String, val lines: List<String>) {
    val trouble = lines.any(::isTrouble)
}

private val TROUBLE = Regex("(?i)\\b(error|fail\\w*|fatal|panic|not running|timed out|refused|denied|cannot|can't|no session)\\b")

private fun isTrouble(line: String) = TROUBLE.containsMatchIn(line)

private fun parseLog(raw: String): List<LogSection> {
    val out = ArrayList<LogSection>()
    var title = ""
    var body = ArrayList<String>()
    fun flush() {
        val trimmed = body.dropLastWhile { it.isBlank() }.dropWhile { it.isBlank() }
        if (title.isNotEmpty() || trimmed.isNotEmpty()) out += LogSection(title.ifEmpty { "—" }, trimmed)
    }
    for (line in raw.lines()) {
        if (line.startsWith("== ")) {
            flush()
            title = line.removePrefix("== ").trim()
            body = ArrayList()
        } else body += line
    }
    flush()
    return out
}

/**
 * The service log, full screen. It opens at once and reads the log in the background (that takes a root
 * shell and a few hundred milliseconds); the old dialog only appeared after the read, so the tap looked dead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceLogScreen(onClose: () -> Unit) {
    var raw by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(round) {
        loading = true
        raw = withContext(Dispatchers.IO) { RadioInstaller.log() }
        loading = false
    }
    val sections = remember(raw) { raw?.let(::parseLog).orEmpty() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.radio_log)) },
                        navigationIcon = {
                            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                        },
                        actions = {
                            IconButton(enabled = !loading, onClick = { round++ }) {
                                Icon(Icons.Rounded.Refresh, stringResource(R.string.log_refresh))
                            }
                            IconButton(enabled = raw != null, onClick = {
                                raw?.let { clipboard.setText(AnnotatedString(it)) }
                            }) {
                                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.copy_log))
                            }
                        },
                    )
                    if (loading && raw != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
        ) { inner ->
            if (raw == null) {
                Column(
                    Modifier.fillMaxSize().padding(inner),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.log_loading),
                        modifier = Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sections, key = { it.title }) { s ->
                    // Sections with trouble in them open by default; the rest stay folded to a single row.
                    val open = expanded[s.title] ?: (s.trouble || s.lines.size <= 4)
                    LogSectionCard(s, open) { expanded[s.title] = !open }
                }
            }
        }
    }
}

@Composable
private fun LogSectionCard(s: LogSection, open: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (s.trouble) Icon(Icons.Rounded.Warning, null, tint = cs.error, modifier = Modifier.size(18.dp))
                Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${s.lines.size}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = cs.onSurfaceVariant)
            }
            AnimatedVisibility(open) {
                val errorColor = cs.error
                val text = remember(s) {
                    buildAnnotatedString {
                        if (s.lines.isEmpty()) append("—")
                        s.lines.forEachIndexed { i, line ->
                            if (i > 0) append('\n')
                            if (isTrouble(line)) withStyle(SpanStyle(color = errorColor, fontWeight = FontWeight.SemiBold)) { append(line) }
                            else append(line)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .background(cs.surfaceContainerLowest, MaterialTheme.shapes.medium)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, softWrap = false)
                }
            }
        }
    }
}

/** What the background health check last found; shown on the home screen whether AirDrop is on or off. */
@Composable
fun HealthCard(health: Hub.Health, airdropOn: Boolean, onShowLog: () -> Unit) {
    // While AirDrop is on and everything works, the hero above already says so.
    if (health == Hub.Health.Ready && airdropOn) return
    if (health == Hub.Health.NotInstalled) return // the install card covers this
    val cs = MaterialTheme.colorScheme
    val bad = health == Hub.Health.NoRoot || health == Hub.Health.RadioDown || health == Hub.Health.ShareNotResponding ||
        health == Hub.Health.Outdated
    val busy = health == Hub.Health.Checking || health == Hub.Health.Starting
    val title = when (health) {
        Hub.Health.Checking -> R.string.health_checking
        Hub.Health.Ready -> R.string.health_ready
        Hub.Health.Starting -> R.string.health_starting
        Hub.Health.NoRoot -> R.string.health_noroot
        Hub.Health.RadioDown -> R.string.health_radiodown
        Hub.Health.ShareNotResponding -> R.string.health_share
        Hub.Health.Outdated -> R.string.health_outdated
        Hub.Health.NotInstalled -> return
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (bad) cs.errorContainer else cs.surfaceContainer,
        contentColor = if (bad) cs.onErrorContainer else cs.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    bad -> Icon(Icons.Rounded.Warning, null, tint = cs.error)
                    else -> Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                }
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (bad && health != Hub.Health.Outdated) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = Hub::checkHealthNow) {
                        Text(stringResource(R.string.health_check), maxLines = 1)
                    }
                    FilledTonalButton(modifier = Modifier.weight(1f), onClick = onShowLog) {
                        Text(stringResource(R.string.radio_log), maxLines = 1)
                    }
                }
            }
        }
    }
}
