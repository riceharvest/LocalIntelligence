#!/usr/bin/env python3
"""Range-probe a HF GGUF, parse its tensor table from the first N bytes, and
report exact weights bytes / parameters / bits-per-weight plus the KV geometry.

No full download: the header + KV metadata + tensor table live in the first
~2 MiB of a GGUF, and every byte after that is tensor payload we do not need
for the ratio.
"""
import json
import struct
import subprocess
import sys
from collections import defaultdict

# block_elements, block_bytes straight from sizeof() of the real ggml block
# structs (derived by compiling /tmp/blocksize.c against ggml-common.h of
# llama.cpp tag b4661).
BLOCKS = {
    0: ("F32", 1, 4), 1: ("F16", 1, 2), 2: ("Q4_0", 32, 18), 3: ("Q4_1", 32, 20),
    4: ("Q4_2", 32, 22), 5: ("Q4_3", 32, 24), 6: ("Q5_0", 32, 22), 7: ("Q5_1", 32, 24),
    8: ("Q8_0", 32, 34), 9: ("Q8_1", 32, 36), 10: ("Q2_K", 256, 84), 11: ("Q3_K", 256, 110),
    12: ("Q4_K", 256, 144), 13: ("Q5_K", 256, 176), 14: ("Q6_K", 256, 210), 15: ("Q8_K", 256, 292),
    16: ("IQ2_XXS", 256, 66), 17: ("IQ2_XS", 256, 74), 18: ("IQ3_XXS", 256, 98), 19: ("IQ1_S", 256, 50),
    20: ("IQ4_NL", 32, 18), 21: ("IQ3_S", 256, 110), 22: ("IQ2_S", 256, 82), 23: ("IQ4_XS", 256, 136),
    24: ("I8", 1, 1), 25: ("I16", 1, 2), 26: ("I32", 1, 4), 27: ("I64", 1, 8), 28: ("F64", 1, 8),
    29: ("IQ1_M", 256, 56), 30: ("BF16", 1, 2), 31: ("Q4_0_4_4", 32, 18), 32: ("Q4_0_4_8", 32, 20),
    33: ("Q4_0_8_8", 32, 24), 34: ("TQ1_0", 256, 54), 35: ("TQ2_0", 256, 66), 36: ("MXFP4", 32, 17),
}


