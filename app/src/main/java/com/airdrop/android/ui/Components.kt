package com.airdrop.android.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airdrop.android.R
import com.airdrop.android.Received
import com.airdrop.android.daemon.Event
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.Incoming
import com.airdrop.android.daemon.Outgoing
import com.airdrop.android.daemon.RadioInstaller
import com.airdrop.android.daemon.ReceiveState
import com.airdrop.android.daemon.SendState

fun deviceIcon(model: String): ImageVector = when {
    model.startsWith("iPad", true) -> Icons.Rounded.Tablet
    model.startsWith("Mac", true) -> Icons.Rounded.Laptop
    else -> Icons.Rounded.PhoneIphone
}

@Composable
fun DeviceAvatar(model: String, seed: String = model, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when ((seed.hashCode() and 0x7fffffff) % 3) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Box(
        Modifier.size(size).then(modifier).background(bg, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(deviceIcon(model), null, tint = fg, modifier = Modifier.size(size * 0.54f))
    }
}

fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun Incoming.label(ctx: Context): String = singleName ?: ctx.getString(R.string.n_items, count)

@Composable
fun RadarHero(
    connected: Boolean,
    status: Event.Status,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val problem = enabled && (!connected || status.failed)
    val live = enabled && !problem
    val ctx = LocalContext.current
    val ours = remember(connected, status.on) { RadioInstaller.ours(ctx) }
    val bg = when {
        problem -> cs.errorContainer
        live -> cs.primaryContainer
        else -> cs.surfaceContainerHigh
    }
    val onHero = when {
        problem -> cs.onErrorContainer
        live -> cs.onPrimaryContainer
        else -> cs.onSurface
    }
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        contentColor = onHero,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    if (live) {
                        Canvas(Modifier.size(84.dp)) {
                            for (k in 0..2) {
                                val p = (phase + k / 3f) % 1f
                                drawCircle(
                                    color = onHero.copy(alpha = (1f - p) * 0.45f),
                                    radius = size.minDimension / 2 * (0.42f + 0.58f * p),
                                    center = Offset(size.width / 2, size.height / 2),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                    Box(Modifier.size(52.dp).background(onHero, CircleShape), contentAlignment = Alignment.Center) {
                        if (!problem) {
                            Icon(
                                painterResource(R.drawable.ic_airdrop),
                                null,
                                tint = bg,
                                modifier = Modifier.size(30.dp),
                            )
                        } else {
                            Icon(Icons.Rounded.Warning, null, tint = bg, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            !enabled -> stringResource(R.string.hero_off)
                            status.failed -> stringResource(R.string.hero_failed)
                            !connected -> stringResource(R.string.hero_noradio)
                            status.on -> stringResource(R.string.hero_on)
                            else -> stringResource(R.string.hero_starting)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            !enabled -> stringResource(R.string.hero_off_sub)
                            status.failed -> stringResource(R.string.hero_failed_sub, status.error.ifBlank { "-" })
                            !connected -> stringResource(R.string.hero_noradio_sub)
                            status.on -> stringResource(R.string.hero_on_sub, status.name.ifBlank { Build.MODEL })
                            else -> stringResource(R.string.hero_starting_sub)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onHero.copy(alpha = 0.85f),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cs.onPrimary,
                        checkedTrackColor = cs.primary,
                        uncheckedThumbColor = onHero,
                        uncheckedTrackColor = onHero.copy(alpha = 0.28f),
                        uncheckedBorderColor = onHero.copy(alpha = 0.45f),
                    ),
                )
            }
            if (enabled && connected && status.on) {
                if (!status.moduleActive) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.hero_nomodule), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.hero_nomodule_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = onHero.copy(alpha = 0.9f),
                        )
                    }
                } else if (!ours) {
                    Text(
                        stringResource(R.string.hero_radio_borrowed),
                        style = MaterialTheme.typography.bodySmall,
                        color = onHero.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
fun OutgoingStatus(
    o: Outgoing,
    onCancel: () -> Unit,
    sent: Hub.SentItem? = null,
    onOpen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pad = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = when (o.state) {
            SendState.Asking -> stringResource(R.string.st_waiting)
            SendState.Sending -> stringResource(R.string.st_sending, humanSize(o.sent), humanSize(o.total))
            SendState.Delivered -> stringResource(R.string.st_delivered)
            SendState.Declined -> stringResource(R.string.st_declined)
            SendState.Cancelled -> stringResource(R.string.st_cancelled)
            SendState.Failed -> o.error.ifBlank { stringResource(R.string.st_failed) }
        }
        val color = when (o.state) {
            SendState.Delivered -> MaterialTheme.colorScheme.secondary
            SendState.Declined, SendState.Failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (o.active) {
                FilledTonalButton(onClick = onCancel, contentPadding = pad) {
                    Text(stringResource(R.string.cancel), maxLines = 1)
                }
            } else if (o.state == SendState.Delivered && sent != null) {
                FilledTonalButton(onClick = onOpen, contentPadding = pad) {
                    Text(stringResource(if (sent.url != null) R.string.open_link else R.string.open), maxLines = 1)
                }
            }
        }
        if (o.state == SendState.Sending && o.total > 0) {
            LinearProgressIndicator(progress = { o.fraction }, modifier = Modifier.fillMaxWidth())
        } else if (o.state == SendState.Asking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun IncomingCard(
    i: Incoming,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = onDecline,
    onOpen: () -> Unit = {},
    onFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val name = i.label(ctx)
    val file = i.paths.lastOrNull()?.substringAfterLast('/')
    val (container, content) = when (i.state) {
        ReceiveState.Asking -> cs.primaryContainer to cs.onPrimaryContainer
        ReceiveState.Failed -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHigh to cs.onSurface
    }
    val pad = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(i.model, i.from)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        i.from,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (i.state) {
                            ReceiveState.Asking -> stringResource(R.string.in_wants, name)
                            ReceiveState.Receiving -> stringResource(R.string.in_receiving, name)
                            ReceiveState.Done -> stringResource(R.string.in_done, name)
                            ReceiveState.Failed -> stringResource(R.string.in_failed, i.reason.ifBlank { stringResource(R.string.transfer_stopped) })
                            ReceiveState.Declined -> stringResource(R.string.in_declined)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (i.state == ReceiveState.Done && file != null) {
                        Text(
                            file,
                            style = MaterialTheme.typography.bodySmall,
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when (i.state) {
                ReceiveState.Asking -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onDecline, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(stringResource(R.string.decline), maxLines = 1)
                    }
                    Button(onClick = onAccept, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(stringResource(R.string.accept), maxLines = 1)
                    }
                }
                ReceiveState.Receiving -> {
                    if (i.total > 0) LinearProgressIndicator(progress = { i.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.progress_of, humanSize(i.received), humanSize(i.total)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FilledTonalButton(onClick = onCancel, contentPadding = pad) {
                        Text(stringResource(R.string.cancel), maxLines = 1)
                    }
                }
                ReceiveState.Done -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(stringResource(R.string.dismiss), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i.urls.isEmpty()) {
                        FilledTonalButton(onClick = onFolder, modifier = Modifier.weight(1f), contentPadding = pad) {
                            Text(stringResource(R.string.open_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                        contentPadding = pad,
                        enabled = file != null || i.urls.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(if (i.urls.isEmpty()) R.string.open else R.string.open_link),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> FilledTonalButton(onClick = onDismiss, contentPadding = pad) {
                    Text(stringResource(R.string.dismiss), maxLines = 1)
                }
            }
        }
    }
}
