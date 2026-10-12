#!/usr/bin/env python3
"""Writes a tiny *random-weight* GGUF using the same architecture as the translation model
(hunyuan-dense), a byte-level BPE vocabulary and a Hunyuan-style Jinja chat template.

It exists only to exercise the native pipeline end to end on machines without the real model:
GGUF load -> chat template -> tokenization -> prefill/decode -> streaming -> cancellation.
Its output is meaningless text by construction and must never be used as a translation result.

Usage: python3 tools/make_pipeline_fixture.py <llama.cpp/gguf-py dir> <out.gguf>
"""
import sys

import numpy as np

sys.path.insert(0, sys.argv[1])
import gguf  # noqa: E402

out = sys.argv[2]
arch = "hunyuan-dense"
n_embd, n_head, n_kv, n_layer, n_ff, n_ctx = 64, 4, 2, 2, 128, 512
head = n_embd // n_head


def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return [chr(c) for _, c in sorted(zip(bs, cs))]


byte_tokens = bytes_to_unicode()
specials = ["<｜hy_begin▁of▁sentence｜>", "<｜hy_User｜>", "<｜hy_Assistant｜>",
            "<｜hy_place▁holder▁no▁2｜>", "<｜hy_place▁holder▁no▁3｜>"]
tokens = byte_tokens + ["Ġt", "Ġa"] + specials
types = [gguf.TokenType.NORMAL] * (len(tokens) - len(specials)) + [gguf.TokenType.CONTROL] * len(specials)
n_vocab = len(tokens)
bos = tokens.index(specials[0])
eos = tokens.index(specials[3])

template = (
    "{{ bos_token }}{% for m in messages %}"
    "{% if m['role'] == 'system' %}{{ m['content'] }}<｜hy_place▁holder▁no▁3｜>"
    "{% elif m['role'] == 'user' %}<｜hy_User｜>{{ m['content'] }}"
    "{% elif m['role'] == 'assistant' %}<｜hy_Assistant｜>{{ m['content'] }}<｜hy_place▁holder▁no▁2｜>{% endif %}"
    "{% endfor %}{% if add_generation_prompt %}<｜hy_Assistant｜>{% endif %}"
)

w = gguf.GGUFWriter(out, arch)
w.add_name("ModuleX pipeline fixture (random weights)")
w.add_context_length(n_ctx)
w.add_embedding_length(n_embd)
w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff)
w.add_head_count(n_head)
w.add_head_count_kv(n_kv)
w.add_layer_norm_rms_eps(1e-5)
w.add_rope_freq_base(10000.0)
w.add_file_type(gguf.LlamaFileType.ALL_F32)
w.add_tokenizer_model("gpt2")
w.add_tokenizer_pre("hunyuan-dense")
w.add_token_list(tokens)
w.add_token_types(types)
w.add_token_merges(["Ġ t", "Ġ a"])
w.add_bos_token_id(bos)
w.add_eos_token_id(eos)
w.add_add_bos_token(False)
w.add_chat_template(template)

rng = np.random.default_rng(1234)


def t(name, *shape):
    # GGUF/ggml shapes are listed innermost-first; numpy wants outermost-first.
    w.add_tensor(name, (rng.standard_normal(tuple(reversed(shape))) * 0.05).astype(np.float32))


t("token_embd.weight", n_embd, n_vocab)
w.add_tensor("output_norm.weight", np.ones(n_embd, dtype=np.float32))
for i in range(n_layer):
    w.add_tensor(f"blk.{i}.attn_norm.weight", np.ones(n_embd, dtype=np.float32))
    t(f"blk.{i}.attn_q.weight", n_embd, head * n_head)
    t(f"blk.{i}.attn_k.weight", n_embd, head * n_kv)
    t(f"blk.{i}.attn_v.weight", n_embd, head * n_kv)
    t(f"blk.{i}.attn_output.weight", head * n_head, n_embd)
    w.add_tensor(f"blk.{i}.attn_k_norm.weight", np.ones(head, dtype=np.float32))
    w.add_tensor(f"blk.{i}.attn_q_norm.weight", np.ones(head, dtype=np.float32))
    w.add_tensor(f"blk.{i}.ffn_norm.weight", np.ones(n_embd, dtype=np.float32))
    t(f"blk.{i}.ffn_gate.weight", n_embd, n_ff)
    t(f"blk.{i}.ffn_down.weight", n_ff, n_embd)
    t(f"blk.{i}.ffn_up.weight", n_embd, n_ff)

w.write_header_to_file()
w.write_kv_data_to_file()
w.write_tensors_to_file()
w.close()
print(out)
