#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <cstdio>
#include <cstring>
#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
static std::mutex g_last_error_mutex;
static std::string g_last_error_message;
static std::mutex g_llama_log_mutex;
static std::string g_llama_log_message;

static constexpr size_t MAX_LAST_ERROR_CHARS = 8192;
static constexpr size_t MAX_LLAMA_LOG_CHARS = 32768;

static std::string sanitize_single_line(const std::string& input, size_t maxChars = 2000) {
    std::string out;
    out.reserve(input.size());
    bool lastWasSpace = false;
    for (char ch : input) {
        char c = ch;
        if (c == '\n' || c == '\r' || c == '\t') c = ' ';
        if (c == ' ') {
            if (lastWasSpace) continue;
            lastWasSpace = true;
        } else {
            lastWasSpace = false;
        }
        out.push_back(c);
    }
    if (out.size() > maxChars) {
        out = out.substr(out.size() - maxChars);
    }
    return out;
}

static std::string hex_bytes(const uint8_t* data, size_t len) {
    std::ostringstream out;
    out << std::hex;
    for (size_t i = 0; i < len; ++i) {
        out << std::setw(2) << std::setfill('0') << static_cast<int>(data[i]);
    }
    return out.str();
}

static std::string errno_text(int value) {
    std::ostringstream out;
    out << value << " (" << strerror(value) << ")";
    return out.str();
}

static void clear_llama_log() {
    std::lock_guard<std::mutex> lock(g_llama_log_mutex);
    g_llama_log_message.clear();
}

static void append_llama_log(const char* text) {
    if (!text || text[0] == '\0') return;
    std::lock_guard<std::mutex> lock(g_llama_log_mutex);
    g_llama_log_message.append(text);
    if (g_llama_log_message.size() > MAX_LLAMA_LOG_CHARS) {
        g_llama_log_message.erase(0, g_llama_log_message.size() - MAX_LLAMA_LOG_CHARS);
    }
}

static std::string get_llama_log() {
    std::lock_guard<std::mutex> lock(g_llama_log_mutex);
    return g_llama_log_message;
}

static void llama_log_callback_bridge(ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    if (!text) return;

    append_llama_log(text);

    const std::string line = sanitize_single_line(text, 800);
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            LOGE("llama: %s", line.c_str());
            break;
        case GGML_LOG_LEVEL_WARN:
            LOGI("llama(warn): %s", line.c_str());
            break;
        default:
            break;
    }
}

static void clear_last_error() {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    g_last_error_message.clear();
}

static void set_last_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    g_last_error_message = sanitize_single_line(message, MAX_LAST_ERROR_CHARS);
    LOGE("%s", g_last_error_message.c_str());
}

static std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    return g_last_error_message;
}

static void append_path_probe(std::ostringstream& details, const std::string& path) {
    struct stat st = {};
    const bool statOk = stat(path.c_str(), &st) == 0;
    if (statOk) {
        details << "; stat_size=" << (long long)st.st_size
                << "; stat_mode=0" << std::oct << st.st_mode << std::dec;
        if (!S_ISREG(st.st_mode)) {
            details << "; stat_note=not_regular_file";
        }
    } else {
        details << "; stat_errno=" << errno_text(errno);
    }

    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        details << "; open_errno=" << errno_text(errno);
        return;
    }

    details << "; open_ok=1";

    uint8_t header[16] = {};
    const ssize_t n = pread(fd, header, sizeof(header), 0);
    if (n < 0) {
        details << "; pread_errno=" << errno_text(errno);
    } else {
        details << "; pread_bytes=" << n;
        if (n > 0) {
            details << "; header_hex=" << hex_bytes(header, static_cast<size_t>(n));
            if (n >= 4) {
                const bool isGguf = std::memcmp(header, "GGUF", 4) == 0;
                details << "; header_magic=" << (isGguf ? "GGUF" : "not_GGUF");
            }
        }
    }

    if (statOk && st.st_size > 0 && S_ISREG(st.st_mode)) {
        const size_t mapLen = std::min<size_t>(4096, static_cast<size_t>(st.st_size));
        void* probe = mmap(nullptr, mapLen, PROT_READ, MAP_PRIVATE, fd, 0);
        if (probe == MAP_FAILED) {
            details << "; mmap_probe_errno=" << errno_text(errno);
        } else {
            details << "; mmap_probe=ok";
            munmap(probe, mapLen);
        }
    }

    close(fd);
}

static void append_fd_probe(std::ostringstream& details, int fd) {
    struct stat st = {};
    const bool statOk = fstat(fd, &st) == 0;
    if (statOk) {
        details << "; fd_stat_size=" << (long long)st.st_size
                << "; fd_stat_mode=0" << std::oct << st.st_mode << std::dec;
    } else {
        details << "; fd_stat_errno=" << errno_text(errno);
    }

    uint8_t header[16] = {};
    const ssize_t n = pread(fd, header, sizeof(header), 0);
    if (n < 0) {
        details << "; fd_pread_errno=" << errno_text(errno);
    } else {
        details << "; fd_pread_bytes=" << n;
        if (n > 0) {
            details << "; fd_header_hex=" << hex_bytes(header, static_cast<size_t>(n));
            if (n >= 4) {
                const bool isGguf = std::memcmp(header, "GGUF", 4) == 0;
                details << "; fd_header_magic=" << (isGguf ? "GGUF" : "not_GGUF");
            }
        }
    }

    if (statOk && st.st_size > 0) {
        const size_t mapLen = std::min<size_t>(4096, static_cast<size_t>(st.st_size));
        void* probe = mmap(nullptr, mapLen, PROT_READ, MAP_PRIVATE, fd, 0);
        if (probe == MAP_FAILED) {
            details << "; fd_mmap_probe_errno=" << errno_text(errno);
        } else {
            details << "; fd_mmap_probe=ok";
            munmap(probe, mapLen);
        }
    }
}

