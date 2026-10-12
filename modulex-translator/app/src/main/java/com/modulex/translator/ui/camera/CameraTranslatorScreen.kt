package com.modulex.translator.ui.camera

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.TranslationError
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.data.HistoryEntry
import com.modulex.translator.data.HistoryKind
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.LanguageBar
import com.modulex.translator.ui.components.LanguagePickerDialog
import com.modulex.translator.ui.components.LanguageText
import com.modulex.translator.ui.components.LoadingBlock
import com.modulex.translator.ui.components.PermissionGate
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.messageRes
import com.modulex.translator.ui.text.copy
import com.modulex.translator.ui.text.share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

sealed interface CameraPhase {
    data object Viewfinder : CameraPhase
    data object Reading : CameraPhase
    data class Result(val recognized: String, val lowConfidence: Boolean, val translated: String?, val error: TranslationError?, val translating: Boolean) : CameraPhase
    data object NoText : CameraPhase
    data class Failed(val message: Int) : CameraPhase
}

data class CameraUiState(
    val source: Language = LanguageRegistry.require("ar"),
    val target: Language = LanguageRegistry.require("en"),
    val phase: CameraPhase = CameraPhase.Viewfinder,
    val photo: Bitmap? = null,
    val flash: Boolean = false,
    val ocrLanguages: List<String> = emptyList(),
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(CameraUiState(
        source = LanguageRegistry.get(c.settings.settings.value.lastSource) ?: LanguageRegistry.require("ar"),
        target = LanguageRegistry.get(c.settings.settings.value.lastTarget) ?: LanguageRegistry.require("en"),
    ))
    val state = _state.asStateFlow()

    fun setSource(l: Language) = _state.update { it.copy(source = l) }
    fun setTarget(l: Language) = _state.update { it.copy(target = l) }
    fun swap() = _state.update { it.copy(source = it.target, target = it.source) }
    fun toggleFlash() = _state.update { it.copy(flash = !it.flash) }
    fun retake() = _state.update { it.copy(phase = CameraPhase.Viewfinder, photo = null) }

    /** Recognises text in [bitmap] locally, then translates it. The bitmap is dropped on retake. */
    fun process(bitmap: Bitmap) {
        val s = _state.value
        _state.update { it.copy(phase = CameraPhase.Reading, photo = bitmap) }
        viewModelScope.launch {
            // Source language data plus English, which is common on mixed signage.
            val wanted = (s.source.ocrPacks + "eng").toSet()
            val installed = c.packs.installedOcrModels()
            val models = wanted.filter { it in installed }.toSet()
            if (s.source.ocrPacks.none { it in installed } || !c.engines.ensureOcr(models)) {
                _state.update { it.copy(phase = CameraPhase.Failed(R.string.camera_ocr_unsupported)) }
                return@launch
            }
            _state.update { it.copy(ocrLanguages = models.sorted()) }
            val rgba = withContext(Dispatchers.Default) { toRgba(bitmap) }
            val r = runCatching { c.engines.recognize(rgba, bitmap.width, bitmap.height) }.getOrElse {
                _state.update { st -> st.copy(phase = CameraPhase.Failed(R.string.err_generic)) }; return@launch
            }
            val text = r.ocr.text.trim()
            if (text.isEmpty()) { _state.update { it.copy(phase = CameraPhase.NoText) }; return@launch }
            val low = r.ocr.meanConfidence < 70
            _state.update { it.copy(phase = CameraPhase.Result(text, low, null, null, true)) }
            translate(text, low)
        }
    }

    fun translate(text: String, low: Boolean = false) {
        val s = _state.value
        _state.update { it.copy(phase = CameraPhase.Result(text, low, null, null, true)) }
        viewModelScope.launch {
            val r = c.engines.translate(text, s.source, s.target) { p ->
                _state.update { st -> (st.phase as? CameraPhase.Result)?.let { st.copy(phase = it.copy(translated = p)) } ?: st }
            }
            _state.update { st ->
                val ph = st.phase as? CameraPhase.Result ?: return@update st
                when (r) {
                    is TranslationResult.Success -> st.copy(phase = ph.copy(translated = r.text, translating = false))
                    is TranslationResult.Failure -> st.copy(phase = ph.copy(translated = null, error = r.error, translating = false))
                    TranslationResult.Cancelled -> st.copy(phase = ph.copy(translating = false))
                }
            }
            if (r is TranslationResult.Success && c.settings.settings.value.saveHistory) {
                c.history.add(HistoryEntry(timestamp = System.currentTimeMillis(), kind = HistoryKind.CAMERA, sourceLang = s.source.code,
                    targetLang = s.target.code, sourceText = text, translatedText = r.text))
            }
        }
    }

    fun loadFromGallery(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > MAX_DIM) decoder.setTargetSampleSize(longest / MAX_DIM + 1)
                    }
                }.getOrNull()
            }
            if (bmp == null) _state.update { it.copy(phase = CameraPhase.Failed(R.string.err_generic)) } else process(bmp)
        }
    }

    companion object {
        const val MAX_DIM = 2400

        fun toRgba(bitmap: Bitmap): ByteArray {
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            // ARGB_8888 is stored as R,G,B,A bytes in memory.
            val buf = ByteBuffer.allocate(argb.byteCount)
            argb.copyPixelsToBuffer(buf)
            return buf.array()
        }

        fun fromProxy(image: ImageProxy): Bitmap {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(raw.width, raw.height))
            val m = Matrix().apply { postRotate(rot.toFloat()); postScale(scale, scale) }
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out !== raw) raw.recycle()
            return out
        }
    }
}

