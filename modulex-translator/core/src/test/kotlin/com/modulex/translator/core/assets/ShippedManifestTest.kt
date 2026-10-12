package com.modulex.translator.core.assets

import com.modulex.translator.core.language.LanguageRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validates the manifest that ships in the APK. */
class ShippedManifestTest {
    private val file = File("../app/src/main/assets/asset_manifest.json")

    @Test
    fun shippedManifestIsValidAndHonest() {
        val m = AssetManifest.parse(file.readText())
        // Bundled packs are pinned and their files exist in the APK assets with matching hashes.
        for (p in m.packs.filter { it.bundled }) {
            assertTrue(p.isPinned, "${p.id} must be pinned")
            for (f in p.files) {
                val asset = File("../app/src/main/assets/tessdata/${f.name}")
                assertEquals(f.sizeBytes, asset.length(), f.name)
                assertEquals(f.sha256, AssetStore.sha256(asset), f.name)
            }
        }
        // Every downloadable pack is fully pinned to an HTTPS URL.
        for (p in m.packs.filter { it.files.any { f -> f.url != null } }) assertTrue(p.isDownloadable, p.id)
        // Every OCR mapping in the language registry is served by a pack.
        val ocrNames = m.packs.filter { it.kind == PackKind.OCR_LANGUAGE }.mapNotNull { it.meta["traineddata"] }.toSet()
        for (l in LanguageRegistry.all) for (n in l.ocrPacks) assertTrue(n in ocrNames, "${l.code}: $n")
        // Packs without verified files are not offered for download.
        for (p in m.packs.filter { !it.isPinned }) assertTrue(!p.isDownloadable, p.id)
    }
}
