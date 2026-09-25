// llama.cpp JNI implementation. See llama_jni.h for the surface contract.
//
// Written against llama.h at the tag pinned in CMakeLists.txt. Every call into
// llama.cpp here uses a signature read out of that header.

#include "llama_jni.h"

#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>
#include <vector>

namespace {

constexpr int32_t kDefaultContext = 4096;
constexpr int32_t kDefaultBatch = 512;

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

jstring to_jstring(JNIEnv * env, const std::string & s) {
    if (s.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(s.c_str());
}

void free_model_locked(LlamaHandle * h) {
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
}

// Phones are asymmetric (big.LITTLE). Leaving the prime core free matters:
// oversubscribing the little cores costs more than the thread gains, and it
// makes the UI janky while a background decode runs.
int32_t pick_threads(int32_t requested) {
    if (requested > 0) {
        return requested;
    }
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) {
        return 4;
    }
    return static_cast<int32_t>(hw > 4 ? hw - 2 : hw);
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
                keep = need - have;  // hold back the bytes we do have
            }
            break;
        }
        i--;  // a continuation byte; keep walking back toward the lead byte
        if (buf.size() - (i - 1) > 4) {
            break;  // malformed: stop rather than scan the whole buffer
        }
    }
    if (keep > 0 && keep < buf.size()) {
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
bool tokenize(const llama_vocab * vocab, const std::string & text,
              std::vector<llama_token> & out, std::string & err) {
    const int32_t len = static_cast<int32_t>(text.size());
    int32_t needed = llama_tokenize(vocab, text.data(), len, nullptr, 0,
                                    /*add_special=*/true, /*parse_special=*/false);
    if (needed < 0) {
        needed = -needed;  // negative is "buffer too small, this is the size"
    }
    if (needed == 0) {
        out.clear();
        return true;
    }
    out.resize(static_cast<size_t>(needed));
    const int32_t got = llama_tokenize(vocab, text.data(), len, out.data(), needed,
                                       /*add_special=*/true, /*parse_special=*/false);
    if (got < 0) {
        err = "llama_tokenize failed on a " + std::to_string(needed) + " token prompt";
        return false;
    }
    out.resize(static_cast<size_t>(got));
    return true;
}

// Builds the sampling chain. Returns null only if llama.cpp cannot allocate one.
llama_sampler * build_chain(const llama_vocab * vocab, const std::string & grammar_str,
                            float temperature, float top_p, float min_p,
                            float repeat_penalty, uint32_t seed) {
    // The chain has to exist before anything can be added to it: `sparams` is
    // consumed by llama_sampler_chain_init, and llama_sampler_chain_add takes
    // the chain, not the params.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (chain == nullptr) {
        return nullptr;
    }

    // Grammar goes in first, so the candidate set is already constrained before
    // top-p/min-p narrow it. A grammar that fails to compile yields a null
    // sampler, which we drop: unconstrained output is better than no output,
    // and the caller sees a non-parseable action rather than a crash.
    if (!grammar_str.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
        if (g != nullptr) {
            llama_sampler_chain_add(chain, g);
        }
    }
    if (repeat_penalty > 0.0f) {
        llama_sampler_chain_add(
            chain, llama_sampler_init_penalties(/*penalty_last_n=*/64, repeat_penalty, 0.0f, 0.0f));
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

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCreate(JNIEnv *, jclass) {
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
        jboolean use_mmap) {
    LlamaHandle * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return to_jstring(env, "null handle");
    }
    if (path == nullptr) {
        return to_jstring(env, "null model path");
    }
    const char * cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) {
        return to_jstring(env, "could not read the model path");
    }
    const std::string model_path(cpath);
    env->ReleaseStringUTFChars(path, cpath);

    std::lock_guard<std::mutex> lock(h->state);

    // One model resident at a time: loading a second unloads the first.
    free_model_locked(h);

    // llama_backend_init is refcounted internally, so pairing init/free per
    // loaded model is correct and survives a reload.
    llama_backend_init();
    h->backend_initialised = true;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // no GPU backend in this Android build
    mparams.use_mmap = use_mmap == JNI_TRUE;
    // use_mlock would pin ~2 GB for the model's whole life, which on Android
    // makes the process an immediate low-memory-killer target. Leave it off.
    mparams.use_mlock = false;
    mparams.vocab_only = false;

    const int32_t threads = pick_threads(n_threads);

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
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    // Only the last token of each decode needs logits: it is the row we sample
    // the next token from. Everything else would be dead weight.
    cparams.logits_all = false;
    cparams.embeddings = false;
    cparams.flash_attn = true;
    cparams.offload_kqv = false;
    // f16 KV cache, which is what RamEstimate assumes.
    cparams.type_k = GGML_TYPE_F16;
    cparams.type_v = GGML_TYPE_F16;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return to_jstring(env, "could not create a context for " + model_path);
    }

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
    h->loaded = true;
    // A fresh load starts uncancelled.
    h->cancelled.store(false);

    return nullptr;
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
    const char * chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) {
        return -1;
    }
    const std::string s(chars);
    env->ReleaseStringUTFChars(text, chars);

    std::vector<llama_token> tokens;
    std::string err;
    if (!tokenize(as_vocab(h->vocab), s, tokens, err)) {
        return -1;
    }
    return static_cast<jint>(tokens.size());
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
    //   stop|promptTokens|completionTokens|prefillMs|decodeMs|<error>\n<text>
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
    if (!h->outcome.error.empty()) {
        out += "E" + h->outcome.error + '\n';
    }
    out += h->outcome.text;
    h->outcome = GenOutcome();
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring prompt, jstring grammar, jfloat temperature,
        jfloat top_p, jfloat min_p, jfloat repeat_penalty, jint seed, jint max_tokens,
        jobject onToken) {
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
    }

    const char * cprompt = env->GetStringUTFChars(prompt, nullptr);
    if (cprompt == nullptr) {
        return to_jstring(env, "could not read the prompt");
    }
    const std::string prompt_str(cprompt);
    env->ReleaseStringUTFChars(prompt, cprompt);

    std::string grammar_str;
    if (grammar != nullptr) {
        const char * cg = env->GetStringUTFChars(grammar, nullptr);
        if (cg != nullptr) {
            grammar_str.assign(cg);
            env->ReleaseStringUTFChars(grammar, cg);
        }
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

    // ---- tokenise -------------------------------------------------------------
    std::vector<llama_token> tokens;
    std::string tok_err;
    if (!tokenize(vocab, prompt_str, tokens, tok_err)) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = tok_err;
        publish();
        return to_jstring(env, outcome.error);
    }
    if (tokens.empty()) {
        outcome.stop = static_cast<int32_t>(StopCode::COMPLETED);
        publish();
        return nullptr;
    }

    // A prompt that fills the whole window leaves nowhere to put the answer.
    // Truncate from the left, keeping the leading special token so the model
    // still sees a well-formed sequence start.
    const int32_t max_prompt = n_ctx - kGenerationHeadroom;
    if (max_prompt <= 0) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = "the context window of " + std::to_string(n_ctx) + " is too small to generate";
        publish();
        return to_jstring(env, outcome.error);
    }
    if (static_cast<int32_t>(tokens.size()) > max_prompt) {
        std::vector<llama_token> truncated;
        truncated.reserve(static_cast<size_t>(max_prompt));
        if (!llama_vocab_is_eog(vocab, tokens.front())) {
            truncated.push_back(tokens.front());
        }
        const size_t room = static_cast<size_t>(max_prompt) - truncated.size();
        const size_t from = tokens.size() - std::min(room, tokens.size());
        for (size_t i = from; i < tokens.size(); ++i) {
            truncated.push_back(tokens[i]);
        }
        tokens.swap(truncated);
    }
    const int32_t prompt_tokens = static_cast<int32_t>(tokens.size());

    llama_sampler * chain = build_chain(
        vocab, grammar_str, temperature, top_p, min_p, repeat_penalty,
        static_cast<uint32_t>(seed));
    if (chain == nullptr) {
        outcome.stop = static_cast<int32_t>(StopCode::ERROR);
        outcome.error = "could not build the sampler chain";
        publish();
        return to_jstring(env, outcome.error);
    }
    llama_sampler_reset(chain);

    // The prompt and the generated answer live in different sequence slots, so
    // a context compactor can drop the prompt without dropping the answer.
    constexpr llama_seq_id kPromptSeq = 0;
    constexpr llama_seq_id kOutputSeq = 1;

    const int64_t start = now_ms();
    int32_t pos = 0;
    bool failed = false;
    // Trailing bytes of a UTF-8 character split across two tokens.
    std::string partial_utf8;

    // ---- prefill --------------------------------------------------------------
    const int32_t batch_limit = std::max(1, std::min(n_ctx, kDefaultBatch));
    for (int32_t off = 0; off < prompt_tokens; off += batch_limit) {
        if (h->cancelled.load()) {
            outcome.stop = static_cast<int32_t>(StopCode::CANCELLED);
            break;
        }
        const int32_t n = std::min(batch_limit, prompt_tokens - off);
        llama_batch batch = llama_batch_init(n, 0, 1);
        for (int32_t i = 0; i < n; ++i) {
            batch.token[i] = tokens[static_cast<size_t>(off + i)];
            batch.pos[i] = off + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = kPromptSeq;
            // Logits only for the final prompt token: that is the row we sample from.
            batch.logits[i] = (off + i == prompt_tokens - 1) ? 1 : 0;
        }
        const int32_t rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            outcome.stop = static_cast<int32_t>(StopCode::ERROR);
            outcome.error = "llama_decode failed during prefill with " + std::to_string(rc);
            failed = true;
            break;
        }
        pos += n;
    }

    const int64_t after_prefill = now_ms();
    if (!failed) {
        outcome.prefill_ms = after_prefill - start;
    }

    // ---- decode loop ----------------------------------------------------------
    if (!failed && outcome.stop != static_cast<int32_t>(StopCode::CANCELLED)) {
        const int32_t limit = max_tokens > 0 ? max_tokens : 512;
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
                // the values.
                std::vector<llama_token_data> data(static_cast<size_t>(n_vocab));
                for (int32_t v = 0; v < n_vocab; ++v) {
                    data[static_cast<size_t>(v)] = llama_token_data{v, logits[v], 0.0f};
                }
                llama_token_data_array cur;
                cur.data = data.data();
                cur.size = data.size();
                cur.selected = -1;
                cur.sorted = false;
                llama_sampler_apply(chain, &cur);
                if (cur.selected >= 0 &&
                    static_cast<size_t>(cur.selected) < cur.size) {
                    id = cur.data[cur.selected].id;
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

            llama_sampler_accept(chain, id);
            outcome.completion_tokens++;

            // Decode the sampled token so the next one has context.
            llama_batch batch = llama_batch_init(1, 0, 1);
            batch.token[0] = id;
            batch.pos[0] = pos;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = kOutputSeq;
            batch.logits[0] = 1;
            const int32_t rc = llama_decode(ctx, batch);
            llama_batch_free(batch);
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
    publish();

    llama_sampler_free(chain);

    // The accumulated text is authoritative; the token stream was for latency.
    if (!failed && !outcome.error.empty()) {
        return to_jstring(env, outcome.error);
    }
    return nullptr;
}

}  // extern "C"
