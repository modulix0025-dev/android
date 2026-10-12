#!/usr/bin/env python3
"""Checks that a GGUF file can run on the pinned (mainline) llama.cpp before it is pinned/shipped.

Reports architecture, tokenizer, chat template presence and every tensor quantization type, and
fails if any tensor type is unknown to the pinned ggml (e.g. vendor-specific STQ low-bit types that
need llama.cpp PR #22836, which is not merged).

Usage: python3 tools/check_gguf.py third_party/llama.cpp/gguf-py model.gguf [expected_arch]
"""
import collections
import sys

sys.path.insert(0, sys.argv[1])
from gguf import GGUFReader  # noqa: E402
from gguf.constants import GGMLQuantizationType  # noqa: E402

path = sys.argv[2]
expected = sys.argv[3] if len(sys.argv) > 3 else "hunyuan-dense"

r = GGUFReader(path)


def kv(key):
    f = r.fields.get(key)
    if f is None:
        return None
    v = f.parts[f.data[0]]
    try:
        return bytes(v).decode("utf-8")
    except Exception:
        return v.tolist()


arch = kv("general.architecture")
print("architecture :", arch)
print("name         :", kv("general.name"))
print("tokenizer    :", kv("tokenizer.ggml.model"), "/ pre:", kv("tokenizer.ggml.pre"))
print("chat template:", "yes" if r.fields.get("tokenizer.chat_template") else "NO")

known = {t.value for t in GGMLQuantizationType}
types = collections.Counter()
unknown = collections.Counter()
for t in r.tensors:
    tv = int(t.tensor_type)
    if tv in known:
        types[GGMLQuantizationType(tv).name] += 1
    else:
        unknown[tv] += 1
print("tensor types :", dict(types))
ok = True
if unknown:
    print("UNKNOWN tensor types (not supported by this llama.cpp):", dict(unknown))
    ok = False
if arch != expected:
    print(f"architecture mismatch: expected {expected}")
    ok = False
print("RESULT:", "compatible with pinned llama.cpp" if ok else "NOT compatible")
sys.exit(0 if ok else 1)
