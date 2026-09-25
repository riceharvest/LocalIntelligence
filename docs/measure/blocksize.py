#!/usr/bin/env python3
"""
Derive the block sizes the fit model depends on, from the llama.cpp source the
app links against, by asking the compiler for sizeof().

This is the whole point: the bits-per-weight of a quant is not a convention, it
is `sizeof(block_qX) * 8 / QK`. Compiling the real header is how you get the
real number instead of the number someone remembered.

    git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
    cp docs/measure/blocksize.c /tmp/
    cd /tmp/llama.cpp/ggml/src && gcc -O0 -o /tmp/blocksize /tmp/blocksize.c -I.
    /tmp/blocksize

Tag b4661 is the tag this project builds against. Newer llama.cpp removed
use_mmap / logits_all / flash_attn and will not compile here.
"""
import os
import subprocess
import sys

C_SOURCE = r"""
#define GGML_COMMON_DECL_C
#include <stdio.h>
#include <stddef.h>
#include "ggml-common.h"
#define P(t,qk) printf("%-12s elems=%-4zu bytes=%-4zu bpw=%.4f\n", #t, (size_t)(qk), sizeof(t), sizeof(t)*8.0/(double)(qk))
int main(void){
  P(block_q4_0,32); P(block_q4_1,32); P(block_q5_0,32); P(block_q5_1,32); P(block_q8_0,32); P(block_iq4_nl,32);
  P(block_q2_K,QK_K); P(block_q3_K,QK_K); P(block_q4_K,QK_K); P(block_q5_K,QK_K); P(block_q6_K,QK_K); P(block_q8_K,QK_K);
  P(block_iq2_xxs,QK_K); P(block_iq2_xs,QK_K); P(block_iq2_s,QK_K); P(block_iq3_s,QK_K); P(block_iq1_s,QK_K); P(block_iq1_m,QK_K); P(block_iq4_xs,QK_K);
  return 0;
}
"""

if __name__ == "__main__":
    src_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/llama.cpp/ggml/src"
    c_path = "/tmp/blocksize.c"
    with open(c_path, "w") as fh:
        fh.write(C_SOURCE)
    subprocess.run(["gcc", "-O0", "-o", "/tmp/blocksize", c_path, "-I" + src_dir], check=True)
    subprocess.run(["/tmp/blocksize"], check=True)
    print("\nF16/BF16 are 2 bytes/element (16.0 bpw) and F32 is 4 (32.0 bpw);")
    print("those are declared in the enum, not in a struct, so they are stated here.")