class R:
    def __init__(self, b):
        self.b = b
        self.i = 0

    def u32(self):
        v = struct.unpack_from("<I", self.b, self.i)[0]
        self.i += 4
        return v

    def u64(self):
        v = struct.unpack_from("<Q", self.b, self.i)[0]
        self.i += 8
        return v

    def s(self):
        n = self.u64()
        v = self.b[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v

    def val(self, t):
        if t == 0: return self.take("<B")
        if t == 1: return self.take("<b")
        if t == 2: return self.take("<H")
        if t == 3: return self.take("<h")
        if t == 4: return self.take("<I")
        if t == 5: return self.take("<i")
        if t == 6: return self.take("<f")
        if t == 7:
            v = bool(self.b[self.i]); self.i += 1; return v
        if t == 8: return self.s()
        if t == 9:
            et = self.u32(); n = self.u64()
            if n > 2_000_000: raise ValueError("array too large: %d" % n)
            return [self.val(et) for _ in range(n)]
        if t == 10: return self.u64()
        if t == 11: return self.take("<q")
        if t == 12: return self.take("<d")
        raise ValueError("bad vtype %d" % t)

    def take(self, fmt):
        v = struct.unpack_from(fmt, self.b, self.i)[0]
        self.i += struct.calcsize(fmt)
        return v


def probe(repo, fname, nbytes=4 << 20):
    url = f"https://huggingface.co/{repo}/resolve/main/{fname}?download=true"
    p = subprocess.run(
        ["curl", "-sSL", "--fail", "-H", f"Range: bytes=0-{nbytes - 1}", url],
        capture_output=True, timeout=180)
    if p.returncode != 0:
        return {"error": p.stderr.decode()[:200], "repo": repo, "file": fname}
    b = p.stdout
    if b[:4] != b"GGUF":
        return {"error": "not gguf", "repo": repo, "file": fname}
    if len(b) >= nbytes and nbytes < (32 << 20):
        # Truncated inside the tensor table. Re-probe wider.
        return probe(repo, fname, min(32 << 20, nbytes * 4))
    r = R(b)
    r.i = 4
    version = r.u32(); n_tensors = r.u64(); n_kv = r.u64()
    kv = {}
    for _ in range(n_kv):
        k = r.s(); t = r.u32(); kv[k] = r.val(t)
    tensors = []
    for _ in range(n_tensors):
        name = r.s(); nd = r.u32()
        dims = [r.u64() for _ in range(nd)]
        tt = r.u32(); off = r.u64()
        tensors.append((name, dims, tt, off))
    header_end = r.i
    total = len(b)

    per_type_elems = defaultdict(int)
    per_type_bytes = defaultdict(int)
    weight_bytes = 0
    elems = 0
    first_data = None
    for name, dims, tt, off in tensors:
        e = 1
        for d in dims:
            e *= d
        label, be, bb = BLOCKS.get(tt, ("T%d" % tt, 1, 1))
        blocks = e // be + (1 if e % be else 0)
        by = blocks * bb
        # A GGUF's non-weight tensors (token_embd is a weight, but these are not)
        if name.endswith(".weight") or name == "token_embd.weight":
            weight_bytes += by; elems += e
        per_type_elems[label] += e
        per_type_bytes[label] += by
        if first_data is None or off < first_data:
            first_data = off
    comp = sorted(
        ({"type": lbl, "elems": per_type_elems[lbl], "bytes": per_type_bytes[lbl],
          "share_pct": round(100.0 * per_type_elems[lbl] / max(1, elems), 1)}
         for lbl in per_type_elems),
        key=lambda d: -d["bytes"])
    return {
        "repo": repo, "file": fname, "gguf_version": version, "probe_bytes": total,
        "n_tensors": n_tensors, "header_bytes": header_end, "alignment_pad": first_data,
        "params": elems, "weight_bytes": weight_bytes,
        "bpw": round(weight_bytes * 8.0 / elems, 4) if elems else None,
        "file_type": kv.get("general.file_type"),
        "declared_params": kv.get("general.parameter_count"),
        "arch": kv.get("general.architecture"),
        "name": kv.get("general.name"),
        "block_count": kv.get("%s.block_count" % kv.get("general.architecture")),
        "n_head": kv.get("%s.attention.head_count" % kv.get("general.architecture")),
        "n_head_kv": kv.get("%s.attention.head_count_kv" % kv.get("general.architecture")),
        "key_length": kv.get("%s.attention.key_length" % kv.get("general.architecture")),
        "n_embd": kv.get("%s.embedding_length" % kv.get("general.architecture")),
        "n_expert": kv.get("%s.expert_count" % kv.get("general.architecture")),
        "head_dim": (kv.get("%s.attention.key_length" % kv.get("general.architecture"))
                     or (kv["%s.embedding_length" % kv.get("general.architecture")] //
                         kv["%s.attention.head_count" % kv.get("general.architecture")]
                         if kv.get("%s.embedding_length" % kv.get("general.architecture"))
                         and kv.get("%s.attention.head_count" % kv.get("general.architecture")) else None)),
        "ctx": kv.get("%s.context_length" % kv.get("general.architecture")),
        "composition": comp[:6],
    }


if __name__ == "__main__":
    targets = json.load(open(sys.argv[1]))
    out = []
    for t in targets:
        try:
            r = probe(t["repo"], t["file"])
        except Exception as e:
            r = {"error": repr(e)[:200], "repo": t["repo"], "file": t["file"]}
        out.append(r)
        if "error" in r:
            print("ERR  %-46s %s" % (t["file"], r["error"]), flush=True)
        else:
            print("%-46s bpw=%-8s params=%-13s L=%-4s kvH=%-4s hd=%-5s %s" % (
                t["file"], r["bpw"], r["params"], r["block_count"], r["n_head_kv"],
                r["key_length"] or r["n_embd"], ",".join(
                    "%s:%.0f%%" % (c["type"], c["share_pct"]) for c in r["composition"][:4])), flush=True)
    json.dump(out, open(sys.argv[2], "w"), indent=1)
