package com.modulex.translator.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.conversation.ChatMessage
import com.modulex.translator.conversation.ConvPhase
import com.modulex.translator.conversation.Delivery
import com.modulex.translator.conversation.InputMode
import com.modulex.translator.conversation.MicState
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.security.TransportKind
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.LanguageText
import com.modulex.translator.ui.components.LoadingBlock
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.theme.LocalStatusColors
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationScreen(onBack: () -> Unit) {
    val controller = LocalContext.current.container.conversation
    val st by controller.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (controller.active) confirmLeave = true else { controller.reset(); onBack() } }
    BackHandler(onBack = leave)

    ScreenScaffold(
        title = st.peer?.displayName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.conv_connected_to, it) } ?: stringResource(R.string.conv_title),
        onBack = leave,
        actions = {
            st.latencyMs?.let { Text(stringResource(R.string.conv_latency, it.toInt()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp)) }
            if (controller.active) TextButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.conv_end)) }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            st.transport?.let { t ->
                Text(stringResource(R.string.conv_mode_active, stringResource(transportTitle(t))) + " · " +
                    stringResource(if (t == TransportKind.INTERNET) R.string.conv_internet_mode_note else R.string.conv_offline_mode_note),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            st.notice?.let { n ->
                Banner(noticeText(n), BannerKind.WARNING, stringResource(R.string.action_done), controller::dismissNotice, Modifier.padding(horizontal = 16.dp))
            }
            when (val ph = st.phase) {
                ConvPhase.Idle, ConvPhase.Preparing -> LoadingBlock(stringResource(R.string.conv_connecting))
                is ConvPhase.WaitingForPeer -> WaitingPanel(ph)
                ConvPhase.Connecting -> LoadingBlock(stringResource(R.string.conv_connecting))
                is ConvPhase.Failed -> FailedPanel(ph.code) { controller.reset(); onBack() }
                is ConvPhase.Ended -> {
                    Banner(stringResource(if (ph.byPeer) R.string.conv_ended_by_peer else R.string.conv_end), BannerKind.INFO, modifier = Modifier.padding(16.dp))
                    Messages(st.messages, st.showOriginal, Modifier.weight(1f))
                }
                is ConvPhase.Reconnecting, is ConvPhase.Connected -> {
                    if (ph is ConvPhase.Reconnecting) Banner(stringResource(R.string.conv_reconnecting), BannerKind.WARNING, modifier = Modifier.padding(horizontal = 16.dp))
                    Messages(st.messages, st.showOriginal, Modifier.weight(1f))
                    st.partial?.let { LanguageText("… $it", LanguageRegistry.get(st.me.speaks), Modifier.padding(horizontal = 16.dp), MaterialTheme.typography.bodyMedium) }
                    Controls()
                    if (ph is ConvPhase.Connected && !ph.sasConfirmed) SasDialog(ph.sas, onResult = controller::confirmSas)
                }
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.conv_end)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; controller.end() }) { Text(stringResource(R.string.conv_end)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun noticeText(code: String): String = stringResource(when (code) {
    "auth_failed" -> R.string.conv_auth_failed
    "sas_mismatch" -> R.string.conv_mismatch_ended
    "translation_failed" -> R.string.err_inference
    "speech_unavailable" -> R.string.voice_unavailable_body
    else -> R.string.err_generic
})

@Composable
private fun WaitingPanel(ph: ConvPhase.WaitingForPeer) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.conv_show_code), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(8.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.widthIn(max = 320.dp)) {
            QrCode(ph.payload.toUri(), Modifier.padding(12.dp))
        }
        ph.pairingCode?.let {
            Spacer(Modifier.padding(8.dp))
            Text(stringResource(R.string.conv_pairing_code, it), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.padding(8.dp))
        LoadingBlock(stringResource(R.string.conv_waiting))
    }
}

