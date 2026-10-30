// llama.cpp JNI implementation. See llama_jni.h for the surface contract.
//
// Written against llama.h at the tag pinned in CMakeLists.txt. Every call into
// llama.cpp here uses a signature read out of that header.

#include "llama_jni.h"

#include <android/log.h>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <string>

// WHY THIS EXISTS: llama.cpp explains a failed decode on stderr. Android
// discards native stderr, so `llama_decode` returning -1 arrived in Kotlin as a
// bare "llama_decode failed during prefill with -1" and the actual cause was
// gone. ggml's log callback is the only place that text exists, so route it to
// logcat. Without this, every native failure looked identical and none of it was
// diagnosable from the device.
static void log_to_logcat(enum ggml_log_level level, const char * text, void * /*ud*/) {
    // Per-tensor debug lines (a repack line for every weight) and the model
    // loader's progress dots fill logcat's 256 KB ring and push out the lines
    // that matter.
    if (text == nullptr || level == GGML_LOG_LEVEL_DEBUG) return;
    int pri = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) pri = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) pri = ANDROID_LOG_WARN;
    // logcat truncates long lines and ggml puts the whole reason on one, so
    // split on newlines rather than losing the tail.
    const std::string line(text);
    size_t pos = 0;
    while (pos <= line.size()) {
        const size_t nl = line.find('\n', pos);
        const std::string chunk = (nl == std::string::npos)
            ? line.substr(pos)
            : line.substr(pos, nl - pos);
        if (!chunk.empty() && chunk != ".") __android_log_print(pri, "LocalLlama", "%s", chunk.c_str());
        if (nl == std::string::npos) break;
        pos = nl + 1;
    }
}

#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>
#include <cstdio>
#include <dlfcn.h>
#include <strings.h>
#include <sys/prctl.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <mutex>
#include <cmath>
#include <chrono>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

namespace {

constexpr int32_t kDefaultContext = 4096;
constexpr int32_t kDefaultBatch = 512;

// Context for an embedding model. A document passage is about 800 characters,
// which is a few hundred tokens; 1024 covers that with room for other scripts,
// and the trained length caps it below that anyway.
constexpr int32_t kEmbeddingContext = 1024;

// Headroom left at the end of the context window for generation, so a prompt
// that exactly fills the window still has somewhere to put an answer.
constexpr int32_t kGenerationHeadroom = 8;

llama_model * as_model(void * p) { return reinterpret_cast<llama_model *>(p); }
llama_context * as_context(void * p) { return reinterpret_cast<llama_context *>(p); }
const llama_vocab * as_vocab(void * p) { return reinterpret_cast<const llama_vocab *>(p); }

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// A Java string from UTF-8, through UTF-16.
//
// WHY NOT NewStringUTF: it expects *modified* UTF-8, so a 4-byte sequence (any
// emoji a model writes) is invalid input, and with CheckJNI on (every debuggable
// build) that aborts the process. Decoding here also turns malformed bytes into
// U+FFFD instead of passing them to the VM.
jstring to_jstring(JNIEnv * env, const std::string & s) {
    if (s.empty()) {
        return nullptr;
    }
    std::vector<jchar> u;
    u.reserve(s.size());
    const auto * b = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    for (size_t i = 0; i < n;) {
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        const unsigned char c = b[i];
        if (c < 0x80) {
            cp = c;
        } else if ((c >> 5) == 0x6 && i + 1 < n && (b[i + 1] & 0xC0) == 0x80) {
            cp = ((c & 0x1F) << 6) | (b[i + 1] & 0x3F);
            len = 2;
        } else if ((c >> 4) == 0xE && i + 2 < n && (b[i + 1] & 0xC0) == 0x80 && (b[i + 2] & 0xC0) == 0x80) {
            cp = ((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F);
            len = 3;
        } else if ((c >> 3) == 0x1E && i + 3 < n && (b[i + 1] & 0xC0) == 0x80 && (b[i + 2] & 0xC0) == 0x80 &&
                   (b[i + 3] & 0xC0) == 0x80) {
            cp = ((c & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12) | ((b[i + 2] & 0x3F) << 6) | (b[i + 3] & 0x3F);
            len = 4;
        }
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            u.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            u.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back(static_cast<jchar>(cp > 0xFFFF ? 0xFFFD : cp));
        }
        i += len;
    }
    return env->NewString(u.data(), static_cast<jsize>(u.size()));
}

void free_model_locked(LlamaHandle * h) {
    // The projector is bound to the model, so it goes first.
    if (h->mtmd != nullptr) {
        mtmd_free(reinterpret_cast<mtmd_context *>(h->mtmd));
        h->mtmd = nullptr;
    }
    h->pending_media.clear();
    h->snap_tokens.clear();
    h->snap_state.clear();
    h->gen_state.clear();
    h->gen_at = 0;
    h->anchor_tokens.clear();
    h->anchor_state.clear();
    h->prefix_key.clear();
    if (h->context != nullptr) {
        llama_free(as_context(h->context));
        h->context = nullptr;
    }
    if (h->model != nullptr) {
        llama_model_free(as_model(h->model));
        h->model = nullptr;
    }
    h->vocab = nullptr;
    h->loaded = false;
    h->n_ctx = 0;
    h->n_vocab = 0;
    h->eog_token = -1;
    h->n_embd = 0;
}

// Phones are asymmetric (big.LITTLE). Leaving the prime core free matters:
// oversubscribing the little cores costs more than the thread gains, and it
// makes the UI janky while a background decode runs.
// The cores worth running inference on: every core faster than the slowest
// cluster. Phones are big.LITTLE (Tensor G5: 1 X4 + 5 A725 + 2 A520); the
// little cores slow a parallel matmul down to their pace.
int32_t fast_cores() {
    const unsigned hw = std::thread::hardware_concurrency();
    std::vector<long> freqs;
    for (unsigned i = 0; i < hw; ++i) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (f == nullptr) continue;
        long khz = 0;
        if (fscanf(f, "%ld", &khz) == 1 && khz > 0) freqs.push_back(khz);
        fclose(f);
    }
    if (hw == 0 || freqs.size() != hw) {
        return hw == 0 ? 4 : static_cast<int32_t>(hw > 4 ? hw - 2 : hw);
    }
    const long slowest = *std::min_element(freqs.begin(), freqs.end());
    const int32_t fast = static_cast<int32_t>(
        std::count_if(freqs.begin(), freqs.end(), [&](long k) { return k > slowest; }));
    return fast > 0 ? fast : static_cast<int32_t>(hw);
}

// Prompt processing is compute-bound and scales with the fast cores. Token
// generation is memory-bound and stops scaling at about 4 threads on a phone:
// MEASURED Pixel 10 Pro, LFM3-1.7B Q4_K_M, llama-bench: pp256 152/211/148 t/s
// at 4/6/8 threads, tg64 24.0/23.7/21.0 t/s.
struct ThreadPlan {
    int32_t decode;
    int32_t prefill;
};

ThreadPlan pick_thread_plan(int32_t requested) {
    if (requested > 0) return {requested, requested};
    const int32_t fast = fast_cores();
    return {std::min(fast, 4), fast};
}

int32_t pick_threads(int32_t requested) {
    return pick_thread_plan(requested).prefill;
}

// Splits a token's bytes at the last complete UTF-8 character boundary.
//
// llama.cpp's `llama_token_to_piece` can return a fragment that ends in the
// middle of a multi-byte character, because BPE tokens are byte-oriented and a
// character's bytes can straddle two tokens. Handing Java a fragment would put
// a replacement character in the stream. So the trailing incomplete sequence is
// held back and prepended to the next piece.
//
// @param out      receives the complete characters (may be empty).
// @param partial is updated to hold any trailing incomplete bytes.
// @return true if there is a partial tail to carry into the next token.
bool split_utf8(const std::string & piece, std::string & out, std::string & partial) {
    std::string buf = partial + piece;
    partial.clear();

    // Walk back over any trailing bytes that form an incomplete sequence: a
    // lead byte says how many bytes the character needs, and we keep it if they
    // are not all present yet.
    size_t keep = 0;
    size_t i = buf.size();
    while (i > 0) {
        const unsigned char c = static_cast<unsigned char>(buf[i - 1]);
        if ((c & 0x80) == 0x00) {
            break;  // ASCII: the string ends on a boundary
        }
        if ((c & 0xC0) == 0xC0) {
            // A lead byte. How many bytes does this character need?
            int need = 0;
            if ((c & 0xE0) == 0xC0) {
                need = 2;
            } else if ((c & 0xF0) == 0xE0) {
                need = 3;
            } else if ((c & 0xF8) == 0xF0) {
                need = 4;
            }
            const size_t have = buf.size() - (i - 1);
            if (need > 0 && have < static_cast<size_t>(need)) {
                keep = have;  // hold back the bytes we do have
            }
            break;
        }
        i--;  // a continuation byte; keep walking back toward the lead byte
        if (buf.size() - (i - 1) > 4) {
            break;  // malformed: stop rather than scan the whole buffer
        }
    }
    if (keep > 0 && keep <= buf.size()) {
        partial = buf.substr(buf.size() - keep);
        out = buf.substr(0, buf.size() - keep);
    } else {
        out = buf;
    }
    return !partial.empty();
}

// Resolved once per generation rather than once per token: GetObjectClass and
// GetMethodID are real JNI calls, and doing them inside the decode loop adds
// measurable overhead to every token of a long answer.
struct TokenSinkRef {
    jobject obj = nullptr;  // global ref, so a local-ref GC cannot take it
    jmethodID method = nullptr;

    bool resolve(JNIEnv * env, jobject sink) {
        if (sink == nullptr) {
            return true;
        }
        jclass local = env->GetObjectClass(sink);
        if (local == nullptr) {
            return false;
        }
        method = env->GetMethodID(local, "onToken", "(Ljava/lang/String;)V");
        // A global ref: the caller's local frame is gone by the time we decode.
        obj = env->NewGlobalRef(sink);
        env->DeleteLocalRef(local);
        return method != nullptr && obj != nullptr;
    }

    void release(JNIEnv * env) {
        if (obj != nullptr) {
            env->DeleteGlobalRef(obj);
            obj = nullptr;
        }
        method = nullptr;
    }

