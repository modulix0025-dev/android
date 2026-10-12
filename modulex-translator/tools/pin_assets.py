#!/usr/bin/env python3
"""Pins a pack in app/src/main/assets/asset_manifest.json to immutable, verified files.

For Hugging Face repositories it resolves the current commit, reads each file's LFS SHA-256 and
size from the API, then downloads the file and re-computes the SHA-256 locally (both must agree)
before writing  url = https://huggingface.co/<repo>/resolve/<commit>/<file>.

Examples:
  tools/pin_assets.py --pack translation-core --repo tencent/Hy-MT2-1.8B-GGUF --file Hy-MT2-1.8B-Q4_K_M.gguf --as model.gguf
  tools/pin_assets.py --pack speech-base --repo ggerganov/whisper.cpp --file ggml-base.bin
  tools/pin_assets.py --pack voice-runtime-data --url https://example.org/espeak-ng-data.zip --as espeak-ng-data.zip

Never edit hashes by hand. A pack is only downloadable in the app once every file is pinned.
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile
import urllib.request

MANIFEST = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "asset_manifest.json")


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def download_and_hash(url):
    h = hashlib.sha256()
    n = 0
    with urllib.request.urlopen(url, timeout=120) as r, tempfile.TemporaryFile() as tmp:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            n += len(chunk)
    return h.hexdigest(), n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--repo")
    ap.add_argument("--revision", default="main")
    ap.add_argument("--file", action="append", default=[])
    ap.add_argument("--as", dest="names", action="append", default=[], help="file name inside the pack (defaults to basename)")
    ap.add_argument("--url", action="append", default=[], help="direct HTTPS URL (immutable!) instead of --repo/--file")
    ap.add_argument("--version")
    args = ap.parse_args()

    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    pack = next((p for p in manifest["packs"] if p["id"] == args.pack), None)
    if pack is None:
        sys.exit(f"unknown pack {args.pack}")

    entries = []
    if args.repo:
        commit = get_json(f"https://huggingface.co/api/models/{args.repo}/revision/{args.revision}")["sha"]
        for i, path in enumerate(args.file):
            folder = os.path.dirname(path)
            listing = get_json(f"https://huggingface.co/api/models/{args.repo}/tree/{commit}/{folder}".rstrip("/"))
            meta = next((e for e in listing if e["path"] == path), None)
            if meta is None:
                sys.exit(f"{path} not found in {args.repo}@{commit}")
            lfs = meta.get("lfs") or {}
            url = f"https://huggingface.co/{args.repo}/resolve/{commit}/{path}"
            entries.append((args.names[i] if i < len(args.names) else os.path.basename(path), url, lfs.get("oid"), meta.get("size")))
        pack["upstream"] = f"{args.repo}@{commit}: " + ", ".join(args.file)
    else:
        for i, url in enumerate(args.url):
            if not url.startswith("https://"):
                sys.exit("only https URLs")
            entries.append((args.names[i] if i < len(args.names) else os.path.basename(url), url, None, None))

    files = []
    for name, url, expected_sha, expected_size in entries:
        print(f"downloading {url} to verify ...", file=sys.stderr)
        sha, size = download_and_hash(url)
        if expected_sha and expected_sha != sha:
            sys.exit(f"SHA-256 mismatch for {name}: api={expected_sha} downloaded={sha}")
        if expected_size and expected_size != size:
            sys.exit(f"size mismatch for {name}: api={expected_size} downloaded={size}")
        files.append({"name": name, "url": url, "sizeBytes": size, "sha256": sha})
        print(f"pinned {name}: {size} bytes sha256={sha}", file=sys.stderr)

    pack["files"] = files
    if args.version:
        pack["version"] = args.version
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("manifest updated; rebuild the app and run :core:test (manifest validation)")


if __name__ == "__main__":
    main()
