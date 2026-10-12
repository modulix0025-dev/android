#!/usr/bin/env python3
"""Generates app/src/main/assets/asset_manifest.json.

OCR packs are pinned from a local checkout of tessdata_fast at a known commit (size + SHA-256
computed from the real files). Model packs whose files could not be inspected are emitted
*unpinned* (no URL, no hash): the app refuses to download them and labels them accordingly until
tools/pin_assets.py fills in verified values.

Usage: tools/gen_asset_manifest.py <tessdata_fast checkout> [existing manifest to keep pins from]
"""
import hashlib
import json
import os
import subprocess
import sys

TESS = sys.argv[1]
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "asset_manifest.json")
TESS_REPO = "modulix0025-dev/tessdata_fast"
commit = subprocess.check_output(["git", "-C", TESS, "rev-parse", "HEAD"], text=True).strip()

# app language code -> traineddata names (mirrors LanguageRegistry.ocrPacks)
OCR = {
    "en": ["eng"], "ar": ["ara"], "zh": ["chi_sim"], "zh-Hant": ["chi_tra"], "yue": ["chi_tra"], "fr": ["fra"],
    "pt": ["por"], "es": ["spa"], "ja": ["jpn"], "tr": ["tur"], "ru": ["rus"], "ko": ["kor"], "th": ["tha"],
    "it": ["ita"], "de": ["deu"], "vi": ["vie"], "ms": ["msa"], "id": ["ind"], "tl": ["fil"], "hi": ["hin"],
    "pl": ["pol"], "cs": ["ces"], "nl": ["nld"], "km": ["khm"], "my": ["mya"], "fa": ["fas"], "gu": ["guj"],
    "ur": ["urd"], "te": ["tel"], "mr": ["mar"], "he": ["heb"], "bn": ["ben"], "ta": ["tam"], "uk": ["ukr"],
    "bo": ["bod"], "kk": ["kaz"], "mn": ["mon"], "ug": ["uig"],
}
BUNDLED = {"eng", "ara"}

by_name = {}
for code, names in OCR.items():
    for n in names:
        by_name.setdefault(n, []).append(code)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


packs = []
packs.append({
    "id": "translation-core",
    "kind": "translation_model",
    "title": "Offline Translation Engine",
    "version": "hy-mt2-1.8b-q4_k_m",
    "languages": [],
    "files": [{"name": "model.gguf"}],
    "upstream": "tencent/Hy-MT2-1.8B-GGUF (Hugging Face)",
    "license": "Apache-2.0 (code repository: Tencent-Hunyuan/Hy-MT2; confirm the model card license before redistribution)",
    "attribution": "Hy-MT2 translation model by Tencent Hunyuan",
    "meta": {
        "expected_architecture": "hunyuan-dense",
        "quantization": "Q4_K_M (candidate; requires no custom kernels)",
        "pin_with": "tools/pin_assets.py --repo tencent/Hy-MT2-1.8B-GGUF --file <exact Q4_K_M filename>",
        "excluded_variants": "2bit/1.25bit GGUFs need the STQ kernel (llama.cpp PR #22836, not in mainline)",
    },
})
packs.append({
    "id": "speech-base",
    "kind": "speech_model",
    "title": "Speech Recognition Engine",
    "version": "whisper-base-multilingual",
    "languages": [],
    "files": [{"name": "ggml-base.bin"}],
    "upstream": "ggerganov/whisper.cpp ggml-base.bin (Hugging Face)",
    "license": "MIT (Whisper weights by OpenAI; whisper.cpp conversion)",
    "attribution": "Whisper speech recognition model by OpenAI, ggml conversion by the whisper.cpp project",
    "meta": {"pin_with": "tools/pin_assets.py --repo ggerganov/whisper.cpp --file ggml-base.bin"},
})
packs.append({
    "id": "speech-small",
    "kind": "speech_model",
    "title": "Speech Recognition Engine (higher accuracy)",
    "version": "whisper-small-multilingual-q5_1",
    "languages": [],
    "files": [{"name": "ggml-small-q5_1.bin"}],
    "upstream": "ggerganov/whisper.cpp ggml-small-q5_1.bin (Hugging Face)",
    "license": "MIT (Whisper weights by OpenAI; whisper.cpp conversion)",
    "attribution": "Whisper speech recognition model by OpenAI, ggml conversion by the whisper.cpp project",
    "meta": {"pin_with": "tools/pin_assets.py --repo ggerganov/whisper.cpp --file ggml-small-q5_1.bin"},
})

for name in sorted(by_name):
    path = os.path.join(TESS, name + ".traineddata")
    if not os.path.isfile(path):
        print("missing", path, file=sys.stderr)
        continue
    f = {
        "name": name + ".traineddata",
        "sizeBytes": os.path.getsize(path),
        "sha256": sha256(path),
    }
    if name not in BUNDLED:
        f["url"] = f"https://raw.githubusercontent.com/{TESS_REPO}/{commit}/{name}.traineddata"
    packs.append({
        "id": "ocr-" + name.replace("_", "-"),
        "kind": "ocr_language",
        "title": "Local Text Recognition",
        "version": "tessdata_fast@" + commit[:12],
        "languages": sorted(by_name[name]),
        "files": [f],
        "upstream": f"tessdata_fast {name}.traineddata @ {commit}",
        "license": "Apache-2.0",
        "attribution": "Tesseract OCR trained data (tessdata_fast), Apache License 2.0",
        "bundled": name in BUNDLED,
        "meta": {"traineddata": name},
    })

# Offline voices (Piper, GPL-3.0 runtime; voices have individual licenses). Unpinned candidates.
VOICES = [
    ("en", "en_US-lessac-medium", "en/en_US/lessac/medium"),
    ("ar", "ar_JO-kareem-medium", "ar/ar_JO/kareem/medium"),
]
for code, voice, path in VOICES:
    packs.append({
        "id": "voice-" + voice.lower().replace("_", "-"),
        "kind": "tts_voice",
        "title": "Offline Voice Engine",
        "version": voice,
        "languages": [code],
        "files": [{"name": "voice.onnx"}, {"name": "voice.onnx.json"}],
        "upstream": f"rhasspy/piper-voices {path}/{voice}.onnx (Hugging Face)",
        "license": "See the voice MODEL_CARD; verify before redistribution",
        "attribution": f"Piper voice {voice}",
        "meta": {"pin_with": f"tools/pin_assets.py --repo rhasspy/piper-voices --file {path}/{voice}.onnx --file {path}/{voice}.onnx.json"},
    })
packs.append({
    "id": "voice-runtime-data",
    "kind": "tts_runtime_data",
    "title": "Offline Voice Engine data",
    "version": "espeak-ng-data@212928b3",
    "languages": [],
    "files": [{"name": "espeak-ng-data.zip"}],
    "upstream": "espeak-ng phoneme data built by tools/build-espeak-data.sh",
    "license": "GPL-3.0-or-later",
    "attribution": "eSpeak NG",
    "meta": {"pin_with": "host the zip produced by tools/build-espeak-data.sh and pin it with tools/pin_assets.py --url"},
})

manifest = {"schema": 1, "packs": packs}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=1)
print(f"wrote {len(packs)} packs to {os.path.normpath(OUT)} (tessdata commit {commit})")
