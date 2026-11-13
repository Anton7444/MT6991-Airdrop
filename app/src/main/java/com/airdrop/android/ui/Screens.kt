package com.airdrop.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import com.airdrop.android.BuildConfig
import com.airdrop.android.update.Updater
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import com.airdrop.android.R
import com.airdrop.android.Received
import com.airdrop.android.daemon.Event
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.Ipc
import com.airdrop.android.daemon.Outgoing
import com.airdrop.android.daemon.Peer
import com.airdrop.android.daemon.RadioInstaller
import com.airdrop.android.daemon.ReceiveState
import com.airdrop.android.daemon.SendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val ScreenMargin = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    title: String,
    padding: PaddingValues,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).padding(bottom = padding.calculateBottomPadding()),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = actions,
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        content = content,
    )
}

@Composable
fun NearbyScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    val connected by Hub.connected.collectAsStateWithLifecycle()
    val status by Hub.status.collectAsStateWithLifecycle()
    val peers by Hub.peers.collectAsStateWithLifecycle()
    val outgoing by Hub.outgoing.collectAsStateWithLifecycle()
    val sent by Hub.sent.collectAsStateWithLifecycle()
    val incoming by Hub.incoming.collectAsStateWithLifecycle()
    val enabled by Hub.airdrop.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Peer?>(null) }
    val unknown = stringResource(R.string.apple_device)
    LaunchedEffect(enabled, connected, status.on) {
        if (enabled && connected && status.on) Hub.discover(true) else if (!enabled) Hub.discover(false)
    }
    DisposableEffect(Unit) { onDispose { Hub.discover(false) } }
    LaunchedEffect(Unit) { Hub.refreshRoot() }

    Screen(stringResource(R.string.nav_nearby), padding) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ScreenMargin,
                end = ScreenMargin,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(incoming.filter { it.state != ReceiveState.Done }, key = { "in${it.id}" }) { i ->
                IncomingCard(
                    i,
                    onAccept = { Hub.answer(i.id, true) },
                    onDecline = { Hub.answer(i.id, false) },
                    onDismiss = { Hub.dismiss(i.id) },
                    onOpen = { i.paths.lastOrNull()?.let { p -> Received.openReceived(ctx, p) } },
                    onFolder = { Received.openFolder(ctx) },
                    modifier = Modifier.animateContentSize(),
                )
            }
            items(outgoing.values.filter { it.active && peers.none { p -> p.mac == it.mac } }.toList(), key = { "out${it.mac}" }) { o ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    OutgoingStatus(o, onCancel = { Hub.cancel(o.mac) }, modifier = Modifier.padding(16.dp))
                }
            }
            item { RadarHero(connected, status, enabled, onToggle = Hub::setAirDrop) }
            if (!connected) item { RadioCard() }
            if (peers.isNotEmpty()) {
                item {
                    NearbyPeers(peers, outgoing, sent, unknown, onSend = { target = it })
                }
            }
            item { StatusCard(connected, status) }
        }
    }

    target?.let { SendSheet(it, onDismiss = { target = null }) }
}