    // Returns false if the sink threw or has gone, so the caller stops rather
    // than streaming into nothing.
    bool emit(JNIEnv * env, const std::string & piece) const {
        if (obj == nullptr || method == nullptr || piece.empty()) {
            return true;
        }
        jstring js = to_jstring(env, piece);
        if (js == nullptr) {
            return true;
        }
        env->CallVoidMethod(obj, method, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return true;
    }
};

// Tokenises, preferring the two-pass form: ask for the count, then fill.
//
// `parse_special` false (the default, and the flat transcript) keeps text such
// as "<|im_start|>" as ordinary characters, so user text cannot become a control
// token. True turns it into the special token; only a rendered chat template
// may use that, and its contents must have been neutralised first.
bool tokenize(const llama_vocab * vocab, const std::string & text,
              std::vector<llama_token> & out, std::string & err,
              bool parse_special = false) {
    const int32_t len = static_cast<int32_t>(text.size());
    int32_t needed = llama_tokenize(vocab, text.data(), len, nullptr, 0,
                                    /*add_special=*/true, parse_special);
    if (needed < 0) {
        needed = -needed;  // negative is "buffer too small, this is the size"
    }
    if (needed == 0) {
        out.clear();
        return true;
    }
    out.resize(static_cast<size_t>(needed));
    const int32_t got = llama_tokenize(vocab, text.data(), len, out.data(), needed,
                                       /*add_special=*/true, parse_special);
    if (got < 0) {
        err = "llama_tokenize failed on a " + std::to_string(needed) + " token prompt";
        return false;
    }
    out.resize(static_cast<size_t>(got));
    return true;
}

// Copies a Java string into `out`. A null reference becomes "". False only if
// the JVM could not hand over the characters.
// Real UTF-8 from a Java string.
//
// WHY NOT GetStringUTFChars: it returns *modified* UTF-8, which encodes every
// character outside the BMP (emoji, many CJK extensions) as two 3-byte
// surrogates instead of one 4-byte sequence. The tokenizer then sees bytes no
// model was trained on. Reading UTF-16 and encoding it here is the fix.
bool jstring_utf8(JNIEnv * env, jstring js, std::string & out) {
    out.clear();
    if (js == nullptr) return true;
    const jsize n = env->GetStringLength(js);
    std::vector<jchar> u(static_cast<size_t>(n));
    if (n > 0) env->GetStringRegion(js, 0, n, u.data());
    if (env->ExceptionCheck()) return false;
    out.reserve(static_cast<size_t>(n) * 3);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = u[static_cast<size_t>(i)];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
            const uint32_t lo = u[static_cast<size_t>(i + 1)];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;  // a lone surrogate
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return true;
}

bool copy_jstring(JNIEnv * env, jstring js, std::string & out) {
    return jstring_utf8(env, js, out);
}

// The text of every control token in the vocabulary, longest first. With
// parse_special on, these are the strings the tokenizer turns into one special
// token each, so they are exactly what a message must not contain verbatim.
std::vector<std::string> control_token_texts(const llama_vocab * vocab) {
    std::vector<std::string> out;
    const int32_t n = llama_vocab_n_tokens(vocab);
    for (llama_token id = 0; id < n; ++id) {
        if (!llama_vocab_is_control(vocab, id)) {
            continue;
        }
        const char * text = llama_vocab_get_text(vocab, id);
        // A one-character control token would match every occurrence of that
        // character, and breaking it would corrupt ordinary text.
        if (text != nullptr && std::strlen(text) >= 2) {
            out.emplace_back(text);
        }
    }
    std::sort(out.begin(), out.end(),
              [](const std::string & a, const std::string & b) { return a.size() > b.size(); });
    return out;
}

// Breaks every occurrence of a control token by inserting a space after its
// first character. The model still reads the same characters, but the tokenizer
// can no longer build the special token from them.
std::string neutralise_control_text(std::string s, const std::vector<std::string> & specials) {
    for (const std::string & sp : specials) {
        size_t pos = 0;
        while ((pos = s.find(sp, pos)) != std::string::npos) {
            s.insert(pos + 1, " ");
            pos += 1;
        }
    }
    return s;
}

// Builds the sampling chain. Returns null only if llama.cpp cannot allocate one.
// The action grammar, or null. Kept out of the chain on purpose: applying a
// grammar to the whole vocabulary on every token is the most expensive step in
// sampling, so the decode loop samples first and only constrains when the
// sampled token breaks the grammar (llama.cpp's own common sampler does the
// same). A grammar that fails to compile yields null: unconstrained output is
// better than no output, and the parser then rejects a malformed action.
llama_sampler * build_grammar(const llama_vocab * vocab, const std::string & grammar_str) {
    if (grammar_str.empty()) return nullptr;
    return llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
}

llama_sampler * build_chain(const llama_vocab * vocab, const std::string & grammar_str,
                            float temperature, int32_t top_k, float top_p, float min_p,
                            float repeat_penalty, int32_t penalty_last_n,
                            float freq_penalty, float presence_penalty, uint32_t seed) {
    // The chain has to exist before anything can be added to it: `sparams` is
    // consumed by llama_sampler_chain_init, and llama_sampler_chain_add takes
    // the chain, not the params.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (chain == nullptr) {
        return nullptr;
    }

    (void) grammar_str;  // the grammar is a separate sampler: see build_grammar
    // Top-k first: everything after it then works on k candidates instead of
    // the whole vocabulary (128k tokens for LFM3), every token. It cannot
    // change a greedy pick, and a penalised token outside the top k could not
    // have been sampled anyway.
    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    // Penalties only go in when one is active. A window of 0 turns all of them
    // off, and llama.cpp treats a repeat penalty of exactly 1 as a no-op.
    const float repeat = repeat_penalty > 0.0f ? repeat_penalty : 1.0f;
    const bool penalties_active =
        penalty_last_n > 0 && (repeat != 1.0f || freq_penalty != 0.0f || presence_penalty != 0.0f);
    if (penalties_active) {
        llama_sampler_chain_add(
            chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), penalty_last_n,
                                                repeat, freq_penalty, presence_penalty));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, /*min_keep=*/1));
    }
    if (min_p > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, /*min_keep=*/1));
    }
    if (temperature <= 0.0f) {
        // Greedy, for the determinism the eval suite needs.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    }
    return chain;
}

// The directory this library was loaded from. The APK's other native
// libraries, the ggml backends among them, are extracted next to it.
std::string native_lib_dir() {
    Dl_info info;
    if (dladdr(reinterpret_cast<void *>(&native_lib_dir), &info) == 0 || info.dli_fname == nullptr) {
        return {};
    }
    std::string dir = info.dli_fname;
    const size_t slash = dir.find_last_of('/');
    return slash == std::string::npos ? std::string() : dir.substr(0, slash);
}

void log_devices() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        __android_log_print(ANDROID_LOG_INFO, "LocalLlama", "backend device %zu: %s (%s)", i,
                            ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }
}

// Registers the one CPU variant that suits this phone. Each libggml-cpu-*.so
// reports a score for the running CPU (0 = cannot run here) and the highest
// wins, which is ggml's own rule; ggml_backend_load_best() is not exported,
// and ggml_backend_load_all_from_path() would also start every GPU driver.
void load_cpu_backend(const std::string & dir) {
    // Testing aid: `adb shell setprop debug.localintelligence.cpu android_armv8.6_1`
    // loads that one CPU variant instead of the best-scored one, so kernel
    // families can be compared on a real phone without a rebuild.
    char forced[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.localintelligence.cpu", forced);
    if (forced[0] != '\0') {
        const std::string lib = dir + "/libggml-cpu-" + forced + ".so";
        if (ggml_backend_load(lib.c_str()) != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, "LocalLlama", "CPU variant forced: %s", forced);
            return;
        }
        __android_log_print(ANDROID_LOG_WARN, "LocalLlama", "forced CPU variant %s failed to load", forced);
    }
    std::string best;
    int best_score = 0;
    bool neon_i8mm_ok = false;
    if (DIR * d = opendir(dir.c_str())) {
        while (dirent * e = readdir(d)) {
            const std::string name = e->d_name;
            if (name.rfind("libggml-cpu-", 0) != 0 || name.size() < 3 || name.compare(name.size() - 3, 3, ".so") != 0) {
                continue;
            }
            const std::string path = dir + "/" + name;
            void * lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (lib == nullptr) continue;
            using score_fn = int (*)();
            auto score = reinterpret_cast<score_fn>(dlsym(lib, "ggml_backend_score"));
            const int s = score != nullptr ? score() : 0;
            dlclose(lib);
            if (s > 0 && name == "libggml-cpu-android_armv8.6_1.so") neon_i8mm_ok = true;
            if (s > best_score) {
                best_score = s;
                best = path;
            }
        }
        closedir(d);
    }
    // The SVE2 build (armv9.0, no SME) outscores the NEON i8mm build, but phone
    // cores run SVE at 128 bits, the same width as NEON, and on a Pixel 10 Pro it
    // measured 15-25% slower prefill with equal decode (Q4_0 and Q4_K_M alike).
    // Wider SVE, or SME in the armv9.2 builds, keeps the higher score.
    const int sve_vl = prctl(PR_SVE_GET_VL);
    const int sve_bytes = sve_vl < 0 ? 0 : sve_vl & PR_SVE_VL_LEN_MASK;
    if (neon_i8mm_ok && best.find("android_armv9.0_") != std::string::npos && sve_bytes > 0 && sve_bytes <= 16) {
        best = dir + "/libggml-cpu-android_armv8.6_1.so";
    }
    __android_log_print(ANDROID_LOG_INFO, "LocalLlama", "CPU variant: %s (SVE %d bytes)",
                        best.empty() ? "none" : best.substr(best.rfind('/') + 1).c_str(), sve_bytes);
    if (best.empty() || ggml_backend_load(best.c_str()) == nullptr) {
        // No variant scored (or the directory was unreadable): let ggml look on its own.
        ggml_backend_load_all_from_path(dir.c_str());
    }
}

