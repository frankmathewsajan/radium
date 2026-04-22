#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <android/log.h>

// The Neural Engine Brain
#include "llama.h"

#define LOG_TAG "Radium-Metal"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global pointers for physical RAM allocation
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static llama_context_params g_ctx_params; // Saved configuration for bulletproof reloads

// ============================================================================
// PART 1: THE NEURAL ENGINE (LocalAiBridge.kt)
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_radium_LocalAiBridge_loadModelNative(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Neural Engine: Booting from %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(path, model_params);

    if (g_model == nullptr) {
        LOGE("CRITICAL: Failed to load GGUF model into memory.");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Save the configuration globally so we can instantly respawn the context later
    g_ctx_params = llama_context_default_params();
    g_ctx_params.n_ctx = 4096; // Expanded context window for chat history
    g_ctx_params.n_batch = 2048;
    g_ctx_params.n_threads = 4;

    g_ctx = llama_init_from_model(g_model, g_ctx_params);

    env->ReleaseStringUTFChars(model_path, path);
    LOGI("Neural Engine: Boot Sequence Complete. Ready for Inference.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_radium_LocalAiBridge_generateResponseNative(JNIEnv *env, jobject thiz, jstring prompt) {
    const char *input_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string input(input_cstr);
    env->ReleaseStringUTFChars(prompt, input_cstr);

    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("System Error: Neural Engine Offline.");
    }

    // ====================================================================
    // BULLETPROOF FIX: Destroy and rebuild the context instead of wiping KV
    // ====================================================================
    LOGI("Neural Engine: Rebuilding fresh context for stateless prompt...");
    if (g_ctx) {
        llama_free(g_ctx);
    }
    g_ctx = llama_init_from_model(g_model, g_ctx_params);

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);

    // The prompt is now fully formatted by Kotlin (Context Window)
    std::vector<llama_token> tokens_list(input.length() + 4);
    tokens_list[0] = llama_vocab_bos(vocab);

    int n_tokens = llama_tokenize(vocab, input.c_str(), input.length(), tokens_list.data() + 1, tokens_list.size() - 1, false, true);
    if (n_tokens < 0) {
        return env->NewStringUTF("Error: Tokenization failed.");
    }
    tokens_list.resize(n_tokens + 1);

    // Initialize batch
    llama_batch batch = llama_batch_init(4096, 0, 1);
    batch.n_tokens = tokens_list.size();
    for (size_t i = 0; i < tokens_list.size(); i++) {
        batch.token[i] = tokens_list[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.logits[tokens_list.size() - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("Error: Context evaluation failed.");
    }

    // Generation Loop
    std::string response = "";
    int n_cur = tokens_list.size();
    int max_tokens = 512; // Max words the AI can speak in one reply
    int n_vocab = llama_vocab_n_tokens(vocab);

    while (n_cur <= tokens_list.size() + max_tokens) {
        auto * logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);

        llama_token new_token_id = 0;
        float max_val = -1e9;
        for (int i = 0; i < n_vocab; i++) {
            if (logits[i] > max_val) {
                max_val = logits[i];
                new_token_id = i;
            }
        }

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response += std::string(buf, n);
        }

        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
        n_cur += 1;
    }

    llama_batch_free(batch);
    LOGI("Neural Engine: Generation Complete.");
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_radium_LocalAiBridge_unloadModelNative(JNIEnv *env, jobject thiz) {
    LOGI("Neural Engine: Purging model from physical RAM.");
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}

// ============================================================================
// PART 2: THE RADIO TRANSCEIVER (RadioEngine.kt)
// ============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_radium_RadioEngine_stringFromJNI(JNIEnv *env, jobject thiz) {
    std::string hello = "Radium Metal Plane Initialized";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_radium_RadioEngine_processDataNative(JNIEnv *env, jobject thiz, jstring input) {
    const char *raw_text = env->GetStringUTFChars(input, nullptr);
    std::string text_str(raw_text);

    std::vector<uint8_t> fake_encrypted(text_str.begin(), text_str.end());

    jbyteArray result = env->NewByteArray(fake_encrypted.size());
    env->SetByteArrayRegion(result, 0, fake_encrypted.size(), reinterpret_cast<const jbyte*>(fake_encrypted.data()));

    env->ReleaseStringUTFChars(input, raw_text);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_radium_RadioEngine_decodeDataNative(JNIEnv *env, jobject thiz, jbyteArray input) {
    jsize length = env->GetArrayLength(input);
    jbyte *raw_bytes = env->GetByteArrayElements(input, nullptr);

    std::string decoded(reinterpret_cast<char*>(raw_bytes), length);

    env->ReleaseByteArrayElements(input, raw_bytes, JNI_ABORT);
    return env->NewStringUTF(decoded.c_str());
}