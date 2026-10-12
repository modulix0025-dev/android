package com.modulex.translator.ui.conversation

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Renders [text] as a QR code (generated locally with ZXing). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val w = m.width
        val h = m.height
        val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    Image(bmp.asImageBitmap(), contentDescription = null, modifier = modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
}

/** Camera QR scanner. Frames are analysed in memory and discarded; nothing is stored. */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    }
    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                image.use {
                    if (done.get()) return@use
                    val plane = it.planes[0]
                    val buf = plane.buffer
                    val data = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                    val src = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                    reader.reset()
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { onResult(text) }
                    }
                }
            }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    Box(modifier.fillMaxWidth().aspectRatio(1f)) { AndroidView({ previewView }, Modifier.fillMaxSize()) }
}
