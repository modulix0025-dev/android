package com.modulex.translator.ui.conversation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.conversation.ParticipantProfile
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.security.PairingPayload
import com.modulex.translator.core.security.TransportKind
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.LanguagePickerDialog
import com.modulex.translator.ui.components.PermissionGate
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.SectionCard

/** Precise location (requested together with approximate, as Android 12 requires) for pre-13 scans. */
private val locationPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Runtime permissions each transport needs on this Android version. */
fun permissionsFor(t: TransportKind): List<String> = when (t) {
    TransportKind.LAN, TransportKind.INTERNET -> emptyList()
    TransportKind.WIFI_DIRECT ->
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else locationPermissions
    TransportKind.BLUETOOTH ->
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else locationPermissions
}

/** Microphone and notifications are optional: text mode works without them. */
private fun optionalPermissions(): List<String> =
    listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

@Composable
fun ConversationSetupScreen(onBack: () -> Unit, onStarted: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val packs by c.packs.states.collectAsStateWithLifecycle()
    val readiness = remember(packs) { c.packs.readiness() }

    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    var speaks by rememberSaveable { mutableStateOf(settings.lastSource) }
    var wants by rememberSaveable { mutableStateOf(settings.lastSource) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var transport by rememberSaveable { mutableStateOf(TransportKind.LAN) }
    var picker by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = result.filterKeys { it !in optionalPermissions() }
        if (required.values.all { it }) pending?.invoke() else error = R.string.err_nearby_denied
        pending = null
    }

    fun me() = ParticipantProfile(name.trim().take(40), speaks, wants)
    fun persist() = c.settings.update { it.copy(displayName = name.trim(), lastSource = speaks) }
    fun withPermissions(t: TransportKind, action: () -> Unit) {
        persist()
        pending = action
        launcher.launch((permissionsFor(t) + optionalPermissions()).toTypedArray())
    }

    ScreenScaffold(stringResource(R.string.conv_title), onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.conv_your_name)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker = "speaks" }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.conv_i_speak) + ": " + (LanguageRegistry.get(speaks)?.nativeName ?: speaks))
                }
                OutlinedButton(onClick = { picker = "wants" }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.conv_i_read) + ": " + (LanguageRegistry.get(wants)?.nativeName ?: wants))
                }
            }
            error?.let { Banner(stringResource(it), BannerKind.ERROR) }

            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.conv_create)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.conv_join)) })
            }

            if (tab == 0) {
                SectionCard {
                    Text(stringResource(R.string.conv_connection), style = MaterialTheme.typography.titleMedium)
                    TransportKind.entries.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = transport == t, role = Role.RadioButton) { transport = t }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(transport == t, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(transportTitle(t)), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(transportDesc(t)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (transport == TransportKind.INTERNET && !settings.signalingUrl.startsWith("wss://")) {
                    Banner(stringResource(R.string.conv_net_not_configured), BannerKind.INFO, stringResource(R.string.settings_title), onOpenSettings)
                }
                Text(stringResource(if (transport == TransportKind.INTERNET) R.string.conv_internet_mode_note else R.string.conv_offline_mode_note),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { withPermissions(transport) { c.conversation.host(transport, me()); onStarted() } },
                    enabled = transport != TransportKind.INTERNET || settings.signalingUrl.startsWith("wss://"),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conv_create)) }
            } else {
                if (scanning) {
                    PermissionGate(listOf(Manifest.permission.CAMERA), stringResource(R.string.err_camera_denied)) {
                        QrScanner(onResult = { text ->
                            scanning = false
                            when (val r = PairingPayload.parse(text)) {
                                is PairingPayload.Companion.ParseResult.Ok ->
                                    withPermissions(r.payload.transport) { c.conversation.join(r.payload, me()); onStarted() }
                                is PairingPayload.Companion.ParseResult.Invalid -> error = R.string.conv_invalid_code
                            }
                        })
                    }
                    TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.action_cancel)) }
                } else {
                    Button(onClick = { error = null; scanning = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.conv_scan_qr)) }
                }
                SectionCard {
                    Text(stringResource(R.string.conv_enter_code), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.conv_mode_net_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(code, { code = it.uppercase().take(11) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("XXXXX-XXXXX") })
                    OutlinedButton(
                        onClick = {
                            if (com.modulex.translator.core.security.SecureIds.normalisePairingCode(code) == null) error = R.string.conv_invalid_code
                            else withPermissions(TransportKind.INTERNET) { c.conversation.joinWithCode(code, me()); onStarted() }
                        },
                        enabled = settings.signalingUrl.startsWith("wss://") && code.length >= 10,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.conv_join)) }
                    if (!settings.signalingUrl.startsWith("wss://")) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.conv_net_not_configured)) }
                }
            }
        }
    }

    picker?.let { which ->
        LanguagePickerDialog(
            options = readiness.all(), allowDetect = false,
            onPick = { l -> l?.let { if (which == "speaks") speaks = it.code else wants = it.code }; picker = null },
            onDismiss = { picker = null },
            capability = { if (which == "speaks") it.speechInput else it.translation },
        )
    }
}

fun transportTitle(t: TransportKind) = when (t) {
    TransportKind.LAN -> R.string.conv_mode_lan
    TransportKind.WIFI_DIRECT -> R.string.conv_mode_p2p
    TransportKind.BLUETOOTH -> R.string.conv_mode_bt
    TransportKind.INTERNET -> R.string.conv_mode_net
}

fun transportDesc(t: TransportKind) = when (t) {
    TransportKind.LAN -> R.string.conv_mode_lan_desc
    TransportKind.WIFI_DIRECT -> R.string.conv_mode_p2p_desc
    TransportKind.BLUETOOTH -> R.string.conv_mode_bt_desc
    TransportKind.INTERNET -> R.string.conv_mode_net_desc
}