// Vulkan and OpenCL are registered the first time a GPU is asked for, not at
// start-up: creating a Vulkan instance or an OpenCL platform costs driver
// memory and launch time on every start, and a CPU-only user never needs it.
// A backend whose driver is missing (no libOpenCL.so on a Mali phone) simply
// fails to load.
void ensure_gpu_backends() {
    static std::once_flag gpu_loaded;
    std::call_once(gpu_loaded, [] {
        const std::string dir = native_lib_dir();
        if (dir.empty()) return;
        for (const char * gpu : {"vulkan", "opencl"}) {
            const std::string path = dir + "/libggml-" + gpu + ".so";
            if (access(path.c_str(), F_OK) == 0 && ggml_backend_load(path.c_str()) == nullptr) {
                __android_log_print(ANDROID_LOG_WARN, "LocalLlama", "GPU backend %s did not load", gpu);
            }
        }
        log_devices();
    });
}

bool is_gpu(ggml_backend_dev_t dev) {
    const enum ggml_backend_dev_type t = ggml_backend_dev_type(dev);
    return t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

// The first GPU device of the backend named [backend] ("Vulkan", "OpenCL"), or null.
ggml_backend_dev_t find_gpu(const std::string & backend) {
    ensure_gpu_backends();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (is_gpu(dev) && strcasecmp(ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev)), backend.c_str()) == 0) {
            return dev;
        }
    }
    return nullptr;
}

// An empty device list: llama.cpp then keeps every layer, the KV cache and
// every op on the CPU, even when a GPU backend is registered.
ggml_backend_dev_t kNoDevices[] = {nullptr};


// ---- system prompt anchor ----------------------------------------------------

// Bumped when llama.cpp's state format may have changed (a llama.cpp update),
// so an old file is never fed to a new build.
// The sequence speech runs on when the context has two (see nativeLoadModel).
constexpr llama_seq_id kSpeechSeq = 1;

constexpr char kAnchorFormat[] = "b11988-1";
// Below this the system prompt is cheap to prefill and not worth a file.
constexpr int32_t kMinAnchorTokens = 64;
// Anchor files kept on disk; the oldest go first.
constexpr size_t kKeptAnchorFiles = 3;

uint64_t fnv1a(const void * data, size_t n, uint64_t hash = 1469598103934665603ULL) {
    const auto * p = static_cast<const uint8_t *>(data);
    for (size_t i = 0; i < n; ++i) {
        hash ^= p[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

// The file for the first `n` tokens of `tokens` under this model load, or ""
// when disk anchors are off.
std::string anchor_path(const LlamaHandle * h, const std::vector<llama_token> & tokens, int32_t n) {
    if (h->prefix_dir.empty() || h->prefix_key.empty()) return "";
    uint64_t x = fnv1a(h->prefix_key.data(), h->prefix_key.size());
    x = fnv1a(kAnchorFormat, sizeof(kAnchorFormat), x);
    x = fnv1a(tokens.data(), static_cast<size_t>(n) * sizeof(llama_token), x);
    char name[48];
    snprintf(name, sizeof(name), "prefix-%016llx.state", static_cast<unsigned long long>(x));
    return h->prefix_dir + "/" + name;
}

bool read_whole_file(const std::string & path, std::vector<uint8_t> & out) {
    FILE * f = fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    bool ok = fseek(f, 0, SEEK_END) == 0;
    const long size = ok ? ftell(f) : -1;
    ok = ok && size > 0 && fseek(f, 0, SEEK_SET) == 0;
    if (ok) {
        out.resize(static_cast<size_t>(size));
        ok = fread(out.data(), 1, out.size(), f) == out.size();
    }
    fclose(f);
    if (!ok) out.clear();
    return ok;
}

// Written beside the target and renamed over it, so a crash mid-write leaves
// no half file under the real name.
bool write_file_atomic(const std::string & path, const std::vector<uint8_t> & data) {
    const std::string tmp = path + ".tmp";
    FILE * f = fopen(tmp.c_str(), "wb");
    if (f == nullptr) return false;
    const bool ok = fwrite(data.data(), 1, data.size(), f) == data.size();
    if (fclose(f) != 0 || !ok) {
        remove(tmp.c_str());
        return false;
    }
    return rename(tmp.c_str(), path.c_str()) == 0;
}

// Keeps the newest kKeptAnchorFiles anchor files in `dir`.
void prune_anchor_files(const std::string & dir) {
    DIR * d = opendir(dir.c_str());
    if (d == nullptr) return;
    std::vector<std::pair<time_t, std::string>> files;
    while (dirent * e = readdir(d)) {
        const std::string name = e->d_name;
        if (name.rfind("prefix-", 0) != 0) continue;
        const std::string full = dir + "/" + name;
        struct stat st{};
        if (stat(full.c_str(), &st) == 0) files.emplace_back(st.st_mtime, full);
    }
    closedir(d);
    if (files.size() <= kKeptAnchorFiles) return;
    std::sort(files.begin(), files.end());
    for (size_t i = 0; i + kKeptAnchorFiles < files.size(); ++i) remove(files[i].second.c_str());
}

// Restores the system prompt's state for `tokens[0, at)` from memory or disk
// into the cleared sequence. Returns the reused token count, 0 on a miss.
int32_t restore_anchor(LlamaHandle * h, llama_context * ctx, const std::vector<llama_token> & tokens,
                       int32_t at, int32_t seq) {
    const bool in_memory = !h->anchor_state.empty() &&
        static_cast<int32_t>(h->anchor_tokens.size()) == at &&
        std::equal(h->anchor_tokens.begin(), h->anchor_tokens.end(), tokens.begin());
    std::string from = "memory";
    if (!in_memory) {
        const std::string path = anchor_path(h, tokens, at);
        std::vector<uint8_t> data;
        if (path.empty() || !read_whole_file(path, data)) return 0;
        h->anchor_state.swap(data);
        h->anchor_tokens.assign(tokens.begin(), tokens.begin() + at);
        from = "disk";
    }
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_clear(mem, true);
    if (llama_state_seq_set_data(ctx, h->anchor_state.data(), h->anchor_state.size(), seq) == 0) {
        llama_memory_clear(mem, true);
        __android_log_print(ANDROID_LOG_WARN, "LocalLlama", "prefix anchor: %s copy did not restore, dropped",
                            from.c_str());
        if (from == "disk") remove(anchor_path(h, tokens, at).c_str());
        h->anchor_state.clear();
        h->anchor_tokens.clear();
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, "LocalLlama", "prefix anchor: restored %d system-prompt tokens from %s",
                        at, from.c_str());
    return at;
}

// Copies the state at `at` (the end of the system prompt) and saves it.
void save_anchor(LlamaHandle * h, llama_context * ctx, const std::vector<llama_token> & tokens,
                 int32_t at, int32_t seq) {
    const size_t size = llama_state_seq_get_size(ctx, seq);
    if (size == 0) return;
    h->anchor_state.resize(size);
    if (llama_state_seq_get_data(ctx, h->anchor_state.data(), size, seq) != size) {
        h->anchor_state.clear();
        h->anchor_tokens.clear();
        return;
    }
    h->anchor_tokens.assign(tokens.begin(), tokens.begin() + at);
    const std::string path = anchor_path(h, tokens, at);
    const bool saved = !path.empty() && write_file_atomic(path, h->anchor_state);
    if (saved) prune_anchor_files(h->prefix_dir);
    __android_log_print(ANDROID_LOG_INFO, "LocalLlama", "prefix anchor: kept %d system-prompt tokens (%.1f MiB)%s",
                        at, static_cast<double>(size) / (1024.0 * 1024.0), saved ? ", saved to disk" : "");
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCreate(JNIEnv *, jclass) {
    // The CPU kernels (one library per ARM/x86 feature level) are dynamic
    // libraries next to this one; the best one for this phone is registered
    // once per process. GPU backends wait until a GPU is asked for
    // (ensure_gpu_backends).
    static std::once_flag backends_loaded;
    std::call_once(backends_loaded, [] {
        ggml_log_set(log_to_logcat, nullptr);
        // llama.cpp's own messages (model load, layers offloaded, buffer sizes)
        // otherwise go to stderr, which an app never sees.
        llama_log_set(log_to_logcat, nullptr);
        const std::string dir = native_lib_dir();
        if (dir.empty()) {
            ggml_backend_load_all();
        } else {
            load_cpu_backend(dir);
        }
        log_devices();
    });
    auto * h = new (std::nothrow) LlamaHandle();
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return;
    }
    // A generation may still be running on another thread. Flagging cancel
    // makes its loop unwind; taking `state` then blocks until it has actually
    // finished, so the frees below cannot race a decode.
    h->cancelled.store(true);
    {
        std::lock_guard<std::mutex> lock(h->state);
        free_model_locked(h);
        if (h->backend_initialised) {
            llama_backend_free();
            h->backend_initialised = false;
        }
    }
    delete h;
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSystemInfo(JNIEnv * env, jclass) {
    const char * info = llama_print_system_info();
    return to_jstring(env, info != nullptr ? std::string(info) : std::string("unknown"));
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jclass, jlong handle, jstring path, jint n_ctx, jint n_threads,
        jboolean use_mmap, jstring gpu_backend, jint gpu_layers, jint outputs_max) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return to_jstring(env, "null handle");
    }
    if (path == nullptr) {
        return to_jstring(env, "null model path");
    }
    std::string model_path;
    if (!jstring_utf8(env, path, model_path)) {
        return to_jstring(env, "could not read the model path");
    }

    // Null or empty: the CPU. Otherwise a GPU backend's name, "Vulkan" or "OpenCL".
    std::string gpu_name;
    if (gpu_backend != nullptr && !jstring_utf8(env, gpu_backend, gpu_name)) {
        return to_jstring(env, "could not read the GPU backend name");
    }

    std::lock_guard<std::mutex> lock(h->state);

    // One model resident at a time: loading a second unloads the first.
    free_model_locked(h);

    // llama_backend_init is refcounted internally, so pairing init/free per
    // loaded model is correct and survives a reload.
    ggml_log_set(log_to_logcat, nullptr);
    llama_backend_init();
    h->backend_initialised = true;

    llama_model_params mparams = llama_model_default_params();
    ggml_backend_dev_t gpu_devices[] = {nullptr, nullptr};
    if (gpu_name.empty()) {
        mparams.devices = kNoDevices;
        mparams.n_gpu_layers = 0;
    } else {
        gpu_devices[0] = find_gpu(gpu_name);
        if (gpu_devices[0] == nullptr) {
            return to_jstring(env, "no " + gpu_name + " GPU on this device");
        }
        // One GPU, the layers that fit it; a negative count means every layer.
        mparams.devices = gpu_devices;
        mparams.split_mode = LLAMA_SPLIT_MODE_NONE;
        mparams.main_gpu = 0;
        mparams.n_gpu_layers = gpu_layers;
    }
    // mlock would pin ~2 GB for the model's whole life, which on Android
    // makes the process an immediate low-memory-killer target. Leave it off:
    // MMAP, never MMAP_MLOCK.
    mparams.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    mparams.vocab_only = false;

    // Decode and prefill thread counts are set separately below.

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        return to_jstring(env, "could not load a model from " + model_path);
    }

    const int32_t trained_ctx = llama_model_n_ctx_train(model);
    int32_t ctx_len = n_ctx > 0 ? n_ctx : (trained_ctx > 0 ? trained_ctx : kDefaultContext);
    // More context than the model was trained on is legal but wasteful: the KV
    // cache is allocated up front.
    if (trained_ctx > 0 && ctx_len > trained_ctx) {
        ctx_len = trained_ctx;
    }
    if (ctx_len < 128) {
        llama_model_free(model);
        return to_jstring(env, "a context length of " + std::to_string(ctx_len) + " is too small");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(ctx_len);
    cparams.n_batch = static_cast<uint32_t>(std::min(ctx_len, kDefaultBatch));
    cparams.n_ubatch = static_cast<uint32_t>(std::min(ctx_len, kDefaultBatch));
    cparams.n_threads = pick_thread_plan(n_threads).decode;
    cparams.n_threads_batch = pick_thread_plan(n_threads).prefill;
    // Only the last token of each decode needs logits: it is the row we sample
    // the next token from. The batch's per-token logits flags say so; there is
    // no context-wide switch any more.
    cparams.embeddings = false;
    // AUTO, not ENABLED: an architecture without flash-attention support falls
    // back instead of failing the context.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    // On a GPU the KV cache and attention go with the layers; on the CPU there
    // is nothing to offload to.
    cparams.offload_kqv = !gpu_name.empty();
    cparams.op_offload = !gpu_name.empty();
    // f16 KV cache, which is what RamEstimate assumes.
    cparams.type_k = GGML_TYPE_F16;
    cparams.type_v = GGML_TYPE_F16;
    // Chat asks for logits on one row per batch (the last prompt token, then
    // each sampled token), and llama.cpp reserves the output head for
    // n_outputs_max rows: 512 by default, 113-462 MiB of compute buffer for
    // nothing. A batch asking for more rows than this aborts in llama.cpp, so
    // the caller passes 0 (a whole batch) for a model whose projector may
    // speak: the audio head reads every row of its batches.
    cparams.n_outputs_max = outputs_max > 0 ? static_cast<uint32_t>(outputs_max) : 0;
    // Sliding-window layers keep only their window (plus one ubatch) instead
    // of n_ctx cells. Prefix reuse already checks the window before trimming
    // (window_ok in nativeGenerate) and hybrid models restore a whole state
    // snapshot, so nothing relied on the full-size cache.
    cparams.swa_full = false;
    // A model with a projector may speak in its own voice. Speech then runs on
    // its own sequence (1), next to the chat (0) in one shared cache, so a
    // spoken reply no longer wipes the conversation the next message reuses.
    if (outputs_max == 0) {
        cparams.n_seq_max = 2;
        cparams.kv_unified = true;
    }

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return to_jstring(env, "could not create a context for " + model_path);
    }
    // A cancel stops the computation between graph nodes, not only between
    // 512-token batches: a batch is ~45 s on a slow phone, and a new message
    // (or Stop) must not wait that out. llama_decode then returns non-zero,
    // which the callers report as CANCELLED, and the next prompt starts over
    // from the kept states.
    llama_set_abort_callback(ctx, [](void * data) {
        return static_cast<LlamaHandle *>(data)->cancelled.load();
    }, h);

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return to_jstring(env, "the model has no vocabulary");
    }

    h->model = model;
    h->context = ctx;
    h->vocab = const_cast<void *>(static_cast<const void *>(vocab));
    h->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    h->n_batch = static_cast<int32_t>(cparams.n_batch);
    h->n_vocab = llama_vocab_n_tokens(vocab);
    h->eog_token = static_cast<int32_t>(llama_vocab_eot(vocab));
    h->path = model_path;
    h->n_threads = pick_threads(n_threads);
    h->state_snapshots = llama_model_is_recurrent(model) || llama_model_is_hybrid(model);
    h->loaded = true;
    // A fresh load starts uncancelled.
    h->cancelled.store(false);

    return nullptr;
}

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSetPrefixCache(
        JNIEnv * env, jclass, jlong handle, jstring dir, jstring key) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) return;
    std::lock_guard<std::mutex> lock(h->state);
    if (!jstring_utf8(env, dir, h->prefix_dir) || !jstring_utf8(env, key, h->prefix_key)) {
        h->prefix_dir.clear();
        h->prefix_key.clear();
    }
    if (!h->prefix_dir.empty()) mkdir(h->prefix_dir.c_str(), 0700);
}

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeUnload(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return;
    }
    // Same handshake as nativeDestroy: flag, then block on the lock.
    h->cancelled.store(true);
    std::lock_guard<std::mutex> lock(h->state);
    free_model_locked(h);
    h->path.clear();
    if (h->backend_initialised) {
        llama_backend_free();
        h->backend_initialised = false;
    }
}

JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeModelContextLength(
        JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(h->state);
    if (h->model == nullptr) {
        return 0;
    }
    const int32_t trained = llama_model_n_ctx_train(as_model(h->model));
    return trained > 0 ? trained : h->n_ctx;
}

JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCountTokens(
        JNIEnv * env, jclass, jlong handle, jstring text) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr || text == nullptr) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(h->state);
    if (!h->loaded || h->vocab == nullptr) {
        return -1;
    }
    std::string s;
    if (!jstring_utf8(env, text, s)) {
        return -1;
    }

    std::vector<llama_token> tokens;
    std::string err;
    if (!tokenize(as_vocab(h->vocab), s, tokens, err)) {
        return -1;
    }
    return static_cast<jint>(tokens.size());
}

// The GPUs llama.cpp can use here, one "backend\tdescription" entry each
// ("Vulkan\tPowerVR D-Series DXT-48-1536"). Registers the GPU backends on
// first call, which starts their drivers.
JNIEXPORT jobjectArray JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeGpuDevices(JNIEnv * env, jclass) {
    ensure_gpu_backends();
    std::vector<std::string> found;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!is_gpu(dev)) continue;
        found.push_back(std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev))) + "\t" +
                        ggml_backend_dev_description(dev));
    }
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(found.size()), string_class, nullptr);
    for (size_t i = 0; i < found.size(); ++i) {
        jstring s = to_jstring(env, found[i]);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeIsGenerating(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return JNI_FALSE;
    }
    return h->generating.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return;
    }
    // Safe when idle: the next load or generate clears the flag.
    h->cancelled.store(true);
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeTakeResult(JNIEnv * env, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(h->outcome_mutex);
    // Serialised so JNI carries one string instead of a struct:
    //   stop|promptTokens|completionTokens|prefillMs|decodeMs|error|<text>
    std::string out;
    out += std::to_string(h->outcome.stop);
    out += '|';
    out += std::to_string(h->outcome.prompt_tokens);
    out += '|';
    out += std::to_string(h->outcome.completion_tokens);
    out += '|';
    out += std::to_string(h->outcome.prefill_ms);
    out += '|';
    out += std::to_string(h->outcome.decode_ms);
    out += '|';
    std::string err = h->outcome.error;
    std::replace(err.begin(), err.end(), '\n', ' ');
    std::replace(err.begin(), err.end(), '|', ' ');
    out += err;
    out += '|';
    out += h->outcome.text;
    h->outcome = GenOutcome();
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring prompt, jstring grammar, jfloat temperature,
        jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty, jint penalty_last_n,
        jfloat frequency_penalty, jfloat presence_penalty, jint seed, jint max_tokens,
        jobject onToken, jboolean parse_special, jint stable_bytes) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return to_jstring(env, "null handle");
    }
    if (prompt == nullptr) {
        return to_jstring(env, "null prompt");
    }

    // Snapshot under the lock, then release it. A decode must never hold
    // `state`, or cancel() could not take it from the UI thread.
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_ctx = 0;
    int32_t n_vocab = 0;
    int32_t eog = -1;
    mtmd_context * mctx = nullptr;
    int32_t n_threads = 0;
    std::vector<std::vector<uint8_t>> media;
    {
        std::lock_guard<std::mutex> lock(h->state);
        if (!h->loaded || h->context == nullptr || h->vocab == nullptr) {
            return to_jstring(env, "no model is loaded");
        }
        ctx = as_context(h->context);
        vocab = as_vocab(h->vocab);
        n_ctx = h->n_ctx;
        n_vocab = h->n_vocab;
        eog = h->eog_token;
        mctx = reinterpret_cast<mtmd_context *>(h->mtmd);
        n_threads = h->n_threads;
        // Attachments belong to this generation only.
        media.swap(h->pending_media);
    }
    (void) n_threads;

    std::string prompt_str;
    if (!jstring_utf8(env, prompt, prompt_str)) {
        return to_jstring(env, "could not read the prompt");
    }

    std::string grammar_str;
    if (grammar != nullptr && !jstring_utf8(env, grammar, grammar_str)) {
        grammar_str.clear();
    }

    h->cancelled.store(false);
    h->generating.store(true);

    // Resolved once here, released on every exit path below.
    TokenSinkRef sink;
    if (!sink.resolve(env, onToken)) {
        std::lock_guard<std::mutex> lock(h->outcome_mutex);
        h->outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        h->outcome.error = "the token sink could not be resolved";
        h->generating.store(false);
        return to_jstring(env, h->outcome.error);
    }

    GenOutcome outcome;

    // Every exit below funnels through publish(), so this is the one place the
    // sink's global ref has to be released. Leaving it would pin the Java
    // listener for the lifetime of the process.
    auto publish = [&]() {
        sink.release(env);
        std::lock_guard<std::mutex> lock(h->outcome_mutex);
        h->outcome = outcome;
        h->generating.store(false);
    };

    // ---- multimodal input -----------------------------------------------------
    // With attachments the prompt carries one media marker per attachment, and
    // libmtmd turns text + media into chunks that are evaluated in place of the
    // plain-text prefill below. Owned here so every exit path frees them.
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> mm_chunks(
        nullptr, &mtmd_input_chunks_free);
    std::vector<std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>> mm_bitmaps;
    int32_t mm_prompt_tokens = 0;
    if (!media.empty()) {
        std::string mm_err;
        if (mctx == nullptr) {
            mm_err = "this model has no vision or audio projector loaded, so attachments cannot be read";
        }
        for (size_t i = 0; mm_err.empty() && i < media.size(); ++i) {
            mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_buf(
                mctx, media[i].data(), media[i].size(), false, mtmd_helper_init_opt_default());
            if (w.bitmap == nullptr) {
                mm_err = "attachment " + std::to_string(i + 1) +
                    " is not an image or audio format this model can read";
                break;
            }
            mm_bitmaps.emplace_back(w.bitmap, &mtmd_bitmap_free);
        }
        if (mm_err.empty()) {
            std::vector<const mtmd_bitmap *> raw;
            raw.reserve(mm_bitmaps.size());
            for (auto & b : mm_bitmaps) raw.push_back(b.get());
            mm_chunks.reset(mtmd_input_chunks_init());
            mtmd_input_text text{prompt_str.c_str(), prompt_str.size(), true, true};
            const int32_t rc = mtmd_tokenize(mctx, mm_chunks.get(), &text, raw.data(), raw.size());
            if (rc == 1) {
                mm_err = "the prompt and the attachments do not match (one marker per attachment)";
            } else if (rc != 0) {
                mm_err = "an attachment could not be preprocessed (" + std::to_string(rc) + ")";
            } else {
                mm_prompt_tokens = static_cast<int32_t>(mtmd_helper_get_n_tokens(mm_chunks.get()));
                if (mm_prompt_tokens > n_ctx - kGenerationHeadroom) {
                    mm_err = "the message with its attachments needs " + std::to_string(mm_prompt_tokens) +
                        " tokens and the context holds " + std::to_string(n_ctx);
                }
            }
        }
        if (!mm_err.empty()) {
            outcome.stop = static_cast<int32_t>(StopCode::ERROR);
            outcome.error = mm_err;
            publish();
            return to_jstring(env, outcome.error);
        }
    }

    // ---- tokenise -------------------------------------------------------------
    std::vector<llama_token> tokens;
    std::string tok_err;
    if (!mm_chunks && !tokenize(vocab, prompt_str, tokens, tok_err, parse_special == JNI_TRUE)) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = tok_err;
        publish();
        return to_jstring(env, outcome.error);
    }
    if (!mm_chunks && tokens.empty()) {
        outcome.stop = static_cast<int32_t>(StopCode::COMPLETED);
        publish();
        return nullptr;
    }

    // A prompt that fills the whole window leaves nowhere to put the answer.
    // It is refused, not cut: cutting from the left dropped the system prompt
    // and the tools without anyone being told. The agent loop sizes prompts
    // with this model's tokenizer and compacts before this can happen, so
    // reaching here means its budget was wrong, and the error says by how much.
    const int32_t max_prompt = n_ctx - kGenerationHeadroom;
    if (max_prompt <= 0) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = "the context window of " + std::to_string(n_ctx) + " is too small to generate";
        publish();
        return to_jstring(env, outcome.error);
    }
    if (!mm_chunks && static_cast<int32_t>(tokens.size()) > max_prompt) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = "context window too small: the prompt is " + std::to_string(tokens.size()) +
            " tokens and the window holds " + std::to_string(max_prompt);
        publish();
        return to_jstring(env, outcome.error);
    }
    const int32_t prompt_tokens = mm_chunks ? mm_prompt_tokens : static_cast<int32_t>(tokens.size());

    // Where the system prompt ends, in this prompt's tokens: the first
    // `stable_bytes` of the text, tokenised alone, compared with the whole.
    // Only the tokens both agree on count, so a merge at the boundary moves the
    // anchor back rather than restoring a state for text that is not there.
    int32_t anchor_at = 0;
    if (!mm_chunks && stable_bytes > 0 && static_cast<size_t>(stable_bytes) < prompt_str.size()) {
        std::vector<llama_token> head;
        std::string head_err;
        if (tokenize(vocab, prompt_str.substr(0, static_cast<size_t>(stable_bytes)), head, head_err,
                     parse_special == JNI_TRUE)) {
            const size_t n = std::min(head.size(), tokens.size());
            size_t k = 0;
            while (k < n && head[k] == tokens[k]) ++k;
            anchor_at = static_cast<int32_t>(k);
        }
        if (anchor_at < kMinAnchorTokens || anchor_at >= prompt_tokens) anchor_at = 0;
    }

    llama_sampler * chain = build_chain(
        vocab, grammar_str, temperature, top_k, top_p, min_p, repeat_penalty,
        penalty_last_n, frequency_penalty, presence_penalty, static_cast<uint32_t>(seed));
    if (chain == nullptr) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = "could not build the sampler chain";
        publish();
        return to_jstring(env, outcome.error);
    }
    llama_sampler_reset(chain);
    llama_sampler * grammar_smpl = build_grammar(vocab, grammar_str);

    constexpr llama_seq_id kPromptSeq = 0;
    // How far before a prompt's end a hybrid model's state is copied: past the
    // assistant turn's opening, whose tokens change once a reply follows it.
    constexpr int32_t kSnapshotTail = 16;

    const int64_t start = now_ms();
    int32_t pos = 0;
    bool failed = false;
    // Trailing bytes of a UTF-8 character split across two tokens.
    std::string partial_utf8;

    // The candidate array for sampling, hoisted out of the decode loop.
    //
    // WHY THIS IS HERE AND NOT INSIDE THE LOOP: it used to be constructed per
    // token, as
    //
    //     std::vector<llama_token_data> data(n_vocab);
    //
    // `llama_token_data` is {int32 id, float logit, float p} = 12 bytes, and
    // n_vocab is 151,936 for Qwen3. That is 1,823,232 bytes - 1.74 MiB - of
    // heap allocated, filled and freed for EVERY generated token, on top of a
    // 151,936-iteration loop that then rewrites every element anyway.
    //
    // WHY HOISTING IS PROVABLY BEHAVIOUR-PRESERVING, not a guess: the loop body
    // overwrites all n_vocab entries unconditionally
    //
    //     data[v] = llama_token_data{v, logits[v], 0.0f};
    //
    // so any reordering the sampler chain did to the previous iteration (top_p
    // and the penalties both sort in place) is fully overwritten before it can
    // be read again. The vector is therefore scratch space, and scratch space
    // does not need re-allocating per iteration.
    //
    // It is sized once, from the vocab, which cannot change for the life of the
    // context. The cursor and size are re-stated every iteration anyway, so
    // nothing downstream depends on them being sticky.
    std::vector<llama_token_data> candidates;
    if (n_vocab > 0) {
        candidates.resize(static_cast<size_t>(n_vocab));
    }

    // ---- prefill --------------------------------------------------------------
    // Prompt-prefix reuse. Every agent step rebuilds the prompt, and the new
    // one starts like the previous one (same system part, same history) and
    // grows by the last action and its observation. The tokens where the two
    // agree are not processed again. They rarely agree to the very end: the
    // previous prompt ended in the assistant turn's opening, which tokenizes
    // differently once the reply follows it.
    //
    // An attention-only model still holds the previous prompt, and the reply
    // after it, in its KV cache: the cache is cut back to where the prompts
    // part and reused in place. A hybrid model (LFM's short convolutions)
    // keeps recurrent state that cannot be cut back, so its state was copied
    // during the previous prefill, kSnapshotTail tokens before the end, and is
    // restored when the new prompt agrees at least that far.
    //
    // Only a longer prompt reuses anything: an agent step always grows, and a
    // repeated identical prompt (the speed test) is processed in full.
    llama_memory_t mem = llama_get_memory(ctx);
    int32_t reused = 0;
    const int32_t previous = static_cast<int32_t>(h->snap_tokens.size());
    const int32_t common = mm_chunks ? 0 : static_cast<int32_t>(
        std::mismatch(h->snap_tokens.begin(), h->snap_tokens.end(), tokens.begin(),
                      tokens.end()).first - h->snap_tokens.begin());
    const bool grows = !mm_chunks && previous > 0 && prompt_tokens > previous;
    // A sliding-window model (Gemma) evicts positions that fall out of its
    // window; the first new token attends back n_swa positions, so those must
    // still be cached (the rule llama-server uses).
    const int32_t n_swa = llama_model_n_swa(as_model(h->model));
    const llama_pos window_from = std::max(0, n_swa > 0 ? common - n_swa + 2 : common);
    const llama_pos cached_from = llama_memory_seq_pos_min(mem, kPromptSeq);
    const bool window_ok = cached_from >= 0 && cached_from <= window_from;
    if (grows && !h->state_snapshots && common > 0 && window_ok &&
        llama_memory_seq_rm(mem, kPromptSeq, common, -1)) {
        reused = common;
    } else {
        llama_memory_clear(mem, true);
        // The state after the last reply first: it covers the reply too.
        if (grows && h->state_snapshots && !h->gen_state.empty() && h->gen_at > 0 && common >= h->gen_at) {
            if (llama_state_seq_set_data(ctx, h->gen_state.data(), h->gen_state.size(), kPromptSeq) > 0) {
                reused = h->gen_at;
            } else {
                llama_memory_clear(mem, true);
            }
        }
        if (reused == 0 && grows && h->state_snapshots && !h->snap_state.empty() && h->snap_at > 0 &&
            common >= h->snap_at) {
            if (llama_state_seq_set_data(ctx, h->snap_state.data(), h->snap_state.size(), kPromptSeq) > 0) {
                reused = h->snap_at;
            } else {
                llama_memory_clear(mem, true);
            }
        }
    }
    // Nothing later was reusable (the prompt changed after the system prompt,
    // or this is the first message since the model loaded): start from the
    // system prompt's saved state instead of from token 0.
    if (reused == 0 && anchor_at > 0) {
        reused = restore_anchor(h, ctx, tokens, anchor_at, kPromptSeq);
    }
    // The anchor is taken while prefilling through it, unless it is already the
    // one held for exactly these tokens.
    const bool anchor_take = anchor_at > reused && !(
        static_cast<int32_t>(h->anchor_tokens.size()) == anchor_at && !h->anchor_state.empty() &&
        std::equal(h->anchor_tokens.begin(), h->anchor_tokens.end(), tokens.begin()));
    // Where this prompt's state is copied for the next step (hybrid models
    // only). When that point is already covered by the restored copy, the copy
    // is that state and is kept as it is.
    int32_t snap_at = 0;
    bool snapped = false;
    if (h->state_snapshots && !mm_chunks) {
        snap_at = std::max(0, prompt_tokens - kSnapshotTail);
        if (snap_at <= reused) {
            snap_at = reused;
            snapped = reused > 0;
        }
    }
    pos = reused;
    outcome.reused_tokens = reused;
    const int32_t batch_limit = std::max(1, std::min(n_ctx, kDefaultBatch));
    if (mm_chunks) {
        // Text chunks are decoded, media chunks encoded by the projector and
        // their embeddings decoded; logits only for the last position.
        llama_pos new_n_past = 0;
        const int32_t rc = mtmd_helper_eval_chunks(
            mctx, ctx, mm_chunks.get(), 0, kPromptSeq, batch_limit, true, &new_n_past);
        if (rc != 0) {
            outcome.stop = static_cast<int32_t>(StopCode::ERROR);
            outcome.error = "evaluating the message with its attachments failed with " + std::to_string(rc);
            failed = true;
        } else {
            pos = static_cast<int32_t>(new_n_past);
        }
    }
    for (int32_t off = reused, n = 0; !mm_chunks && off < prompt_tokens; off += n) {
        if (h->cancelled.load()) {
            outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
            break;
        }
        n = std::min(batch_limit, prompt_tokens - off);
        // A batch ends exactly where a state is to be copied.
        if (snap_at > off && snap_at < off + n) n = snap_at - off;
        if (anchor_take && anchor_at > off && anchor_at < off + n) n = anchor_at - off;
        llama_batch batch = llama_batch_init(n, 0, 1);
        for (int32_t i = 0; i < n; ++i) {
            batch.token[i] = tokens[static_cast<size_t>(off + i)];
            batch.pos[i] = off + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = kPromptSeq;
            // Logits only for the final prompt token: that is the row we sample from.
            batch.logits[i] = (off + i == prompt_tokens - 1) ? 1 : 0;
        }
        // THE BUG. llama_batch_init() allocates the arrays and leaves n_tokens
        // at 0 — it is the caller's job to say how many of those slots are real.
        // Without this the batch was structurally empty, so every single
        // llama_decode returned -1 and NO token was ever decoded. Every other
        // symptom chased from here (the model's fault, flash attention, the
        // sequence split, the llama.cpp version) was this one line. Upstream
        // avoids it by using llama_batch_get_one(), which sets n_tokens itself.
        batch.n_tokens = n;
        const int32_t rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0 && h->cancelled.load()) {
            outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
            break;
        }
        if (rc != 0) {
            outcome.stop = static_cast<int32_t>(StopCode::ERROR);
            outcome.error = "llama_decode failed during prefill with " + std::to_string(rc);
            failed = true;
            break;
        }
        pos += n;
        if (anchor_take && off + n == anchor_at) save_anchor(h, ctx, tokens, anchor_at, kPromptSeq);
        if (!snapped && snap_at > 0 && off + n == snap_at) {
            const size_t size = llama_state_seq_get_size(ctx, kPromptSeq);
            h->snap_state.resize(size);
            snapped = size > 0 && llama_state_seq_get_data(ctx, h->snap_state.data(), size, kPromptSeq) == size;
        }
    }

    // Remember the prompt for the next step. A media prompt is not
    // represented by its tokens, so it is never reused.
    if (!failed && outcome.stop != static_cast<int32_t>(StopCode::CANCELLED) && !mm_chunks) {
        h->snap_tokens = tokens;
        h->snap_at = snapped ? snap_at : 0;
        if (!snapped) h->snap_state.clear();
    } else {
        h->snap_tokens.clear();
        h->snap_state.clear();
        h->snap_at = 0;
    }

    const int64_t after_prefill = now_ms();
    if (!failed) {
        outcome.prefill_ms = after_prefill - start;
        __android_log_print(ANDROID_LOG_INFO, "LocalLlama",
                            "prefill: %d tokens, %d reused (%d of the last %d agree), %lld ms",
                            prompt_tokens, reused, common, previous, static_cast<long long>(outcome.prefill_ms));
    }

    // ---- decode loop ----------------------------------------------------------
    std::vector<llama_token> gen_tokens;
    if (!failed && outcome.stop != static_cast<int32_t>(StopCode::CANCELLED)) {
        // max_tokens < 0: prefill only (the agent warming the next turn while
        // the user reads); 0: the default 512.
        const int32_t limit = max_tokens < 0 ? 0 : (max_tokens > 0 ? max_tokens : 512);
        outcome.stop = static_cast<int32_t>(StopCode::COMPLETED);

        for (int32_t i = 0; i < limit; ++i) {
            if (h->cancelled.load()) {
                outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
                break;
            }
            if (pos >= n_ctx) {
                outcome.stop = static_cast<int32_t>(StopCode::MAX_TOKENS);
                break;
            }

            // Sample the next token from the last row's logits.
            llama_token id = -1;
            const float * logits = llama_get_logits_ith(ctx, -1);
            if (logits != nullptr && n_vocab > 0) {
                // llama_token_data is a plain {id, logit, p} triple; the context
                // hands back a float* of raw logits, so wrap rather than copy
                // the values. `candidates` is the hoisted scratch buffer - see
                // its declaration above for why reusing it cannot change what
                // is sampled.
                for (int32_t v = 0; v < n_vocab; ++v) {
                    candidates[static_cast<size_t>(v)] = llama_token_data{v, logits[v], 0.0f};
                }
                llama_token_data_array cur;
                cur.data = candidates.data();
                cur.size = candidates.size();
                cur.selected = -1;
                cur.sorted = false;
                llama_sampler_apply(chain, &cur);
                if (cur.selected >= 0 &&
                    static_cast<size_t>(cur.selected) < cur.size) {
                    id = cur.data[cur.selected].id;
                }
                // Grammar check on the one sampled token; only when it breaks the
                // grammar is the full vocabulary constrained and sampled again.
                if (grammar_smpl != nullptr && id >= 0) {
                    llama_token_data single{id, logits[id], 0.0f};
                    llama_token_data_array one{&single, 1, -1, false};
                    llama_sampler_apply(grammar_smpl, &one);
                    if (single.logit == -INFINITY) {
                        for (int32_t v = 0; v < n_vocab; ++v) {
                            candidates[static_cast<size_t>(v)] = llama_token_data{v, logits[v], 0.0f};
                        }
                        cur.data = candidates.data();
                        cur.size = candidates.size();
                        cur.selected = -1;
                        cur.sorted = false;
                        llama_sampler_apply(grammar_smpl, &cur);
                        llama_sampler_apply(chain, &cur);
                        id = (cur.selected >= 0 && static_cast<size_t>(cur.selected) < cur.size)
                            ? cur.data[cur.selected].id : -1;
                    }
                }
            }
            if (id < 0) {
                outcome.stop = static_cast<int32_t>(StopCode::ERROR);
                outcome.error = "the sampler produced no token";
                failed = true;
                break;
            }

            // EOG first, before we count or decode it.
            if ((eog >= 0 && id == eog) || llama_vocab_is_eog(vocab, id)) {
                break;
            }
            if (i == limit - 1) {
                outcome.stop = static_cast<int32_t>(StopCode::MAX_TOKENS);
            }

            if (grammar_smpl != nullptr) llama_sampler_accept(grammar_smpl, id);
            llama_sampler_accept(chain, id);
            outcome.completion_tokens++;
            gen_tokens.push_back(id);

            // Decode the sampled token so the next one has context.
            llama_batch batch = llama_batch_init(1, 0, 1);
            batch.token[0] = id;
            batch.pos[0] = pos;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = kPromptSeq;
            batch.logits[0] = 1;
            batch.n_tokens = 1;  // see the prefill note: never left implicit
            const int32_t rc = llama_decode(ctx, batch);
            llama_batch_free(batch);
            if (rc != 0 && h->cancelled.load()) {
                outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
                break;
            }
            if (rc != 0) {
                outcome.stop = static_cast<int32_t>(StopCode::ERROR);
                outcome.error = "llama_decode failed with " + std::to_string(rc);
                failed = true;
                break;
            }
            pos++;

            // Detokenise and stream. A multi-byte UTF-8 character can straddle
            // two tokens, so bytes are only handed to Java once they form whole
            // characters.
            char buf[256];
            const int32_t n_piece = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
            if (n_piece > 0) {
                const std::string raw_piece(buf, static_cast<size_t>(n_piece));
                // The full text keeps every byte, partial tail included: the
                // final result must be exactly what the model produced.
                outcome.text += raw_piece;
                std::string whole;
                split_utf8(raw_piece, whole, partial_utf8);
                if (!whole.empty() && !sink.emit(env, whole)) {
                    // The sink threw or went away: stop rather than spin.
                    outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
                    break;
                }
            }
        }
    }

    outcome.decode_ms = now_ms() - after_prefill;
    outcome.prompt_tokens = prompt_tokens;

    // The reply is in the cache after the prompt (every sampled token but the
    // end token was decoded). The agent replays the model's own output in its
    // next prompt, so the reusable prefix runs through the reply: attention
    // models trim to the first mismatch as before, hybrid models keep a copy of
    // the state here. A reply that re-tokenises differently falls back to the
    // copy taken before the reply.
    h->gen_state.clear();
    h->gen_at = 0;
    if (!failed && !mm_chunks && !gen_tokens.empty() &&
        outcome.stop != static_cast<int32_t>(StopCode::CANCELLED) &&
        h->snap_tokens.size() == tokens.size() && pos == prompt_tokens + static_cast<int32_t>(gen_tokens.size())) {
        h->snap_tokens.insert(h->snap_tokens.end(), gen_tokens.begin(), gen_tokens.end());
        if (h->state_snapshots) {
            const size_t size = llama_state_seq_get_size(ctx, kPromptSeq);
            h->gen_state.resize(size);
            if (size > 0 && llama_state_seq_get_data(ctx, h->gen_state.data(), size, kPromptSeq) == size) {
                h->gen_at = pos;
                // When the reply's text tokenises back to exactly the tokens
                // generated, a next prompt that repeats it reaches gen_at, so
                // the copy from before the reply is never the one restored:
                // free it, so a hybrid model holds two copies (system prompt,
                // after the reply), not three. Anything that does not round-trip
                // (a special token mid-reply, a merge) keeps it as the fallback.
                std::vector<llama_token> again(gen_tokens.size() + 8);
                const int32_t n_again = llama_tokenize(
                    vocab, outcome.text.data(), static_cast<int32_t>(outcome.text.size()), again.data(),
                    static_cast<int32_t>(again.size()), /*add_special=*/false, parse_special == JNI_TRUE);
                if (n_again == static_cast<int32_t>(gen_tokens.size()) &&
                    std::equal(gen_tokens.begin(), gen_tokens.end(), again.begin())) {
                    std::vector<uint8_t>().swap(h->snap_state);
                    h->snap_at = 0;
                }
            } else {
                std::vector<uint8_t>().swap(h->gen_state);
            }
        }
    }
    publish();

    llama_sampler_free(chain);
    if (grammar_smpl != nullptr) llama_sampler_free(grammar_smpl);

    // The accumulated text is authoritative; the token stream was for latency.
    if (!failed && !outcome.error.empty()) {
        return to_jstring(env, outcome.error);
    }
    return nullptr;
}

}  // extern "C"

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeLoadProjector(
        JNIEnv * env, jclass, jlong handle, jstring path) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) return to_jstring(env, "null handle");
    if (path == nullptr) return to_jstring(env, "null projector path");
    std::string projector_path;
    if (!jstring_utf8(env, path, projector_path)) return to_jstring(env, "could not read the projector path");

    std::lock_guard<std::mutex> lock(h->state);
    if (!h->loaded || h->model == nullptr) return to_jstring(env, "no model is loaded");
    if (h->mtmd != nullptr) {
        mtmd_free(reinterpret_cast<mtmd_context *>(h->mtmd));
        h->mtmd = nullptr;
    }
    mtmd_context_params params = mtmd_context_params_default();
    // The projector stays on the CPU even when the model is on a GPU: clip
    // would take the first GPU it finds, not necessarily the model's, and its
    // load sits outside the GPU crash guard.
    params.use_gpu = false;
    params.print_timings = false;
    params.warmup = false;   // a warmup encode costs seconds of CPU on a phone
    params.n_threads = pick_threads(h->n_threads);
    mtmd_context * mctx = mtmd_init_from_file(projector_path.c_str(), as_model(h->model), params);
    if (mctx == nullptr) {
        return to_jstring(env, "could not load the projector " + projector_path);
    }
    h->mtmd = mctx;
    return nullptr;
}