private enum class Level { Ok, Warn }

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    value: String,
    level: Level,
    action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(
                    if (level == Level.Ok) cs.secondaryContainer else cs.errorContainer,
                    MaterialTheme.shapes.small,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (level == Level.Ok) cs.onSecondaryContainer else cs.onErrorContainer)
            }
        },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text(value)
                action?.invoke()
            }
        },
        trailingContent = {
            Icon(
                if (level == Level.Ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                tint = if (level == Level.Ok) cs.secondary else cs.error,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun StatusCard(connected: Boolean, status: Event.Status) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick++
                Hub.refreshLsposed()
                if (Hub.rootOk.value != true || tick % 6 == 0) Hub.refreshRoot()
                delay(4_000)
            }
        }
    }
    val rootOk by Hub.rootOk.collectAsStateWithLifecycle()
    val moduleVersion by Hub.moduleVersion.collectAsStateWithLifecycle()
    val lsp by Hub.lsposed.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val model = android.os.Build.MODEL
    val supported = RadioInstaller.assetForModel(model) != null
    val kernel = remember { System.getProperty("os.version").orEmpty() }
    val kernelOk = kernel.startsWith("6.6.118")
    val live = tick
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
    val btGranted = live >= 0 && (
        android.os.Build.VERSION.SDK_INT < 31 ||
            (ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == granted &&
                ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == granted)
        )
    val btOn = live >= 0 && runCatching {
        ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)
    val shareVersion = if (live >= 0) {
        runCatching { ctx.packageManager.getPackageInfo(Ipc.SHARE_PKG, 0).versionName }.getOrNull()
    } else {
        null
    }
    val none = stringResource(R.string.status_share_none)
    val running = connected && status.moduleActive
    val info = lsp
    val moduleText = when {
        info == null -> R.string.status_module_check
        !info.dbFound -> R.string.status_module_nolsp
        !info.known || !info.enabled -> if (running) R.string.status_module_off_running else R.string.status_module_off
        !info.scoped -> R.string.status_module_noscope
        running -> R.string.status_module_on
        else -> R.string.status_module_restart
    }
    val moduleOk = info != null && info.dbFound && info.known && info.enabled && info.scoped && running
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.status_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column {
                StatusRow(
                    Icons.Rounded.Smartphone,
                    stringResource(R.string.status_device),
                    stringResource(if (supported) R.string.status_device_ok else R.string.status_device_no, model),
                    if (supported) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.Memory,
                    stringResource(R.string.status_kernel),
                    stringResource(if (kernelOk) R.string.status_kernel_ok else R.string.status_kernel_no, kernel),
                    if (kernelOk) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.Security,
                    stringResource(R.string.status_root),
                    stringResource(
                        when (rootOk) {
                            true -> R.string.status_root_ok
                            false -> R.string.status_root_no
                            null -> R.string.status_root_check
                        },
                    ),
                    if (rootOk == true) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.SettingsInputAntenna,
                    stringResource(R.string.status_radio),
                    if (connected) stringResource(R.string.radio_running, moduleVersion ?: status.version) else stringResource(R.string.status_radio_off),
                    if (connected) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.Extension,
                    stringResource(R.string.status_module),
                    stringResource(moduleText),
                    if (moduleOk) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.Bluetooth,
                    stringResource(R.string.status_bt),
                    stringResource(
                        when {
                            !btGranted -> R.string.status_bt_perm
                            !btOn -> R.string.status_bt_off
                            else -> R.string.status_bt_ok
                        },
                    ),
                    if (btGranted && btOn) Level.Ok else Level.Warn,
                )
                StatusRow(
                    Icons.Rounded.Apps,
                    stringResource(R.string.status_share),
                    shareVersion ?: none,
                    if (shareVersion != null) Level.Ok else Level.Warn,
                )
                (update as? Updater.State.Available)?.let {
                    StatusRow(
                        Icons.Rounded.SystemUpdate,
                        stringResource(R.string.status_update),
                        stringResource(R.string.update_available, it.version),
                        Level.Warn,
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyPeers(
    peers: List<Peer>,
    outgoing: Map<String, Outgoing>,
    sent: Map<String, Hub.SentItem>,
    unknown: String,
    onSend: (Peer) -> Unit,
) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.animateContentSize()) {
            peers.forEachIndexed { index, p ->
                val o = outgoing[p.mac]
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                ListItem(
                    modifier = Modifier.clickable(enabled = o?.active != true) { onSend(p) },
                    leadingContent = { DeviceAvatar(p.model, p.mac) },
                    headlineContent = {
                        Text(
                            p.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = { Text(p.model.ifBlank { unknown }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (o == null || !o.active) {
                            FilledTonalButton(
                                onClick = { onSend(p) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) { Text(stringResource(R.string.send), maxLines = 1) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (o != null) {
                    val sentItem = sent[p.mac]
                    OutgoingStatus(
                        o,
                        onCancel = { Hub.cancel(p.mac) },
                        sent = if (o.state == SendState.Delivered) sentItem else null,
                        onOpen = {
                            if (!Received.openSent(ctx, sentItem?.uri, sentItem?.url, sentItem?.mime)) {
                                Received.openFolder(ctx)
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSheet(peer: Peer, onDismiss: () -> Unit, preset: List<Uri> = emptyList(), presetText: String = "") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<Uri>().apply { addAll(preset) } }
    var text by remember { mutableStateOf(presetText) }
    val names = remember(picked.size, picked.joinToString { it.toString() }) {
        picked.map { displayName(ctx, it) }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked.addAll(it) }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { picked.addAll(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(peer.model, peer.mac)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.send_to, peer.name),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.n_selected, picked.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickTile(
                    icon = Icons.Rounded.Image,
                    label = stringResource(R.string.photos),
                    onClick = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    modifier = Modifier.weight(1f),
                )
                PickTile(
                    icon = Icons.Rounded.AttachFile,
                    label = stringResource(R.string.files),
                    onClick = { files.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (names.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    names.forEach { name ->
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 220.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.text_or_link)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.medium,
            )
            Button(
                onClick = { scope.launch { Hub.send(peer.mac, picked.toList(), text); onDismiss() } },
                enabled = picked.isNotEmpty() || text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.send))
            }
        }
    }
}

@Composable
private fun PickTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun displayName(ctx: Context, uri: Uri): String {
    val fromProvider = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: uri.toString().substringAfterLast('/').substringBefore('?').ifBlank { "…" }
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "heic", "heif", "gif", "webp" -> Icons.Rounded.Image
    "mov", "mp4", "m4v", "mkv" -> Icons.Rounded.Movie
    "pdf", "txt", "doc", "docx", "pages", "key", "numbers" -> Icons.Rounded.Description
    else -> Icons.Rounded.AttachFile
}

@Composable
private fun FileGlyph(name: String) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "mov", "mp4", "m4v", "mkv" -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Box(Modifier.size(44.dp).background(bg, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Icon(fileIcon(name), null, tint = fg)
    }
}

@Composable
fun InboxScreen(padding: PaddingValues) {
    val files by Hub.inbox.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Hub.refreshInbox() }

    Screen(stringResource(R.string.received_title), padding, actions = {
        IconButton(onClick = { Received.openFolder(ctx) }) { Icon(Icons.Rounded.Folder, stringResource(R.string.open_folder)) }
    }) { inner ->
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(inner).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Inbox, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.received_empty), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.received_empty_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(onClick = { Received.openFolder(ctx) }) {
                    Icon(Icons.Rounded.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_folder))
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenMargin,
                    end = ScreenMargin,
                    top = inner.calculateTopPadding() + 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.name + it.modified }) { f ->
                    val ms = if (f.modified < 10_000_000_000L) f.modified * 1000 else f.modified
                    ListItem(
                        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { Received.openFile(ctx, f.name) },
                        leadingContent = { FileGlyph(f.name) },
                        headlineContent = {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text("${humanSize(f.size)} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))}")
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            headlineColor = MaterialTheme.colorScheme.onSurface,
                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(padding: PaddingValues) {
    val connected by Hub.connected.collectAsStateWithLifecycle()
    val status by Hub.status.collectAsStateWithLifecycle()
    val coexRequestFailed by Hub.coexRequestFailed.collectAsStateWithLifecycle()

    Screen(stringResource(R.string.settings_title), padding) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = inner.calculateTopPadding() + 8.dp)
                .padding(horizontal = ScreenMargin)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioCard(advanced = true)

            val policy by Hub.policy.collectAsStateWithLifecycle()
            SettingsCard(
                icon = Icons.Rounded.Wifi,
                accent = MaterialTheme.colorScheme.primaryContainer,
                onAccent = MaterialTheme.colorScheme.onPrimaryContainer,
                title = stringResource(R.string.policy_title),
                body = when {
                    coexRequestFailed -> stringResource(R.string.coex_request_failed)
                    !connected -> stringResource(R.string.coex_radio_offline)
                    !status.coexAvailable || (status.coexWanted && !status.coexActive) ->
                        status.coexReason.ifBlank { stringResource(R.string.coex_unavailable) }
                    else -> stringResource(R.string.policy_body)
                },
                ok = connected && status.coexWanted && status.coexActive,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.coex_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = status.coexWanted,
                        onCheckedChange = Hub::setCoex,
                        enabled = connected && (status.coexAvailable || status.coexWanted),
                    )
                }
                val modes = listOf(
                    Hub.WifiPolicy.KeepWifi to (R.string.policy_keep to R.string.policy_keep_sub),
                    Hub.WifiPolicy.Yield to (R.string.policy_yield to R.string.policy_yield_sub),
                )
                Text(
                    stringResource(R.string.policy_during),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { (p, labels) ->
                        FilterChip(
                            selected = policy == p,
                            onClick = { Hub.setPolicy(p) },
                            label = { Text(stringResource(labels.first)) },
                        )
                    }
                }
                Text(
                    stringResource(modes.first { it.first == policy }.second.second),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UpdateCard()
            LanguageCard()
            UninstallCard()
        }
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    accent: Color,
    onAccent: Color,
    title: String,
    body: String,
    ok: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    hasBody: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            ListItem(
                leadingContent = {
                    Box(Modifier.size(40.dp).background(accent, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = onAccent)
                    }
                },
                headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(body) },
                trailingContent = if (ok || trailing != null) {
                    {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (ok) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                            trailing?.invoke()
                        }
                    }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (hasBody) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }
}

private suspend fun waitForRadio(ctx: Context): String {
    repeat(300) {
        if (Hub.connected.value) return ctx.getString(R.string.radio_up)
        kotlinx.coroutines.delay(500)
    }
    return ctx.getString(R.string.radio_not_up)
}

@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()
    val auto by Updater.auto.collectAsStateWithLifecycle()
    val available = state as? Updater.State.Available
    SettingsCard(
        icon = Icons.Rounded.SystemUpdate,
        accent = MaterialTheme.colorScheme.tertiaryContainer,
        onAccent = MaterialTheme.colorScheme.onTertiaryContainer,
        title = stringResource(R.string.update_title),
        body = when (val s = state) {
            Updater.State.Idle -> stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
            Updater.State.Checking -> stringResource(R.string.update_checking)
            Updater.State.UpToDate -> stringResource(R.string.update_latest, BuildConfig.VERSION_NAME)
            Updater.State.Failed -> stringResource(R.string.update_failed)
            is Updater.State.Available -> stringResource(R.string.update_available, s.version)
        },
        ok = state == Updater.State.UpToDate,
    ) {
        available?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                enabled = state != Updater.State.Checking,
                modifier = Modifier.weight(1f),
                onClick = { Updater.check(ctx) },
            ) { Text(stringResource(R.string.update_check), maxLines = 1) }
            if (available != null && available.page.startsWith("https://")) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(available.page))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.update_open), maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.update_auto),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = auto, onCheckedChange = { Updater.setAuto(ctx, it) })
        }
    }
}

@Composable
private fun UninstallCard() {
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsCard(
        icon = Icons.Rounded.Delete,
        accent = MaterialTheme.colorScheme.errorContainer,
        onAccent = MaterialTheme.colorScheme.onErrorContainer,
        title = stringResource(R.string.uninstall_title),
        body = stringResource(R.string.uninstall_body),
        ok = false,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { confirm = true }) {
                Text(stringResource(R.string.uninstall_service), maxLines = 1)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (confirm) {
        val done = stringResource(R.string.uninstall_done)
        val failed = stringResource(R.string.uninstall_failed, "%s")
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.uninstall_confirm_body)) },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    message = null
                    Hub.uninstallService { err ->
                        message = if (err == null) done else failed.replace("%s", err)
                        busy = false
                    }
                }) { Text(stringResource(R.string.uninstall_confirm)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(android.app.LocaleManager::class.java) }
    var current by remember { mutableStateOf(lm.applicationLocales.toLanguageTags()) }
    val options = listOf(
        "" to stringResource(R.string.lang_system),
        "en" to "English",
        "zh-TW" to "繁體中文",
        "zh-CN" to "简体中文",
    )
    SettingsCard(
        icon = Icons.Rounded.Language,
        accent = MaterialTheme.colorScheme.secondaryContainer,
        onAccent = MaterialTheme.colorScheme.onSecondaryContainer,
        title = stringResource(R.string.lang_title),
        body = stringResource(R.string.lang_body),
        ok = false,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (tag, label) ->
                FilterChip(
                    selected = current.substringBefore(',') == tag,
                    onClick = {
                        lm.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
                        current = tag
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
fun RadioCard(advanced: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by Hub.connected.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connected) { version = withContext(Dispatchers.IO) { RadioInstaller.installedVersion() } }

    SettingsCard(
        icon = Icons.Rounded.SettingsInputAntenna,
        accent = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        onAccent = if (connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        title = stringResource(R.string.radio_title),
        body = when {
            connected -> stringResource(R.string.radio_running, version ?: "")
            version != null -> stringResource(R.string.radio_stopped, version!!)
            else -> stringResource(R.string.radio_missing)
        } + "\n" + stringResource(R.string.radio_hidden),
        ok = connected,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!advanced && version != null && !connected) {
                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    busy = true; message = null
                    scope.launch {
                        withContext(Dispatchers.IO) { RadioInstaller.start(enableAirDrop = true) }
                        message = waitForRadio(ctx)
                        busy = false
                    }
                }) { Text(stringResource(R.string.start), maxLines = 1) }
            }
            val primary = !advanced && version == null
            val onInstall: () -> Unit = {
                busy = true; message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { RadioInstaller.install(ctx) }
                    message = when (r) {
                        null -> { Hub.markRadioInstalled(); waitForRadio(ctx) }
                        RadioInstaller.Failure.NoRoot -> ctx.getString(R.string.err_noroot)
                        RadioInstaller.Failure.UnsupportedDevice -> ctx.getString(R.string.err_unsupported_device)
                        RadioInstaller.Failure.ExistingModule -> ctx.getString(R.string.err_existing_module)
                        is RadioInstaller.Failure.Unpack -> ctx.getString(R.string.err_unpack, r.detail)
                    }
                    version = withContext(Dispatchers.IO) { RadioInstaller.installedVersion() }
                    busy = false
                }
            }
            val label = stringResource(if (busy) R.string.installing else if (version == null) R.string.install else R.string.reinstall)
            val buttonContent: @Composable RowScope.() -> Unit = {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, maxLines = 1)
            }
            if (primary) Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = onInstall, content = buttonContent)
            else FilledTonalButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = onInstall, content = buttonContent)
        }
        TextButton(onClick = { scope.launch { log = withContext(Dispatchers.IO) { RadioInstaller.log() } } }) {
            Text(stringResource(R.string.radio_log))
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    log?.let {
        AlertDialog(
            onDismissRequest = { log = null },
            confirmButton = { TextButton(onClick = { log = null }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.radio_log)) },
            text = {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}