static jlong load_model_with_diagnostics(
        const std::string& source,
        const std::string& path,
        int nCtx,
        int nGpuLayers,
        int fdProbe) {
    LOGI("nativeLoadModel[%s]: opening path: %s", source.c_str(), path.c_str());

    llama_model_params params = llama_model_default_params();
    const bool defaultUseMmap = params.use_mmap;
    params.n_gpu_layers = nGpuLayers;

    clear_llama_log();
    llama_model* model = llama_model_load_from_file(path.c_str(), params);
    const std::string defaultAttemptLog = sanitize_single_line(get_llama_log(), 1800);

    std::string retryNoMmapLog;
    if (!model && defaultUseMmap) {
        LOGI("nativeLoadModel[%s]: retrying with use_mmap=0", source.c_str());
        params.use_mmap = false;
        clear_llama_log();
        model = llama_model_load_from_file(path.c_str(), params);
        retryNoMmapLog = sanitize_single_line(get_llama_log(), 1800);
    }

    if (!model) {
        std::ostringstream details;
        details << "llama_model_load_from_file failed"
                << "; source=" << source
                << "; path=" << path
                << "; n_ctx=" << nCtx
                << "; n_gpu_layers=" << nGpuLayers
                << "; default_use_mmap=" << (defaultUseMmap ? 1 : 0);
        if (!defaultAttemptLog.empty()) {
            details << "; llama_log=" << defaultAttemptLog;
        }
        if (!retryNoMmapLog.empty()) {
            details << "; llama_log_retry_nommap=" << retryNoMmapLog;
        }
        append_path_probe(details, path);
        if (fdProbe >= 0) {
            append_fd_probe(details, fdProbe);
        }
        set_last_error(details.str());
        return 0L;
    }

    clear_last_error();
    LOGI("Model loaded: %p", (void*)model);
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    llama_backend_init();
    llama_log_set(llama_log_callback_bridge, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    llama_backend_free();
}

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeGetLastError(
        JNIEnv* env, jobject) {
    const std::string message = get_last_error();
    if (message.empty()) return nullptr;
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeLoadModel(
        JNIEnv* env, jobject, jstring jPath, jint nCtx, jint nGpuLayers) {

    clear_last_error();
    clear_llama_log();

    if (jPath == nullptr) {
        set_last_error("nativeLoadModel received null path");
        return 0L;
    }

    const char* pathChars = env->GetStringUTFChars(jPath, nullptr);
    if (!pathChars) {
        set_last_error("GetStringUTFChars failed while reading model path");
        return 0L;
    }

    const std::string path(pathChars);
    env->ReleaseStringUTFChars(jPath, pathChars);
    return load_model_with_diagnostics("path", path, nCtx, nGpuLayers, -1);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeLoadModelFromFd(
        JNIEnv*, jobject, jint fd, jint nCtx, jint nGpuLayers) {

    clear_last_error();
    clear_llama_log();

    if (fd < 0) {
        set_last_error("nativeLoadModelFromFd received invalid fd");
        return 0L;
    }

    std::ostringstream fdPath;
    fdPath << "/proc/self/fd/" << fd;
    return load_model_with_diagnostics("fd", fdPath.str(), nCtx, nGpuLayers, fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeFreeModel(
        JNIEnv*, jobject, jlong modelPtr) {
    llama_model_free(reinterpret_cast<llama_model*>(modelPtr));
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_fossylabs_portaserver_llm_LlamaWrapper_nativeNewContext(
        JNIEnv*, jobject, jlong modelPtr, jint nCtx, jint nThreads) {

    clear_last_error();

    if (modelPtr == 0L) {
        set_last_error("llama_new_context_with_model called with null model pointer");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t)nCtx;
    cparams.n_threads = (uint32_t)nThreads;

    llama_context* ctx = llama_init_from_model(
        reinterpret_cast<llama_model*>(modelPtr), cparams);

    if (!ctx) {
        std::ostringstream details;
        details << "llama_new_context_with_model failed"
                << "; model_ptr=" << modelPtr
                << "; n_ctx=" << nCtx
                << "; n_threads=" << nThreads;
        set_last_error(details.str());
        return 0L;
    }

    clear_last_error();
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
    if (!text) {
        return env->NewIntArray(0);
    }
    auto* model = reinterpret_cast<llama_model*>(modelPtr);
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Estimate upper bound
    int n_max = strlen(text) + 256;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(vocab, text, (int)strlen(text),
                           tokens.data(), n_max, addBos, false);

    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text, (int)strlen(text),
                           tokens.data(), -n, addBos, false);
    }
    env->ReleaseStringUTFChars(jText, text);

    if (n < 0) {
        return env->NewIntArray(0);
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
    auto * ctx = reinterpret_cast<llama_context*>(ctxPtr);
    if (!ctx) {
        return;
    }
    llama_memory_t mem = llama_get_memory(ctx);
    if (!mem) {
        return;
    }
    llama_memory_clear(mem, false);
}