// Bit 1: image input. Bit 2: audio input. Bit 4: speech output. 0 without a projector.
JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeProjectorCaps(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) return 0;
    std::lock_guard<std::mutex> lock(h->state);
    const mtmd_context * mctx = reinterpret_cast<const mtmd_context *>(h->mtmd);
    if (mctx == nullptr) return 0;
    jint caps = 0;
    if (mtmd_support_vision(mctx)) caps |= 1;
    if (mtmd_support_audio(mctx)) caps |= 2;
    if (mtmd_gen_audio_get_info(mctx).type != MTMD_GEN_AUDIO_TYPE_NONE) caps |= 4;
    return caps;
}

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSetMedia(
        JNIEnv * env, jclass, jlong handle, jobjectArray media) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) return;
    std::vector<std::vector<uint8_t>> items;
    const jsize n = media == nullptr ? 0 : env->GetArrayLength(media);
    for (jsize i = 0; i < n; ++i) {
        auto arr = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(media, i));
        if (arr == nullptr) continue;
        const jsize len = env->GetArrayLength(arr);
        std::vector<uint8_t> bytes(static_cast<size_t>(len));
        if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(bytes.data()));
        env->DeleteLocalRef(arr);
        items.push_back(std::move(bytes));
    }
    std::lock_guard<std::mutex> lock(h->state);
    h->pending_media.swap(items);
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeMediaMarker(JNIEnv * env, jclass) {
    return env->NewStringUTF(mtmd_default_marker());
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeApplyTemplate(
        JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean add_assistant) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr || roles == nullptr || contents == nullptr) {
        return nullptr;
    }
    const jsize n = env->GetArrayLength(roles);
    if (n == 0 || n != env->GetArrayLength(contents)) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(h->state);
    if (!h->loaded || h->model == nullptr || h->vocab == nullptr) {
        return nullptr;
    }
    // Null for a model with no template. A nullptr name asks for the default.
    const char * tmpl = llama_model_chat_template(as_model(h->model), nullptr);
    if (tmpl == nullptr) {
        return nullptr;
    }
    const std::vector<std::string> specials = control_token_texts(as_vocab(h->vocab));

    // Copy everything into owned strings first: the llama_chat_message pointers
    // must stay valid across both render calls, and each jstring local ref is
    // released as soon as its characters are copied.
    std::vector<std::string> role_text(static_cast<size_t>(n));
    std::vector<std::string> content_text(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto rj = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto cj = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        std::string r;
        std::string c;
        const bool ok = copy_jstring(env, rj, r) && copy_jstring(env, cj, c);
        if (rj != nullptr) env->DeleteLocalRef(rj);
        if (cj != nullptr) env->DeleteLocalRef(cj);
        if (!ok) {
            return nullptr;
        }
        role_text[static_cast<size_t>(i)] = std::move(r);
        content_text[static_cast<size_t>(i)] = neutralise_control_text(std::move(c), specials);
        total += role_text[static_cast<size_t>(i)].size() + content_text[static_cast<size_t>(i)].size();
    }

    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    for (size_t i = 0; i < msgs.size(); ++i) {
        msgs[i].role = role_text[i].c_str();
        msgs[i].content = content_text[i].c_str();
    }

    // llama.cpp returns the length it needs when the buffer is too small. Twice
    // the input is nearly always enough; the retry covers the rest.
    std::vector<char> buf(2 * total + 512);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                            add_assistant == JNI_TRUE, buf.data(),
                                            static_cast<int32_t>(buf.size()));
    if (len < 0) {
        // Not a template llama.cpp knows how to render.
        return nullptr;
    }
    if (static_cast<size_t>(len) > buf.size()) {
        buf.assign(static_cast<size_t>(len) + 1, '\0');
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                        add_assistant == JNI_TRUE, buf.data(),
                                        static_cast<int32_t>(buf.size()));
        if (len < 0 || static_cast<size_t>(len) > buf.size()) {
            return nullptr;
        }
    }
    const std::string rendered(buf.data(), static_cast<size_t>(len));
    return to_jstring(env, rendered);
}

