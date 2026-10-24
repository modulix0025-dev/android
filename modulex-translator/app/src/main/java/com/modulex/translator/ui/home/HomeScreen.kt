package com.modulex.translator.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.platform.CpuSupport
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.navigation.Routes
import androidx.compose.ui.platform.LocalContext

private data class Entry(val route: String, val title: Int, val desc: Int?, val icon: ImageVector, val primary: Boolean)

private val entries = listOf(
    Entry(Routes.CONVERSATION_SETUP, R.string.home_conversation, R.string.home_conversation_desc, Icons.Filled.Forum, true),
    Entry(Routes.VOICE, R.string.home_voice, R.string.home_voice_desc, Icons.Filled.Mic, true),
    Entry(Routes.text(), R.string.home_text, R.string.home_text_desc, Icons.Filled.TextFields, true),
    Entry(Routes.CAMERA, R.string.home_camera, R.string.home_camera_desc, Icons.Filled.CameraAlt, true),
    Entry(Routes.PACKS, R.string.home_packs, R.string.home_packs_desc, Icons.Filled.Download, false),
    Entry(Routes.HISTORY, R.string.home_history, null, Icons.Filled.History, false),
    Entry(Routes.SETTINGS, R.string.home_settings, null, Icons.Filled.Settings, false),
    Entry(Routes.PRIVACY, R.string.home_privacy, null, Icons.Filled.PrivacyTip, false),
)

@Composable
fun HomeScreen(navigate: (String) -> Unit) {
    val c = LocalContext.current.container
    val packs by c.packs.states.collectAsStateWithLifecycle()
    val translationReady = packs.values.any { it.pack.kind == PackKind.TRANSLATION_MODEL && it.usable }

    Scaffold { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.statusBarsPadding().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    !CpuSupport.supported -> Banner(stringResource(R.string.err_cpu_unsupported), BannerKind.ERROR)
                    !translationReady -> Banner(
                        stringResource(R.string.home_engine_missing_body), BannerKind.INFO,
                        actionLabel = stringResource(R.string.action_install), onAction = { navigate(Routes.PACKS) },
                    )
                    else -> Banner(stringResource(R.string.home_offline_ready), BannerKind.OFFLINE)
                }
            }
            items(entries.filter { it.primary }) { e -> FeatureCard(e, large = true) { navigate(e.route) } }
            items(entries.filter { !it.primary }, span = { GridItemSpan(maxLineSpan) }) { e -> FeatureCard(e, large = false) { navigate(e.route) } }
        }
    }
}

@Composable
private fun FeatureCard(e: Entry, large: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (large) 132.dp else 56.dp).semantics { role = Role.Button },
        colors = CardDefaults.cardColors(
            containerColor = if (large) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        // Secondary entries sit on the page colour, so a hairline keeps them visibly tappable.
        border = if (large) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (large) {
            Column(Modifier.padding(16.dp)) {
                Icon(e.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(6.dp))
                Text(stringResource(e.title), style = MaterialTheme.typography.titleMedium)
                e.desc?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(e.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(8.dp))
                Column {
                    Text(stringResource(e.title), style = MaterialTheme.typography.titleMedium)
                    e.desc?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