@Composable
private fun FailedPanel(code: String, onClose: () -> Unit) {
    val msg = when (code) {
        "no_network" -> R.string.conv_no_network
        "bt_off", "bt_unavailable" -> R.string.conv_bt_off
        "p2p_unavailable", "p2p_missing" -> R.string.conv_p2p_unavailable
        "net_not_configured" -> R.string.conv_net_not_configured
        "signaling_failed" -> R.string.conv_signaling_failed
        "auth_failed" -> R.string.conv_auth_failed
        "invalid_code" -> R.string.conv_invalid_code
        "permission_denied" -> R.string.err_nearby_denied
        else -> R.string.err_generic
    }
    Column(Modifier.padding(16.dp)) {
        Banner(stringResource(msg), BannerKind.ERROR)
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onClose) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
private fun SasDialog(sas: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.conv_verify_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.conv_verify_body, ""), textAlign = TextAlign.Center)
                Text(sas.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(12.dp).semantics { contentDescription = sas.toList().joinToString(" ") })
            }
        },
        confirmButton = { Button(onClick = { onResult(true) }) { Text(stringResource(R.string.conv_verify_match)) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text(stringResource(R.string.conv_verify_mismatch)) } },
    )
}

@Composable
private fun Messages(messages: List<ChatMessage>, showOriginal: Boolean, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages, key = { it.id }) { m -> Bubble(m, showOriginal) }
    }
}

@Composable
private fun Bubble(m: ChatMessage, showOriginal: Boolean) {
    val time = remember(m.timestamp) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.timestamp)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(containerColor = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (m.mine) {
                    // My words first; the translation the other person reads below.
                    m.original?.let { LanguageText(it, LanguageRegistry.get(m.sourceLang), style = MaterialTheme.typography.bodyLarge) }
                    LanguageText(m.translated, LanguageRegistry.get(m.targetLang), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LanguageText(m.translated, LanguageRegistry.get(m.targetLang), style = MaterialTheme.typography.titleMedium)
                    if (showOriginal) m.original?.let { LanguageText(it, LanguageRegistry.get(m.sourceLang), style = MaterialTheme.typography.bodyMedium) }
                }
                Text(
                    time + if (m.mine) " · " + stringResource(when (m.delivery) {
                        Delivery.DELIVERED -> R.string.conv_delivered
                        Delivery.FAILED -> R.string.conv_failed
                        else -> R.string.conv_pending
                    }) else "",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Controls() {
    val controller = LocalContext.current.container.conversation
    val st by controller.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.inputMode == InputMode.HANDS_FREE, { controller.setInputMode(InputMode.HANDS_FREE) }, { Text(stringResource(R.string.conv_auto_mode)) })
                FilterChip(st.inputMode == InputMode.PUSH_TO_TALK, { controller.setInputMode(InputMode.PUSH_TO_TALK) }, { Text(stringResource(R.string.conv_push_to_talk)) })
                FilterChip(st.inputMode == InputMode.TEXT, { controller.setInputMode(InputMode.TEXT) }, { Text(stringResource(R.string.conv_text_mode)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conv_speak_aloud), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(st.speakAloud, controller::setSpeakAloud)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conv_share_original), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(st.shareOriginal, controller::setShareOriginal)
            }
            when (st.inputMode) {
                InputMode.TEXT -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it.take(2_000) }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.conv_type_message)) })
                    IconButton(onClick = { controller.sendText(text); text = "" }, enabled = text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.conv_send))
                    }
                }
                InputMode.PUSH_TO_TALK -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = if (st.mic == MicState.HEARING_SPEECH) LocalStatusColors.current.live else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(84.dp).semantics { contentDescription = "push to talk" }.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                controller.setPushToTalk(true)
                                tryAwaitRelease()
                                controller.setPushToTalk(false)
                            })
                        },
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary) } }
                }
                InputMode.HANDS_FREE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = when (st.mic) {
                        MicState.OFF -> R.string.conv_unmute
                        MicState.LISTENING -> R.string.voice_recording_indicator
                        MicState.HEARING_SPEECH -> R.string.voice_listening
                        MicState.PROCESSING -> R.string.voice_processing
                        MicState.PAUSED_FOR_PLAYBACK -> R.string.action_speak
                    }
                    Text(stringResource(label), Modifier.weight(1f))
                    OutlinedButton(onClick = { controller.setMuted(!st.muted) }) {
                        Icon(if (st.muted) Icons.Filled.MicOff else Icons.Filled.Mic, null)
                        Text(" " + stringResource(if (st.muted) R.string.conv_unmute else R.string.conv_mute))
                    }
                }
            }
        }
    }
}