namespace {

// The prompt a speech-capable projector speaks. LFM3 ships a "tts" chat
// template in the GGUF; it is Jinja, and this build has no Jinja engine, so
// its one known shape is rendered here. Any other model gets the bare text,
// which is what a TTS-only model (PocketTTS, Soprano) expects.
std::string tts_prompt(const llama_model * model, const llama_vocab * vocab, const std::string & text,
                       const std::string & lang, const std::string & voice) {
    const char * tmpl = llama_model_chat_template(model, "tts");
    if (tmpl == nullptr || std::strstr(tmpl, "Perform TTS in") == nullptr) {
        return text;
    }
    static const char * kNames[][2] = {
        {"ar", "Arabic"}, {"da", "Danish"}, {"de", "German"}, {"el", "Greek"}, {"en", "American English"},
        {"en-us", "American English"}, {"en-gb", "British English"}, {"fi", "Finnish"}, {"fr", "French"},
        {"he", "Hebrew"}, {"it", "Italian"}, {"ja", "Japanese"}, {"ko", "Korean"}, {"nl", "Dutch"},
        {"pl", "Polish"}, {"pt", "Portuguese"}, {"es", "Spanish"}, {"sv", "Swedish"}, {"tr", "Turkish"},
        {"zh", "Chinese"},
    };
    std::string code = lang.empty() ? "en" : lang;
    std::transform(code.begin(), code.end(), code.begin(), ::tolower);
    std::string language = lang;
    for (const auto & n : kNames) {
        if (code == n[0]) { language = n[1]; break; }
    }
    const std::string v = (voice == "male") ? "male" : "female";
    std::string bos;
    const llama_token bos_id = llama_vocab_bos(vocab);
    if (bos_id != LLAMA_TOKEN_NULL) {
        char buf[64];
        const int n = llama_token_to_piece(vocab, bos_id, buf, sizeof(buf), 0, true);
        if (n > 0) bos.assign(buf, static_cast<size_t>(n));
    }
    return bos + "<|im_start|>system\nPerform TTS in " + language + " using a " + v + " voice.<|im_end|>\n" +
        "<|im_start|>user\n" + text + "<|im_end|>\n" +
        "<|im_start|>assistant\n<think></think><|audio_start|>";
}

std::string read_jstring(JNIEnv * env, jstring s) {
    std::string out;
    jstring_utf8(env, s, out);
    return out;
}

}  // namespace

