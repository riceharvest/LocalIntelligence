// llama.cpp JNI surface for LocalIntelligence.
//
// C++ side of dev.localintelligence.android.inference.LlamaBridge. One object, one model,
// one context at a time: a phone holds one model resident, so a second load
// unloads the first (docs/architecture.md section 16).
//
// Cancellation is a std::atomic<bool> on the handle, checked once per token in
// the sampling loop. A single-token decode on a phone is single-digit
// milliseconds for a 3B model, so checking between tokens is prompt enough;
// checking inside llama_decode would mean patching llama.cpp.

#pragma once

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

// Why a generation stopped. Mirrors dev.localintelligence.core.model.StopReason.
enum class StopCode : int32_t {
    COMPLETED = 0,
    MAX_TOKENS = 1,
    CANCELLED = 2,
    ERROR = 3,
};

// The result of the most recent generation, stashed on the handle because JNI
// cannot return a struct by value.
struct GenOutcome {
    std::string text;
    int32_t prompt_tokens = 0;
    int32_t completion_tokens = 0;
    int32_t stop = static_cast<int32_t>(StopCode::ERROR);
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    // Non-empty when generation failed; the message is the reason.
    std::string error;
};

// Opaque to Java: the Kotlin side only ever holds the pointer as a Long.
struct LlamaHandle {
    // `state` guards every field marked below it. Held only for short state
    // transitions, never across a decode, so cancel() can always take it.
    std::mutex state;

    void * model = nullptr;    // llama_model *
    void * context = nullptr;  // llama_context *
    void * vocab = nullptr;    // const llama_vocab *

    int32_t n_ctx = 0;
    int32_t n_batch = 0;
    int32_t n_vocab = 0;
    int32_t eog_token = -1;
    bool loaded = false;
    // libmtmd context for the loaded model's projector (mtmd_context *), or null.
    void * mtmd = nullptr;
    int32_t n_threads = 0;
    // Encoded images/audio (jpg, png, wav, mp3...) for the next generate only.
    std::vector<std::vector<uint8_t>> pending_media;
    bool backend_initialised = false;

    // The SAF descriptor's /proc/self/fd path is valid only while the
    // ParcelFileDescriptor is open; the Kotlin layer owns that lifetime.
    std::string path;

    // Lock-free: read by the decode loop on its own thread while another
    // thread sets it from cancel().
    std::atomic<bool> cancelled{false};
    std::atomic<bool> generating{false};

    // `outcome_mutex` guards `outcome` only. A generation writes it before
    // returning, and the caller reads it after; the mutex makes that handoff
    // explicit rather than relying on the JNI call boundary alone.
    std::mutex outcome_mutex;
    GenOutcome outcome;
};

extern "C" {

// ---- lifecycle ----------------------------------------------------------------

// Creates the handle. Returns 0 on allocation failure.
JNIEXPORT jlong JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCreate(JNIEnv * env, jclass);

// Frees the model, context and the handle. Safe on 0. Waits for any in-flight
// generation on this handle before freeing.
JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeDestroy(JNIEnv * env, jclass, jlong handle);

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSystemInfo(JNIEnv * env, jclass);

// ---- model -------------------------------------------------------------------

// Loads `path` with the given context length. Returns null on success, else a
// non-empty error string. `n_threads` 0 means "let llama.cpp decide".
JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jclass, jlong handle, jstring path, jint n_ctx, jint n_threads,
        jboolean use_mmap);

// Unloads. Frees native memory; the handle stays valid and can be reloaded.
JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeUnload(JNIEnv * env, jclass, jlong handle);

// Trained context length from the model, for the capability check. 0 if none.
JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeModelContextLength(JNIEnv * env, jclass, jlong handle);

// ---- tokenisation -------------------------------------------------------------

// Exact token count via llama_tokenize. -1 if no model is loaded.
JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCountTokens(
        JNIEnv * env, jclass, jlong handle, jstring text);

// ---- generation ---------------------------------------------------------------

// Generates, streaming each decoded piece to `onToken` (a
// dev.localintelligence.android.inference.LlamaBridge.TokenSink) as it is produced.
//
// Returns null on success, or an error string. The result is read back through
// nativeTakeResult after this returns.
//
// `parse_special` true tokenises control-token text in the prompt as the special
// token it names (a chat template's own markers); false keeps it as characters
// (the flat transcript, where user text must not be able to forge a marker).
JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring prompt, jstring grammar, jfloat temperature,
        jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty, jint penalty_last_n,
        jfloat frequency_penalty, jfloat presence_penalty, jint seed, jint max_tokens,
        jobject onToken, jboolean parse_special);

// Renders the messages with the loaded model's own chat template
// (llama_model_chat_template + llama_chat_apply_template). `roles` and
// `contents` are parallel arrays. Returns null when the model has no template,
// llama.cpp does not recognise it, or nothing is loaded. Control-token text in
// the contents is broken before rendering, so only the template emits them.
JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeApplyTemplate(
        JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean add_assistant);

// Moves the last outcome off the handle, serialised as a single string.
JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeTakeResult(JNIEnv * env, jclass, jlong handle);

// Cooperative cancellation. Safe when idle.
JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeCancel(JNIEnv * env, jclass, jlong handle);

// True while a generation is in flight on another thread.
JNIEXPORT jboolean JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeIsGenerating(JNIEnv * env, jclass, jlong handle);

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeLoadProjector(
        JNIEnv * env, jclass, jlong handle, jstring path);

JNIEXPORT jint JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeProjectorCaps(JNIEnv * env, jclass, jlong handle);

JNIEXPORT void JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeSetMedia(
        JNIEnv * env, jclass, jlong handle, jobjectArray media);

JNIEXPORT jstring JNICALL
Java_dev_localintelligence_android_inference_LlamaBridge_nativeMediaMarker(JNIEnv * env, jclass);

}  // extern "C"
