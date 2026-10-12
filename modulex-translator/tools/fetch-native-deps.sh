#!/usr/bin/env bash
# Fetches the native dependency sources at the exact commits ModuleX was built and tested with.
# Usage: tools/fetch-native-deps.sh [third_party_dir]
set -euo pipefail

DEST="${1:-$(cd "$(dirname "$0")/.." && pwd)/third_party}"
mkdir -p "$DEST"

# name | repository | commit
DEPS=(
  "llama.cpp|https://github.com/ggml-org/llama.cpp|f2cc7282ce0042641df55e0e563b75e824e2b647"
  "whisper.cpp|https://github.com/modulix0025-dev/whisper.cpp|7e26d3b0d5bced6c8217ef09710ec2f969001241"
  "tesseract|https://github.com/modulix0025-dev/tesseract|b2544450ca01407259ed53c870b7e5e4c67f7789"
  "leptonica|https://github.com/DanBloomberg/leptonica|8ad618f103972eed12f195499b92cff1ab37067b"
  "piper1-gpl|https://github.com/modulix0025-dev/piper1-gpl|35b0f1ec67b289de553555645140e48f1f104b2d"
  # The espeak-ng revision libpiper itself pins (libpiper/CMakeLists.txt).
  "espeak-ng|https://github.com/espeak-ng/espeak-ng|212928b394a96e8fd2096616bfd54e17845c48f6"
)

for entry in "${DEPS[@]}"; do
  IFS='|' read -r name url commit <<<"$entry"
  dir="$DEST/$name"
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ]; then
    echo "ok      $name @ $commit"
    continue
  fi
  echo "fetch   $name @ $commit"
  mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote remove origin 2>/dev/null || true
  git -C "$dir" remote add origin "$url"
  GIT_LFS_SKIP_SMUDGE=1 git -C "$dir" fetch -q --depth 1 origin "$commit"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
  test "$(git -C "$dir" rev-parse HEAD)" = "$commit"
done
echo "All native dependencies are at their pinned commits in $DEST"
