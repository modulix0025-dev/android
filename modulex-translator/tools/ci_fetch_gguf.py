#!/usr/bin/env python3
"""CI helper: download one file of tencent/Hy-MT2-1.8B-GGUF at a fixed commit and verify its SHA-256
and size against the hub's LFS metadata. Prints the local path.

Usage: ci_fetch_gguf.py --file Hy-MT2-1.8B-Q8_0.gguf --dest DIR [--print-readme]
"""
import argparse
import hashlib
import os
import sys

from huggingface_hub import HfApi, hf_hub_download

REPO = "tencent/Hy-MT2-1.8B-GGUF"
COMMIT = "a0c709d9fac510f2c807aa3af52872340dc37a4a"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True)
    ap.add_argument("--dest", required=True)
    ap.add_argument("--print-readme", action="store_true")
    a = ap.parse_args()
    info = HfApi().model_info(REPO, revision=COMMIT, files_metadata=True)
    meta = {s.rfilename: s for s in info.siblings}[a.file]
    path = hf_hub_download(REPO, a.file, revision=COMMIT, local_dir=a.dest)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if meta.lfs is None or h.hexdigest() != meta.lfs.sha256 or os.path.getsize(path) != meta.size:
        sys.exit(f"verification failed for {a.file}")
    print(f"verified {a.file} {meta.size} bytes sha256={h.hexdigest()}", file=sys.stderr)
    if a.print_readme:
        readme = hf_hub_download(REPO, "README.md", revision=COMMIT, local_dir=a.dest)
        print("README_BEGIN", file=sys.stderr)
        print(open(readme, encoding="utf-8").read(), file=sys.stderr)
        print("README_END", file=sys.stderr)
    print(path)


if __name__ == "__main__":
    main()
