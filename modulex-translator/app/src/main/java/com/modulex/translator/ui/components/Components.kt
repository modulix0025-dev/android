package com.modulex.translator.ui.components

import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.modulex.translator.R
import com.modulex.translator.core.language.FeatureState
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.language.LanguageFeatures
import com.modulex.translator.core.translation.TranslationError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Text in a specific language, laid out in that language's direction regardless of the UI locale. */
@Composable
fun LanguageText(text: String, language: Language?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val dir = when {
        language == null -> TextDirection.Content
        language.rtl -> TextDirection.Rtl
        else -> TextDirection.Ltr
    }
    Text(text, modifier = modifier.fillMaxWidth(), style = style.copy(textDirection = dir))
}

@Composable
fun LanguageBar(
    source: Language?,
    target: Language,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSwap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPickSource, modifier = Modifier.weight(1f).semantics { contentDescription = "source language" }) {
                Text(source?.nativeName ?: stringResource(R.string.detect_language), maxLines = 1)
            }
            IconButton(onClick = { onSwap?.invoke() }, enabled = onSwap != null && source != null) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.action_swap))
            }
            TextButton(onClick = onPickTarget, modifier = Modifier.weight(1f).semantics { contentDescription = "target language" }) {
                Text(target.nativeName, maxLines = 1)
            }
        }
    }
}

@Composable
fun LanguagePickerDialog(
    options: List<LanguageFeatures>,
    allowDetect: Boolean,
    onPick: (Language?) -> Unit,
    onDismiss: () -> Unit,
    capability: (LanguageFeatures) -> FeatureState,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.choose_language)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.search_languages)) })
                Spacer(Modifier.padding(4.dp))
                val q = query.trim().lowercase()
                val filtered = options.filter {
                    q.isEmpty() || it.language.englishName.lowercase().contains(q) || it.language.nativeName.lowercase().contains(q) || it.language.code == q
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (allowDetect && q.isEmpty()) item {
                        Text(stringResource(R.string.detect_language), Modifier.fillMaxWidth().clickable { onPick(null) }.padding(12.dp))
                        HorizontalDivider()
                    }
                    items(filtered, key = { it.language.code }) { f ->
                        val state = capability(f)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = state != FeatureState.UNSUPPORTED) { onPick(f.language) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.language.nativeName, style = MaterialTheme.typography.bodyLarge)
                                Text(f.language.englishName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FeatureStateLabel(state)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun FeatureStateLabel(state: FeatureState) {
    val (text, color) = when (state) {
        FeatureState.READY -> stringResource(R.string.cap_ready) to MaterialTheme.colorScheme.secondary
        FeatureState.NEEDS_PACK -> stringResource(R.string.cap_needs_pack) to MaterialTheme.colorScheme.primary
        FeatureState.PACK_UNVERIFIED -> stringResource(R.string.cap_unverified) to MaterialTheme.colorScheme.onSurfaceVariant
        FeatureState.UNSUPPORTED -> stringResource(R.string.cap_unsupported) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}

enum class BannerKind { INFO, OK, WARNING, ERROR, OFFLINE }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val (icon, container) = when (kind) {
        BannerKind.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.primaryContainer
        BannerKind.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.secondaryContainer
        BannerKind.WARNING -> Icons.Filled.Info to MaterialTheme.colorScheme.surfaceVariant
        BannerKind.ERROR -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.errorContainer
        BannerKind.OFFLINE -> Icons.Filled.WifiOff to MaterialTheme.colorScheme.secondaryContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            // The action takes the banner's content colour so it stays legible on every banner kind.
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LoadingBlock(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.padding(8.dp)); it() }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), content = content) }
}

/**
 * Shows [content] only once all [permissions] are granted; otherwise explains why they are needed
 * and offers to request them (or to open system settings after a permanent denial).
 */
@Composable
fun PermissionGate(permissions: List<String>, rationale: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; ok = granted() }
    if (ok) { content(); return }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rationale, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.padding(8.dp))
            FilledTonalButton(onClick = { launcher.launch(permissions.toTypedArray()) }) { Text(stringResource(R.string.action_grant)) }
            if (asked) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text(stringResource(R.string.action_open_settings)) }
        }
    }
}

/** User-facing message for a translation failure (never raw native errors). */
fun TranslationError.messageRes(): Int = when (this) {
    TranslationError.MODEL_NOT_INSTALLED -> R.string.err_model_missing
    TranslationError.MODEL_LOAD_FAILED -> R.string.err_model_load
    TranslationError.UNSUPPORTED_LANGUAGE -> R.string.err_unsupported_language
    TranslationError.INPUT_TOO_LONG -> R.string.err_input_too_long
    TranslationError.INFERENCE_FAILED -> R.string.err_inference
    TranslationError.OUT_OF_MEMORY -> R.string.err_out_of_memory
    TranslationError.EMPTY_OUTPUT -> R.string.err_empty_output
}

/**
 * Single-choice chip for the black-and-white theme: the selected option is drawn inverted (solid
 * primary with onPrimary text and a check mark) so the choice never depends on a subtle tint.
 */
@Composable
fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