// Speaks [text] with the loaded model's speech projector. Returns a WAV file
// (16-bit PCM, mono) or null on failure / no speech support. Blocking; the
// Kotlin side holds the generation lock, so this never overlaps a generate.
JNIEXPORT jbyteArray JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSpeak(
        JNIEnv * env, jclass, jlong handle, jstring text, jstring lang, jstring voice, jint max_frames) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) return nullptr;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd_context * mctx = nullptr;
    int32_t n_batch = kDefaultBatch;
    {
        std::lock_guard<std::mutex> lock(h->state);
        if (!h->loaded || h->mtmd == nullptr) return nullptr;
        model = as_model(h->model);
        ctx = as_context(h->context);
        vocab = as_vocab(h->vocab);
        mctx = reinterpret_cast<mtmd_context *>(h->mtmd);
        n_batch = std::max(1, std::min(h->n_ctx, kDefaultBatch));
    }
    if (mtmd_gen_audio_get_info(mctx).type == MTMD_GEN_AUDIO_TYPE_NONE) return nullptr;

    const std::string prompt = tts_prompt(model, vocab, read_jstring(env, text), read_jstring(env, lang),
                                          read_jstring(env, voice));
    h->cancelled.store(false);
    h->generating.store(true);

    // Speech runs on its own sequence when the context has one (a model loaded
    // with a projector), leaving the chat's cache and its kept states alone.
    // Otherwise, or if it does not fit beside the chat, it runs on the chat's
    // sequence after clearing it, as it always did.
    llama_memory_t speech_mem = llama_get_memory(ctx);
    llama_seq_id speech_seq = llama_n_seq_max(ctx) > 1 ? kSpeechSeq : 0;
    auto clear_chat = [&]() {
        llama_memory_clear(speech_mem, true);
        h->snap_tokens.clear();
        h->snap_state.clear();
        h->gen_state.clear();
        h->gen_at = 0;
    };
    if (speech_seq == 0) clear_chat();
    else llama_memory_seq_rm(speech_mem, speech_seq, -1, -1);
    // Hidden states feed the audio head, so embeddings are on for this call only.
    llama_set_embeddings(ctx, true);
    mtmd_helper_gen_audio * gen = mtmd_helper_gen_audio_init(ctx, mctx);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    jbyteArray result = nullptr;
    bool ok = gen != nullptr;
    if (ok) {
        mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
        inp.seq_id = speech_seq;
        inp.prompt = prompt.c_str();
        inp.prompt_len = prompt.size();
        inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_WAV;
        ok = mtmd_helper_gen_audio_set_input(gen, &inp) == 0;
    }
    while (ok) {
        const int32_t left = mtmd_helper_gen_audio_step_prompt(gen, n_batch);
        if (left < 0 && speech_seq != 0 && !h->cancelled.load()) {
            // No room beside the chat: fall back to the chat's sequence.
            __android_log_print(ANDROID_LOG_INFO, "LocalLlama",
                                "speech: no room beside the chat, clearing it to speak");
            llama_memory_seq_rm(speech_mem, speech_seq, -1, -1);
            speech_seq = 0;
            clear_chat();
            mtmd_helper_gen_audio_reset(gen);
            mtmd_helper_gen_audio_inp retry = mtmd_helper_gen_audio_inp_default();
            retry.seq_id = 0;
            retry.prompt = prompt.c_str();
            retry.prompt_len = prompt.size();
            retry.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_WAV;
            ok = mtmd_helper_gen_audio_set_input(gen, &retry) == 0;
            continue;
        }
        if (left < 0) ok = false;
        if (left <= 0) break;
        if (h->cancelled.load()) ok = false;
    }
    if (ok) {
        llama_token sampled = llama_sampler_sample(smpl, ctx, -1);
        const float * h_state = llama_get_embeddings_ith(ctx, -1);
        const int32_t limit = max_frames > 0 ? max_frames : 2048;
        bool stop = false;
        for (int32_t frame = 0; ok && !stop && frame < limit; ++frame) {
            if (h->cancelled.load()) { ok = false; break; }
            const float * h_next = nullptr;
            if (mtmd_helper_gen_audio_step_gen(gen, sampled, h_state, &h_next, &stop) != 0) { ok = false; break; }
            if (h_next == nullptr) break;
            h_state = h_next;
            sampled = llama_sampler_sample(smpl, ctx, -1);
        }
    }
    if (ok) {
        int32_t sample_rate = 0;
        const char * data = nullptr;
        size_t len = 0;
        int64_t n_samples = 0;
        if (mtmd_helper_gen_audio_get_output(gen, &sample_rate, &data, &len, &n_samples) == 0 && data != nullptr && len > 0) {
            result = env->NewByteArray(static_cast<jsize>(len));
            if (result != nullptr) {
                env->SetByteArrayRegion(result, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
            }
        }
    }
    llama_sampler_free(smpl);
    if (gen != nullptr) mtmd_helper_gen_audio_free(gen);
    llama_set_embeddings(ctx, false);
    if (speech_seq != 0) {
        llama_memory_seq_rm(speech_mem, speech_seq, -1, -1);
    } else {
        clear_chat();
    }
    h->generating.store(false);
    return result;
}

