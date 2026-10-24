#!/usr/bin/env python3
"""Downloads pinned packs from the asset manifest for a "full" APK build.

Writes DEST/models/<pack id>/<file>, checking each file's size and SHA-256 against the manifest
pin, then build with:  ./gradlew :app:assembleDebug -Pmodulex.bundledModels=DEST

Usage: fetch_bundled_models.py --dest DIR [--pack ID ...]
Without --pack, every pinned pack that is not already inside the APK is fetched (unpinned packs are
listed and skipped: nothing unverified is ever bundled).
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

MANIFEST = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "asset_manifest.json")


def fetch(url, out, expected_sha, expected_size):
    if os.path.exists(out) and os.path.getsize(out) == expected_size and sha256(out) == expected_sha:
        print(f"ok (cached) {out}")
        return
    tmp = out + ".part"
    h = hashlib.sha256()
    n = 0
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            f.write(chunk)
            n += len(chunk)
    if n != expected_size:
        sys.exit(f"size mismatch for {url}: expected {expected_size}, got {n}")
    if h.hexdigest() != expected_sha:
        sys.exit(f"SHA-256 mismatch for {url}: expected {expected_sha}, got {h.hexdigest()}")
    os.replace(tmp, out)
    print(f"ok {out} {n} bytes sha256={expected_sha}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", required=True)
    ap.add_argument("--pack", action="append", dest="packs")
    a = ap.parse_args()
    with open(MANIFEST) as f:
        packs = {p["id"]: p for p in json.load(f)["packs"]}
    def pinned(p):
        return all(f.get("url") and f.get("sha256") and f.get("sizeBytes") for f in p["files"])

    if a.packs:
        wanted = a.packs
    else:
        wanted = [pid for pid, p in packs.items() if not p.get("bundled") and pinned(p)]
        for pid, p in packs.items():
            if not p.get("bundled") and not pinned(p):
                print(f"skip {pid}: not pinned")
    total = 0
    for pid in wanted:
        p = packs.get(pid) or sys.exit(f"unknown pack {pid}")
        total += sum(f.get("sizeBytes") or 0 for f in p["files"])
        d = os.path.join(a.dest, "models", pid)
        os.makedirs(d, exist_ok=True)
        for fl in p["files"]:
            if not (fl.get("url") and fl.get("sha256") and fl.get("sizeBytes")):
                sys.exit(f"{pid}/{fl['name']} is not pinned; refusing to bundle it")
            fetch(fl["url"], os.path.join(d, fl["name"]), fl["sha256"], fl["sizeBytes"])
    print(f"bundled {len(wanted)} packs, {total} bytes")


if __name__ == "__main__":
    main()
