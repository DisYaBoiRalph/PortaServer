#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    llama_backend_free();
}

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeLoadModel(
        JNIEnv* env, jobject, jstring jPath, jint nCtx, jint nGpuLayers) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_load_model_from_file(path, params);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) {
        LOGE("Failed to load model from: %s", path);
        return 0L;
    }
    LOGI("Model loaded: %p", (void*)model);
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeFreeModel(
        JNIEnv*, jobject, jlong modelPtr) {
    llama_free_model(reinterpret_cast<llama_model*>(modelPtr));
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeNewContext(
        JNIEnv*, jobject, jlong modelPtr, jint nCtx, jint nThreads) {

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t)nCtx;
    cparams.n_threads = (uint32_t)nThreads;

    llama_context* ctx = llama_new_context_with_model(
        reinterpret_cast<llama_model*>(modelPtr), cparams);

    if (!ctx) {
        LOGE("Failed to create context");
        return 0L;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeFreeContext(
        JNIEnv*, jobject, jlong ctxPtr) {
    llama_free(reinterpret_cast<llama_context*>(ctxPtr));
}

// ---------------------------------------------------------------------------
// Tokenization
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jintArray JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeTokenize(
        JNIEnv* env, jobject, jlong modelPtr, jstring jText, jboolean addBos) {

    const char* text = env->GetStringUTFChars(jText, nullptr);
    auto* model = reinterpret_cast<llama_model*>(modelPtr);
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Estimate upper bound
    int n_max = strlen(text) + 256;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(vocab, text, (int)strlen(text),
                           tokens.data(), n_max, addBos, false);
    env->ReleaseStringUTFChars(jText, text);

    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text, (int)strlen(text),
                           tokens.data(), -n, addBos, false);
    }

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint*>(tokens.data()));
    return result;
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeDecode(
        JNIEnv* env, jobject, jlong ctxPtr, jintArray jTokens, jint nPast) {

    jint len = env->GetArrayLength(jTokens);
    jint* elems = env->GetIntArrayElements(jTokens, nullptr);

    std::vector<llama_token> tokens(len);
    for (int i = 0; i < len; i++) tokens[i] = elems[i];
    env->ReleaseIntArrayElements(jTokens, elems, JNI_ABORT);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)len);
    int ret = llama_decode(reinterpret_cast<llama_context*>(ctxPtr), batch);
    return ret == 0;
}

// ---------------------------------------------------------------------------
// Sampling
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeNewSampler(
        JNIEnv*, jobject, jfloat temperature, jfloat topP, jint seed) {

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t)seed));
    return reinterpret_cast<jlong>(chain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeFreeSampler(
        JNIEnv*, jobject, jlong samplerPtr) {
    llama_sampler_free(reinterpret_cast<llama_sampler*>(samplerPtr));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeSample(
        JNIEnv*, jobject, jlong samplerPtr, jlong ctxPtr) {

    auto* sampler = reinterpret_cast<llama_sampler*>(samplerPtr);
    auto* ctx     = reinterpret_cast<llama_context*>(ctxPtr);
    llama_token token = llama_sampler_sample(sampler, ctx, -1);
    llama_sampler_accept(sampler, token);
    return (jint)token;
}

// ---------------------------------------------------------------------------
// Token utilities
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeTokenToString(
        JNIEnv* env, jobject, jlong modelPtr, jint token) {

    char buf[256] = {};
    const llama_vocab* vocab = llama_model_get_vocab(reinterpret_cast<llama_model*>(modelPtr));
    int n = llama_token_to_piece(vocab,
                                  (llama_token)token, buf, sizeof(buf) - 1, 0, false);
    if (n < 0) return env->NewStringUTF("");
    buf[n] = '\0';
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeEosToken(
        JNIEnv*, jobject, jlong modelPtr) {
    const llama_vocab* vocab = llama_model_get_vocab(reinterpret_cast<llama_model*>(modelPtr));
    return (jint)llama_vocab_eos(vocab);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeNCtx(
        JNIEnv*, jobject, jlong ctxPtr) {
    return (jint)llama_n_ctx(reinterpret_cast<llama_context*>(ctxPtr));
}

// ---------------------------------------------------------------------------
// Chat template
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeApplyChatTemplate(
        JNIEnv* env, jobject, jlong modelPtr,
        jobjectArray jRoles, jobjectArray jContents, jboolean addAssistantTurn) {

    jint n = env->GetArrayLength(jRoles);

    std::vector<llama_chat_message> msgs(n);
    std::vector<jstring> roleRefs(n), contentRefs(n);
    std::vector<const char*> rolePtrs(n), contentPtrs(n);
    for (int i = 0; i < n; i++) {
        roleRefs[i]    = (jstring)env->GetObjectArrayElement(jRoles,    i);
        contentRefs[i] = (jstring)env->GetObjectArrayElement(jContents, i);
        rolePtrs[i]    = env->GetStringUTFChars(roleRefs[i],    nullptr);
        contentPtrs[i] = env->GetStringUTFChars(contentRefs[i], nullptr);
        msgs[i].role    = rolePtrs[i];
        msgs[i].content = contentPtrs[i];
    }

    // First call to get the required buffer size (pass nullptr, 0)
    int32_t size = llama_chat_apply_template(nullptr,
        msgs.data(), (size_t)n, (bool)addAssistantTurn, nullptr, 0);
    std::string out;
    if (size > 0) {
        out.resize(size);
        llama_chat_apply_template(nullptr,
            msgs.data(), (size_t)n, (bool)addAssistantTurn, &out[0], size);
    }

    for (int i = 0; i < n; i++) {
        env->ReleaseStringUTFChars(roleRefs[i],    rolePtrs[i]);
        env->ReleaseStringUTFChars(contentRefs[i], contentPtrs[i]);
    }
    return env->NewStringUTF(out.c_str());
}

// ---------------------------------------------------------------------------
// KV cache management
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeKvCacheClear(
        JNIEnv*, jobject, jlong ctxPtr) {
    llama_kv_self_clear(reinterpret_cast<llama_context*>(ctxPtr));
}