extern "C" {

// ---- embeddings -----------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeLoadEmbeddingModel(
        JNIEnv * env, jclass, jlong handle, jstring path, jint n_threads) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return to_jstring(env, "null handle");
    }
    if (path == nullptr) {
        return to_jstring(env, "null model path");
    }
    std::string model_path;
    if (!jstring_utf8(env, path, model_path)) {
        return to_jstring(env, "could not read the model path");
    }

    std::lock_guard<std::mutex> lock(h->state);
    free_model_locked(h);

    ggml_log_set(log_to_logcat, nullptr);
    llama_backend_init();
    h->backend_initialised = true;

    llama_model_params mparams = llama_model_default_params();
    mparams.devices = kNoDevices;  // embeddings stay on the CPU, next to the chat model
    mparams.n_gpu_layers = 0;
    // Same reasoning as nativeLoadModel: MMAP, never MMAP_MLOCK.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.vocab_only = false;

    const int32_t threads = pick_threads(n_threads);

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        return to_jstring(env, "could not load an embedding model from " + model_path);
    }

    const int32_t trained_ctx = llama_model_n_ctx_train(model);
    int32_t ctx_len = kEmbeddingContext;
    if (trained_ctx > 0 && ctx_len > trained_ctx) {
        ctx_len = trained_ctx;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(ctx_len);
    // One passage per decode, so the batch holds a whole passage.
    cparams.n_batch = static_cast<uint32_t>(ctx_len);
    cparams.n_ubatch = static_cast<uint32_t>(ctx_len);
    cparams.n_threads = pick_thread_plan(n_threads).decode;
    cparams.n_threads_batch = pick_thread_plan(n_threads).prefill;
    // Pooled embeddings: llama_get_embeddings_seq returns one vector per sequence.
    // UNSPECIFIED lets the model's GGUF choose its pooling (mean for EmbeddingGemma).
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.offload_kqv = false;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return to_jstring(env, "could not create an embedding context for " + model_path);
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return to_jstring(env, "the embedding model has no vocabulary");
    }

    h->model = model;
    h->context = ctx;
    h->vocab = const_cast<void *>(static_cast<const void *>(vocab));
    h->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    h->n_batch = static_cast<int32_t>(cparams.n_batch);
    h->n_vocab = llama_vocab_n_tokens(vocab);
    h->eog_token = static_cast<int32_t>(llama_vocab_eot(vocab));
    h->n_embd = llama_model_n_embd_out(model);
    h->path = model_path;
    h->n_threads = threads;
    h->loaded = true;
    h->cancelled.store(false);

    return nullptr;
}

JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeEmbeddingSize(JNIEnv *, jclass, jlong handle) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(h->state);
    return h->loaded ? h->n_embd : 0;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeEmbed(
        JNIEnv * env, jclass, jlong handle, jobjectArray texts) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr || texts == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(h->state);
    if (!h->loaded || h->context == nullptr || h->vocab == nullptr || h->n_embd <= 0) {
        return nullptr;
    }

    llama_context * ctx = as_context(h->context);
    const llama_vocab * vocab = as_vocab(h->vocab);
    const size_t dim = static_cast<size_t>(h->n_embd);
    const jsize count = env->GetArrayLength(texts);
    const int32_t limit = h->n_ctx;

    std::vector<float> flat;
    flat.reserve(static_cast<size_t>(count) * dim);
    // One batch, reused for every text: each text is at most `limit` tokens.
    llama_batch batch = llama_batch_init(limit, 0, 1);
    bool ok = true;

    for (jsize i = 0; i < count && ok; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
        std::string text;
        const bool read = jstring_utf8(env, js, text);
        if (js != nullptr) env->DeleteLocalRef(js);
        if (!read || env->ExceptionCheck()) {
            ok = false;
            break;
        }

        std::vector<llama_token> tokens;
        std::string err;
        if (!tokenize(vocab, text, tokens, err)) {
            __android_log_print(ANDROID_LOG_ERROR, "LocalLlama", "embedding tokenize: %s", err.c_str());
            ok = false;
            break;
        }
        if (static_cast<int32_t>(tokens.size()) > limit) {
            tokens.resize(static_cast<size_t>(limit));
        }
        if (tokens.empty()) {
            // Nothing to embed: a zero vector, which ranks as no similarity at all.
            flat.insert(flat.end(), dim, 0.0f);
            continue;
        }

        // Each text is its own sequence, so nothing from the previous text is attended to.
        llama_memory_clear(llama_get_memory(ctx), true);
        const int32_t n = static_cast<int32_t>(tokens.size());
        for (int32_t t = 0; t < n; ++t) {
            batch.token[t] = tokens[static_cast<size_t>(t)];
            batch.pos[t] = t;
            batch.n_seq_id[t] = 1;
            batch.seq_id[t][0] = 0;
            // Every token is output: pooling needs all of them.
            batch.logits[t] = 1;
        }
        batch.n_tokens = n;
        const int32_t rc = llama_decode(ctx, batch);
        if (rc != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "LocalLlama", "embedding decode failed with %d", rc);
            ok = false;
            break;
        }

        const float * vec = llama_get_embeddings_seq(ctx, 0);
        if (vec == nullptr) {
            // Pooling NONE, or a model that is not an embedding model at all.
            __android_log_print(ANDROID_LOG_ERROR, "LocalLlama", "the model returned no pooled embedding");
            ok = false;
            break;
        }
        double norm = 0.0;
        for (size_t d = 0; d < dim; ++d) {
            norm += static_cast<double>(vec[d]) * static_cast<double>(vec[d]);
        }
        const float inv = norm > 0.0 ? static_cast<float>(1.0 / std::sqrt(norm)) : 0.0f;
        for (size_t d = 0; d < dim; ++d) {
            flat.push_back(vec[d] * inv);
        }
    }

    llama_batch_free(batch);
    if (!ok) {
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (out != nullptr && !flat.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    }
    return out;
}

}  // extern "C"
