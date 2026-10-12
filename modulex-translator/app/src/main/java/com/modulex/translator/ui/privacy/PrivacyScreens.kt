package com.modulex.translator.ui.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modulex.translator.R
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.SectionCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Composable
fun PrivacyScreen(onBack: () -> Unit, onNotices: () -> Unit) {
    ScreenScaffold(stringResource(R.string.privacy_title), onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard { Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyLarge) }
            SectionCard {
                Text(stringResource(R.string.privacy_permissions), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.privacy_permissions_body))
            }
            SectionCard {
                Text(stringResource(R.string.privacy_storage), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.privacy_storage_body))
            }
            SectionCard { Text(stringResource(R.string.privacy_no_analytics), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onNotices) { Text(stringResource(R.string.notices_title)) }
        }
    }
}

@Serializable
private data class NoticeIndex(val components: List<Notice>)

@Serializable
private data class Notice(val name: String, val license: String, val file: String, val scope: String)

/** Third-party notices: names, licenses and the full license texts shipped in assets/licenses. */
@Composable
fun NoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val index = remember {
        context.assets.open("licenses/index.json").use { Json.decodeFromString(NoticeIndex.serializer(), it.reader().readText()) }
    }
    var open by remember { mutableStateOf<Notice?>(null) }
    ScreenScaffold(stringResource(R.string.notices_title), onBack = { if (open != null) open = null else onBack() }) { pad ->
        val shown = open
        if (shown == null) {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.notices_intro)) }
                items(index.components) { n ->
                    Card(Modifier.fillMaxWidth().clickable { open = n }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.name, style = MaterialTheme.typography.titleMedium)
                            Text(n.license + " · " + n.scope, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            val text = remember(shown) { context.assets.open("licenses/" + shown.file).use { it.reader().readText() } }
            // License texts are long; LazyColumn over lines keeps memory and layout cost low.
            val lines = remember(text) { text.lines() }
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
                item { Text(shown.name, style = MaterialTheme.typography.titleMedium) }
                items(lines) { l -> Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)) }
            }
        }
    }
}