@Composable
fun CameraTranslatorScreen(onBack: () -> Unit, onManagePacks: () -> Unit, vm: CameraViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packs by context.container.packs.states.collectAsStateWithLifecycle()
    val readiness = remember(packs) { context.container.packs.readiness() }
    var picker by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::loadFromGallery) }

    ScreenScaffold(stringResource(R.string.home_camera), onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageBar(st.source, st.target, { picker = "source" }, { picker = "target" }, onSwap = vm::swap)
            when (val ph = st.phase) {
                CameraPhase.Viewfinder -> PermissionGate(listOf(Manifest.permission.CAMERA), stringResource(R.string.err_camera_denied)) {
                    Viewfinder(flash = st.flash, onFlash = vm::toggleFlash, onCaptured = vm::process,
                        onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    st.photo?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), contentScale = ContentScale.Fit) }
                    when (ph) {
                        CameraPhase.Reading -> LoadingBlock(stringResource(R.string.camera_processing))
                        CameraPhase.NoText -> Banner(stringResource(R.string.camera_no_text), BannerKind.WARNING)
                        is CameraPhase.Failed -> if (ph.message == R.string.camera_ocr_unsupported) {
                            Banner(stringResource(ph.message, st.source.nativeName), BannerKind.INFO, stringResource(R.string.action_manage_packs), onManagePacks)
                        } else Banner(stringResource(ph.message), BannerKind.ERROR)
                        is CameraPhase.Result -> ResultCards(ph, st, onRetry = { vm.translate(ph.recognized, ph.lowConfidence) }, onManagePacks)
                        else -> Unit
                    }
                    if (st.ocrLanguages.isNotEmpty()) Text(stringResource(R.string.camera_ocr_languages, st.ocrLanguages.joinToString(" + ")),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::retake, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, null); Text("  " + stringResource(R.string.camera_recapture))
                    }
                }
            }
        }
    }

    picker?.let { which ->
        LanguagePickerDialog(
            options = readiness.all(), allowDetect = false,
            onPick = { l -> l?.let { if (which == "source") vm.setSource(it) else vm.setTarget(it) }; picker = null },
            onDismiss = { picker = null },
            capability = { if (which == "source") it.ocr else it.translation },
        )
    }
}

@Composable
private fun ResultCards(ph: CameraPhase.Result, st: CameraUiState, onRetry: () -> Unit, onManagePacks: () -> Unit) {
    val context = LocalContext.current
    if (ph.lowConfidence) Banner(stringResource(R.string.camera_low_confidence), BannerKind.WARNING)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.camera_recognized), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { LanguageText(ph.recognized, st.source, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    when {
        ph.error == TranslationError.MODEL_NOT_INSTALLED ->
            Banner(stringResource(R.string.err_model_missing), BannerKind.INFO, stringResource(R.string.action_install), onManagePacks)
        ph.error != null -> Banner(stringResource(ph.error.messageRes()), BannerKind.ERROR, stringResource(R.string.action_retry), onRetry)
        ph.translated != null -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.translation), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { LanguageText(ph.translated, st.target) }
                Row {
                    IconButton(onClick = { copy(context, ph.translated) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) }
                    IconButton(onClick = { share(context, ph.translated) }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                }
            }
        }
        ph.translating -> LoadingBlock(stringResource(R.string.translating))
    }
}

/** CameraX preview + still capture. The camera is bound only while this composable is shown. */
@Composable
private fun Viewfinder(flash: Boolean, onFlash: () -> Unit, onCaptured: (Bitmap) -> Unit, onGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var busy by remember { mutableStateOf(false) }
    capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture) }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            IconButton(onClick = onFlash, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, stringResource(R.string.camera_flash))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.camera_gallery)) }
            FilledIconButton(onClick = {
                if (busy) return@FilledIconButton
                busy = true
                capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bmp = image.use { CameraViewModel.fromProxy(it) }
                        busy = false
                        onCaptured(bmp)
                    }
                    override fun onError(exception: ImageCaptureException) { busy = false }
                })
            }, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Filled.CameraAlt, stringResource(R.string.camera_capture))
            }
            Button(onClick = onGallery) { Text(stringResource(R.string.camera_gallery)) }
        }
    }
}
