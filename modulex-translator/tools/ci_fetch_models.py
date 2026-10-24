#!/usr/bin/env python3
"""CI helper: download and verify the real model files, and emit pin records.

For each model it resolves the repository's current commit, picks the file, downloads it at that
commit, re-computes SHA-256 and checks it against the hub's LFS hash and size. It prints a JSON
pin record (also written to --out) with immutable `resolve/<commit>/` URLs and the model card's
declared license, so the asset manifest can be pinned from verified data.

Usage: ci_fetch_models.py --dest DIR --out pins.json [--gguf-file NAME]
"""
import argparse
import hashlib
import json
import os
import re
import sys

from huggingface_hub import HfApi, hf_hub_download

TRANSLATION_REPO = "tencent/Hy-MT2-1.8B-GGUF"
SPEECH_REPO = "ggerganov/whisper.cpp"
SPEECH_FILE = "ggml-base.bin"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(api, repo, choose, dest):
    info = api.model_info(repo, files_metadata=True)
    files = {s.rfilename: s for s in info.siblings}
    print(f"{repo}@{info.sha}: {len(files)} files")
    for name, s in sorted(files.items()):
        size = getattr(s, "size", None)
        print(f"  {name}  {size if size is not None else '?'} bytes")
    name = choose(sorted(files))
    if name is None:
        sys.exit(f"no suitable file in {repo}")
    meta = files[name]
    lfs = getattr(meta, "lfs", None)
    expected_sha = None
    if lfs is not None:
        expected_sha = getattr(lfs, "sha256", None) or (lfs.get("sha256") if isinstance(lfs, dict) else None)
    path = hf_hub_download(repo, name, revision=info.sha, local_dir=dest)
    got = sha256(path)
    size = os.path.getsize(path)
    if expected_sha and expected_sha != got:
        sys.exit(f"SHA-256 mismatch for {name}: hub={expected_sha} local={got}")
    if meta.size and meta.size != size:
        sys.exit(f"size mismatch for {name}: hub={meta.size} local={size}")
    card = getattr(info, "card_data", None)
    license_ = None
    if card is not None:
        license_ = card.get("license") if hasattr(card, "get") else getattr(card, "license", None)
    return {
        "repo": repo, "commit": info.sha, "file": name, "path": path, "sizeBytes": size, "sha256": got,
        "hubSha256": expected_sha, "url": f"https://huggingface.co/{repo}/resolve/{info.sha}/{name}",
        "cardLicense": license_, "tags": [t for t in (info.tags or []) if t.startswith("license")],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--gguf-file", default="")
    a = ap.parse_args()
    api = HfApi()

    def pick_gguf(names):
        if a.gguf_file:
            return a.gguf_file if a.gguf_file in names else None
        ggufs = [n for n in names if n.lower().endswith(".gguf")]
        for pattern in (r"q4_k_m", r"q4_k", r"q5_k_m", r"q8_0"):
            hit = [n for n in ggufs if re.search(pattern, n.lower())]
            if hit:
                return hit[0]
        return None

    pins = {
        "translation": fetch(api, TRANSLATION_REPO, pick_gguf, a.dest),
        "speech": fetch(api, SPEECH_REPO, lambda names: SPEECH_FILE if SPEECH_FILE in names else None, a.dest),
    }
    with open(a.out, "w") as f:
        json.dump(pins, f, indent=1)
    print("PINS_JSON_BEGIN")
    print(json.dumps(pins, indent=1))
    print("PINS_JSON_END")


if __name__ == "__main__":
    main()
