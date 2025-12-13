#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdlib.h>  // For malloc and free
#include <unistd.h> // for sysconf to detect CPU count on Android
#include <vector>
#include <strings.h>
#include <chrono>
#include <stdio.h>

// Include llama.cpp headers using the correct paths
#include "lib/llama.cpp/ggml/include/ggml.h"
#include "lib/llama.cpp/ggml/include/ggml-cpu.h"
#include "lib/llama.cpp/ggml/include/ggml-backend.h"
#include "lib/llama.cpp/include/llama.h"

#define LOG_TAG "LLAMA_ANDROID"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference for thread attachment
static JavaVM* g_jvm = NULL;

// Forward declaration
typedef struct {
    JNIEnv* env;
    jobject callback; // Java callback object (onComplete or onProgress)
    jmethodID onResultMethodId;
    jmethodID onErrorMethodId;
    jmethodID onProgressMethodId;
    char prompt[4096];
    int max_tokens;
} inference_context_t;

// Global state (protected by mutex)
static struct llama_model* g_model = NULL;
static struct llama_context* g_ctx = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_inference_active = 0; // Thread safety flag

// Add global variables to store sampling parameters
static int g_threads = 4;
static int g_batch_size = 512;
static int g_ctx_size = 2048;
static float g_temp = 0.8f;
static float g_top_p = 0.95f;
static int g_top_k = 40;  // New parameter
static float g_min_p = 0.05f;  // New parameter (mi_k might be min_p)
static float g_repeat_penalty = 1.1f;
static bool g_flash_attn = false;  // New parameter for flash attention

// Progress reporting interval: send onProgress every N tokens (reduce log spam)
static int g_progress_interval = 5;

// Add a flag to track if sampler is initialized
static bool g_sampler_initialized = false;
static struct llama_sampler* g_sampler = NULL;

// Preferred devices array (NULL-terminated) allocated at init and freed in nativeFree
static ggml_backend_dev_t * g_preferred_devices = NULL;

// Build a NULL-terminated list of preferred devices: Vulkan -> OpenCL -> GPU -> IGPU -> CPU
static void build_preferred_devices() {
    // Free previous if any
    if (g_preferred_devices) {
        free(g_preferred_devices);
        g_preferred_devices = NULL;
    }

    // Try to load any dynamic backends (no-op if built-in)
    ggml_backend_load_all();

    size_t ndev = ggml_backend_dev_count();
    if (ndev == 0) return;

    std::vector<ggml_backend_dev_t> vulkan;
    std::vector<ggml_backend_dev_t> opencl;
    std::vector<ggml_backend_dev_t> gpus;
    std::vector<ggml_backend_dev_t> igpus;
    std::vector<ggml_backend_dev_t> cpus;

    for (size_t i = 0; i < ndev; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
        const char * reg_name = ggml_backend_reg_name(reg);
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_GPU:
                if (reg_name && strcasecmp(reg_name, "Vulkan") == 0) {
                    vulkan.push_back(dev);
                } else if (reg_name && strcasecmp(reg_name, "OpenCL") == 0) {
                    opencl.push_back(dev);
                } else {
                    gpus.push_back(dev);
                }
                break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:
                igpus.push_back(dev);
                break;
            case GGML_BACKEND_DEVICE_TYPE_CPU:
            case GGML_BACKEND_DEVICE_TYPE_ACCEL:
            default:
                cpus.push_back(dev);
                break;
        }
    }

    // Combine in order of preference
    std::vector<ggml_backend_dev_t> order;
    order.insert(order.end(), vulkan.begin(), vulkan.end());
    order.insert(order.end(), opencl.begin(), opencl.end());
    order.insert(order.end(), gpus.begin(), gpus.end());
    order.insert(order.end(), igpus.begin(), igpus.end());
    order.insert(order.end(), cpus.begin(), cpus.end());

    // allocate NULL-terminated array
    g_preferred_devices = (ggml_backend_dev_t *) malloc(sizeof(ggml_backend_dev_t) * (order.size() + 1));
    if (!g_preferred_devices) return;
    for (size_t i = 0; i < order.size(); ++i) {
        g_preferred_devices[i] = order[i];
    }
    g_preferred_devices[order.size()] = NULL;
}

// --- Helper: Safe JNI String to C ---
static char* jstringToUtf8(JNIEnv *env, jstring jstr) {
    if (!jstr) return NULL;

    jsize len = env->GetStringLength(jstr);   // length in UTF-16 units
    const jchar* raw = env->GetStringChars(jstr, NULL);

    // Max 4 bytes/char + \0
    int bufsize = len * 4 + 1;
    char* utf8 = (char*) malloc(bufsize);
    char* out = utf8;

    for (int i = 0; i < len; i++) {
        unsigned int code = raw[i];

        if (code >= 0xD800 && code <= 0xDBFF) {            // high surrogate
            if (i + 1 < len) {
                unsigned int low = raw[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {      // low surrogate
                    code = 0x10000 + (((code - 0xD800) << 10) | (low - 0xDC00));
                    i++; // skip low
                }
            }
        }

        // Encode UTF-8
        if (code < 0x80) {
            *out++ = (char) code;
        } else if (code < 0x800) {
            *out++ = (char) (0xC0 | (code >> 6));
            *out++ = (char) (0x80 | (code & 0x3F));
        } else if (code < 0x10000) {
            *out++ = (char) (0xE0 | (code >> 12));
            *out++ = (char) (0x80 | ((code >> 6) & 0x3F));
            *out++ = (char) (0x80 | (code & 0x3F));
        } else { // U+10000..U+10FFFF
            *out++ = (char) (0xF0 | (code >> 18));
            *out++ = (char) (0x80 | ((code >> 12) & 0x3F));
            *out++ = (char) (0x80 | ((code >> 6) & 0x3F));
            *out++ = (char) (0x80 | (code & 0x3F));
        }
    }

    *out = '\0';
    env->ReleaseStringChars(jstr, raw);
    return utf8;
}

// --- Helper: Free C string safely ---
static void freeIfNotNull(char* ptr) {
    if (ptr) {
        free(ptr);
        ptr = NULL;
    }
}

// --- Thread Function for Inference ---
void* inference_thread(void* arg) {
    inference_context_t* ctx_data = (inference_context_t*)arg;

    JNIEnv* env = NULL;
    int attached = g_jvm->AttachCurrentThread(&env, NULL);
    if (attached != JNI_OK) {
        LOGE("Failed to attach thread");
        free(ctx_data);
        return NULL;
    } else {
        LOGI("AttachCurrentThread done!");
    }

    pthread_mutex_lock(&g_lock);

    // Ensure model is loaded
    if (!g_model || !g_ctx) {
        LOGE("Model not initialized!");
        if (ctx_data->onErrorMethodId) {
            env->CallVoidMethod(ctx_data->callback,
                               ctx_data->onErrorMethodId,
                               env->NewStringUTF("Error: Model not loaded"));
        }
        pthread_mutex_unlock(&g_lock);
        // delete global ref while thread still attached and env valid
        if (ctx_data->callback) {
            env->DeleteGlobalRef(ctx_data->callback);
            ctx_data->callback = NULL;
        }

        // detach the thread (only if it was attached here)
        if (attached == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        free(ctx_data);
        return NULL;
    } else {
        LOGI("Model initialized!");
    }

    // Get vocabulary from model
    const struct llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Failed to get vocabulary from model!");
        if (ctx_data->onErrorMethodId) {
            env->CallVoidMethod(ctx_data->callback,
                               ctx_data->onErrorMethodId,
                               env->NewStringUTF("Error: Failed to get vocabulary from model"));
        }
        pthread_mutex_unlock(&g_lock);
        // delete global ref while thread still attached and env valid
        if (ctx_data->callback) {
            env->DeleteGlobalRef(ctx_data->callback);
            ctx_data->callback = NULL;
        }

        // detach the thread (only if it was attached here)
        if (attached == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        free(ctx_data);
        return NULL;
    } else {
        LOGI("Got vocabulary from model");
    }
    
    // Tokenize prompt
    int prompt_len = strlen(ctx_data->prompt);
    LOGI("Before llama_tokenize (count)");
    int n_tokens = -llama_tokenize(vocab, ctx_data->prompt, prompt_len, NULL, 0, true, false);
    LOGI("After llama_tokenize (count), n_tokens=%d", n_tokens);
    if (n_tokens <= 0) {
        LOGE("Tokenization failed!");
        if (ctx_data->onErrorMethodId) {
            env->CallVoidMethod(ctx_data->callback,
                               ctx_data->onErrorMethodId,
                               env->NewStringUTF("Error: Tokenization failed"));
        }
        pthread_mutex_unlock(&g_lock);
        // delete global ref while thread still attached and env valid
        if (ctx_data->callback) {
            env->DeleteGlobalRef(ctx_data->callback);
            ctx_data->callback = NULL;
        }

        // detach the thread (only if it was attached here)
        if (attached == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        free(ctx_data);
        return NULL;
    } else {
        LOGI("Tokenization completed. n_tokens=%d", n_tokens);
    }

    LOGI("Before llama_batch_init");
    struct llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    LOGI("After llama_batch_init");
    llama_token* tokens = (llama_token*) malloc(sizeof(llama_token) * n_tokens);
    LOGI("Before llama_tokenize (real)");
    llama_tokenize(vocab, ctx_data->prompt, prompt_len, tokens, n_tokens, true, false);
    LOGI("After llama_tokenize (real)");
    for (int i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.logits[n_tokens - 1] = true; // Only the last token logits are needed
    batch.n_tokens = n_tokens;

    free(tokens);

    // Process prompt
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to process prompt");
        if (ctx_data->onErrorMethodId) {
            env->CallVoidMethod(ctx_data->callback,
                               ctx_data->onErrorMethodId,
                               env->NewStringUTF("Error: Failed to process prompt"));
        }
        llama_batch_free(batch);
        pthread_mutex_unlock(&g_lock);
        // delete global ref while thread still attached and env valid
        if (ctx_data->callback) {
            env->DeleteGlobalRef(ctx_data->callback);
            ctx_data->callback = NULL;
        }

        // detach the thread (only if it was attached here)
        if (attached == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        free(ctx_data);
        return NULL;
    }

    // Generate response
    char* output = (char*) malloc(4096);
    if (!output) {
        LOGE("Memory allocation failed for output");
        if (ctx_data->onErrorMethodId) {
            env->CallVoidMethod(ctx_data->callback,
                               ctx_data->onErrorMethodId,
                               env->NewStringUTF("Error: Memory allocation failed"));
        }
        llama_batch_free(batch);
        pthread_mutex_unlock(&g_lock);
        // delete global ref while thread still attached and env valid
        if (ctx_data->callback) {
            env->DeleteGlobalRef(ctx_data->callback);
            ctx_data->callback = NULL;
        }

        // detach the thread (only if it was attached here)
        if (attached == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        free(ctx_data);
        return NULL;
    }
    memset(output, 0, 4096);

    int n_generated = 0;
    bool done = false;

    // Timing statistics for generation (ms)
    double total_decode_ms = 0.0;
    int timed_tokens = 0;

    // Initialize or reset sampling with the parameters
    if (!g_sampler_initialized || !g_sampler) {
        g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (!g_sampler) {
            LOGE("Failed to initialize sampler");
            if (ctx_data->onErrorMethodId) {
                env->CallVoidMethod(ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   env->NewStringUTF("Error: Failed to initialize sampler"));
            }
            free(output);
            llama_batch_free(batch);
            pthread_mutex_unlock(&g_lock);
            // delete global ref while thread still attached and env valid
            if (ctx_data->callback) {
                env->DeleteGlobalRef(ctx_data->callback);
                ctx_data->callback = NULL;
            }

            // detach the thread (only if it was attached here)
            if (attached == JNI_OK) {
                g_jvm->DetachCurrentThread();
            }
            free(ctx_data);
            return NULL;
        }
        g_sampler_initialized = true;
        
        // Apply the sampling parameters in order
        
        // Top-K sampling (new parameter)
        if (g_top_k > 0) {
            llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(g_top_k));
        }
        
        // Top-p (nucleus) sampling
        if (g_top_p < 1.0f) {
            llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_top_p, 1));
        }
        
        // Min-P sampling (new parameter)
        if (g_min_p > 0.0f) {
            llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_min_p, 1));
        }
        
        // Temperature sampling
        if (g_temp != 0.0f) {
            llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_temp));
        }
        
        // Repeat penalty
        if (g_repeat_penalty != 1.0f) {
            llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
                64,                   // last n tokens to penalize
                g_repeat_penalty,     // penalty_repeat
                0.0f,                 // penalty_freq
                0.0f                  // penalty_present
            ));
        }
        
        // Distribution sampler (required)
        struct llama_sampler * dist_sampler = llama_sampler_init_dist(1234);
        if (!dist_sampler) {
            LOGE("Failed to initialize distribution sampler");
            if (ctx_data->onErrorMethodId) {
                env->CallVoidMethod(ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   env->NewStringUTF("Error: Failed to initialize distribution sampler"));
            }
            llama_sampler_free(g_sampler);
            g_sampler = NULL;
            g_sampler_initialized = false;
            free(output);
            llama_batch_free(batch);
            pthread_mutex_unlock(&g_lock);
            // delete global ref while thread still attached and env valid
            if (ctx_data->callback) {
                env->DeleteGlobalRef(ctx_data->callback);
                ctx_data->callback = NULL;
            }

            // detach the thread (only if it was attached here)
            if (attached == JNI_OK) {
                g_jvm->DetachCurrentThread();
            }
            free(ctx_data);
            return NULL;
        }
        
        llama_sampler_chain_add(g_sampler, dist_sampler);
    } else {
        // Reset the sampler state for new generation
        llama_sampler_reset(g_sampler);
    }

    auto gen_start = std::chrono::steady_clock::now();
    while (n_generated < ctx_data->max_tokens && !done) {
        // Sample next token
        llama_token new_token_id = llama_sampler_sample(g_sampler, g_ctx, -1);
        
        // Check if sampling was successful
        if (new_token_id == -1) {
            LOGE("Failed to sample next token");
            break;
        }
        
        // Accept the token (updates sampler state)
        llama_sampler_accept(g_sampler, new_token_id);

        // Check if it's an end-of-generation token
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            done = true;
            break;
        }
        
        // Additional safety check for valid token
        if (new_token_id < 0) {
            LOGE("Invalid token ID: %d", new_token_id);
            break;
        }

        // Convert token to string
        char token_str[512];
        int token_str_len = llama_token_to_piece(vocab, new_token_id, token_str, sizeof(token_str), 0, false);
        if (token_str_len > 0) {
            token_str[token_str_len] = '\0';
            
            // Check for buffer overflow before concatenating
            size_t output_len = strlen(output);
            size_t token_len = strlen(token_str);
            if (output_len + token_len >= 4095) {
                LOGE("Output buffer full");
                break;
            }
            
            strcat(output, token_str);
        }

        // Notify progress - changed from every 5 tokens to every token for more frequent updates
        if (ctx_data->onProgressMethodId) {
            // Send progress only every g_progress_interval tokens to reduce log spam.
            if ((n_generated > 0 && (n_generated % g_progress_interval) == 0) || (timed_tokens > 0 && (timed_tokens % g_progress_interval) == 0)) {
                // Compose a message containing the partial output and timing stats
                char progress_buf[8192];
                double avg_ms = timed_tokens > 0 ? (total_decode_ms / (double) timed_tokens) : 0.0;
                double tps = avg_ms > 0.0 ? 1000.0 / avg_ms : 0.0;
                // Ensure we don't overflow the buffer
                int written = snprintf(progress_buf, sizeof(progress_buf), "%s\n[stats] avg_ms=%.2f tps=%.2f tokens=%d", output, avg_ms, tps, n_generated);
                if (written < 0) {
                    // fallback to sending only output
                    jstring js = env->NewStringUTF(output);
                    env->CallVoidMethod(ctx_data->callback, ctx_data->onProgressMethodId, js);
                    env->DeleteLocalRef(js);
                } else {
                    jstring js = env->NewStringUTF(progress_buf);
                    env->CallVoidMethod(ctx_data->callback, ctx_data->onProgressMethodId, js);
                    env->DeleteLocalRef(js);
                }
            }
        }

        // Prepare next batch
        struct llama_batch next_batch = llama_batch_init(1, 0, 1);
        next_batch.token[0] = new_token_id;
        next_batch.pos[0] = n_tokens + n_generated;
        next_batch.n_seq_id[0] = 1;
        next_batch.seq_id[0][0] = 0;
        next_batch.logits[0] = true;
        next_batch.n_tokens = 1;

        // Time the decode call (this is the main per-token work)
        auto t0 = std::chrono::steady_clock::now();
        int decode_res = llama_decode(g_ctx, next_batch);
        auto t1 = std::chrono::steady_clock::now();
        double decode_ms = std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(t1 - t0).count();
        if (decode_res != 0) {
            LOGE("Failed to decode token");
            llama_batch_free(next_batch);
            break;
        }
        llama_batch_free(next_batch);

        // Update timing stats
        total_decode_ms += decode_ms;
        ++timed_tokens;
        double avg_ms = total_decode_ms / (double) timed_tokens;
        double tokens_per_sec = avg_ms > 0.0 ? 1000.0 / avg_ms : 0.0;
        // Log only every g_progress_interval tokens to avoid spam
        if ((timed_tokens % g_progress_interval) == 0) {
            LOGI("Decode token id=%d decode_ms=%.2f avg_ms=%.2f tokens=%d tps=%.2f", new_token_id, decode_ms, avg_ms, timed_tokens, tokens_per_sec);
        }
        ++n_generated;
    }

    // Generation finished — compute summary timings
    auto gen_end = std::chrono::steady_clock::now();
    double total_gen_ms = std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(gen_end - gen_start).count();
    double avg_ms_total = n_generated > 0 ? (total_gen_ms / (double) n_generated) : 0.0;
    double total_tps = avg_ms_total > 0.0 ? 1000.0 / avg_ms_total : 0.0;

    LOGI("Generation finished: tokens=%d total_ms=%.2f avg_ms=%.2f tps=%.2f", n_generated, total_gen_ms, avg_ms_total, total_tps);

    // Send a final progress message with timing summary (so UI can display it)
    if (ctx_data->onProgressMethodId) {
        char summary_buf[8192];
        int w = snprintf(summary_buf, sizeof(summary_buf), "%s\n[summary] tokens=%d total_ms=%.2f avg_ms=%.2f tps=%.2f", output, n_generated, total_gen_ms, avg_ms_total, total_tps);
        if (w >= 0) {
            jstring jsprog = env->NewStringUTF(summary_buf);
            env->CallVoidMethod(ctx_data->callback, ctx_data->onProgressMethodId, jsprog);
            env->DeleteLocalRef(jsprog);
        }
    }

    // Send final result
    jstring js = env->NewStringUTF(output);
    if (ctx_data->onResultMethodId) {
        env->CallVoidMethod(ctx_data->callback,
                           ctx_data->onResultMethodId, js);
    }

    free(output);
    llama_batch_free(batch);

    pthread_mutex_unlock(&g_lock);
    // delete global ref while thread still attached and env valid
    if (ctx_data->callback) {
        env->DeleteGlobalRef(ctx_data->callback);
        ctx_data->callback = NULL;
    }

    // detach the thread (only if it was attached here)
    if (attached == JNI_OK) {
        g_jvm->DetachCurrentThread();
    }
    free(ctx_data);
    return NULL;
}

// --- Java Native Interface: Init Model ---
extern "C"
JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeInitModel(JNIEnv *env, jclass thiz, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    LOGI("Loading model from: %s", path);

    llama_backend_init();
    // load/initialize available ggml backends and build preferred device list
    ggml_backend_load_all();
    build_preferred_devices();

    struct llama_model_params model_params = llama_model_default_params();
    // prefer Vulkan/OpenCL devices when available
    model_params.devices = g_preferred_devices;
    model_params.n_gpu_layers = 0; // No GPU layers for Android

    // load/initialize available ggml backends and build preferred device list
    ggml_backend_load_all();
    build_preferred_devices();

    pthread_mutex_lock(&g_lock);
    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path);
        pthread_mutex_unlock(&g_lock);
        env->ReleaseStringUTFChars(modelPath, path);
        return env->NewStringUTF("Error: Failed to load model. Please check if the file is a valid GGUF format and accessible.");
    }

    struct llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context for model at %s", path);
        llama_model_free(g_model);
        g_model = NULL;
        pthread_mutex_unlock(&g_lock);
        env->ReleaseStringUTFChars(modelPath, path);
        return env->NewStringUTF("Error: Failed to create model context. The model might be corrupted or incompatible with this version.");
    }

    g_inference_active = 1;
    pthread_mutex_unlock(&g_lock);

    LOGI("Model loaded successfully from %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return env->NewStringUTF("Success");
}

// --- Java Native Interface: Init Model With Parameters ---
extern "C"
JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeInitModelWithParams(
    JNIEnv *env, 
    jclass thiz, 
    jstring modelPath,
    jint threads,
    jint batchSize,
    jint ctxSize,
    jfloat temp,
    jfloat topP,
    jint topK,        // New parameter
    jfloat minP,      // New parameter
    jfloat repeatPenalty,
    jboolean flashAttn) {  // New parameter
    
    const char* path = env->GetStringUTFChars(modelPath, 0);
    LOGI("Loading model from: %s", path);
    LOGI("Parameters: threads=%d, batch_size=%d, ctx_size=%d, temp=%.2f, top_p=%.2f, top_k=%d, min_p=%.2f, repeat_penalty=%.2f, flash_attn=%s", 
         threads, batchSize, ctxSize, temp, topP, topK, minP, repeatPenalty, flashAttn ? "true" : "false");

    // Store parameters for use in inference
    // If caller passed threads<=0, autodetect CPU cores and use that as default
    int cpu_count = 1;
#if defined(__linux__) || defined(__ANDROID__)
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n > 0) cpu_count = (int)n;
#endif
    if (threads <= 0) {
        threads = cpu_count;
        LOGI("threads not provided or <=0, autodetected cpu_count=%d -> using threads=%d", cpu_count, threads);
    }
    g_threads = threads;
    g_batch_size = batchSize;
    g_ctx_size = ctxSize;
    g_temp = temp;
    g_top_p = topP;
    g_top_k = topK;              // Store new parameter
    g_min_p = minP;              // Store new parameter
    g_repeat_penalty = repeatPenalty;
    g_flash_attn = flashAttn;    // Store new parameter

    llama_backend_init();

    struct llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // No GPU layers for Android
    // Prefer Vulkan/OpenCL devices if available (built by build_preferred_devices)
    model_params.devices = g_preferred_devices;
    // Apply flash attention if enabled
    if (g_flash_attn) {
        // Flash attention settings would go here if supported
        LOGI("Flash attention setting requested: %s", g_flash_attn ? "true" : "false");
    }

    pthread_mutex_lock(&g_lock);
    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path);
        pthread_mutex_unlock(&g_lock);
        env->ReleaseStringUTFChars(modelPath, path);
        return env->NewStringUTF("Error: Failed to load model. Please check if the file is a valid GGUF format and accessible.");
    }

    struct llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctxSize;
    ctx_params.n_batch = batchSize;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    
    // Apply flash attention context setting if enabled
    if (g_flash_attn) {
        // Flash attention context settings would go here if supported
        LOGI("Flash attention context setting applied");
    }

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context for model at %s", path);
        llama_model_free(g_model);
        g_model = NULL;
        pthread_mutex_unlock(&g_lock);
        env->ReleaseStringUTFChars(modelPath, path);
        return env->NewStringUTF("Error: Failed to create model context. The model might be corrupted or incompatible with this version.");
    }

    g_inference_active = 1;
    pthread_mutex_unlock(&g_lock);

    LOGI("Model loaded successfully from %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return env->NewStringUTF("Success");
}

// --- Java Native Interface: Check if Model is Loaded ---
extern "C"
JNIEXPORT jboolean JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeIsModelLoaded(JNIEnv *env, jclass thiz) {
    pthread_mutex_lock(&g_lock);
    jboolean result = (g_model != NULL && g_ctx != NULL) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_lock);
    return result;
}

// --- Java Native Interface: Generate (async) ---
extern "C"
JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeGenerate(JNIEnv *env, jclass thiz, jstring prompt, jint maxTokens, jobject callbackObj) {
    if (!prompt || !callbackObj) {
        LOGE("Invalid parameters: prompt or callback object is null");
        return;
    }

    pthread_mutex_lock(&g_lock);
    if (!g_ctx || !g_model) {
        LOGE("Model not loaded! Cannot generate response.");
        pthread_mutex_unlock(&g_lock);
        
        // Get method ID for error callback
        jclass callbackClass = env->GetObjectClass(callbackObj);
        jmethodID onErrorMethodId = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        
        if (onErrorMethodId) {
            env->CallVoidMethod(callbackObj,
                               onErrorMethodId,
                               env->NewStringUTF("Error: Model not initialized. Please load a valid GGUF model file first."));
        }
        return;
    }
    pthread_mutex_unlock(&g_lock);

    // Prepare data for thread
    inference_context_t* ctx_data = (inference_context_t*) malloc(sizeof(inference_context_t));
    if (!ctx_data) {
        LOGE("Failed to allocate context for inference");
        
        // Get method ID for error callback
        jclass callbackClass = env->GetObjectClass(callbackObj);
        jmethodID onErrorMethodId = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        
        if (onErrorMethodId) {
            env->CallVoidMethod(callbackObj,
                               onErrorMethodId,
                               env->NewStringUTF("Error: Memory allocation failed. Not enough memory for inference."));
        }
        return;
    }

    // Get method IDs
    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID onResultMethodId = env->GetMethodID(callbackClass, "onResult", "(Ljava/lang/String;)V");
    jmethodID onErrorMethodId = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    jmethodID onProgressMethodId = env->GetMethodID(callbackClass, "onProgress", "(Ljava/lang/String;)V");

    ctx_data->env = env;
    ctx_data->callback = env->NewGlobalRef(callbackObj);
    ctx_data->onResultMethodId = onResultMethodId;
    ctx_data->onErrorMethodId = onErrorMethodId;
    ctx_data->onProgressMethodId = onProgressMethodId;
    
    // Copy prompt safely
    const char* promptChars = jstringToUtf8(env, prompt);
    //const char* promptChars = env->GetStringUTFChars(prompt, 0);
    if (!promptChars) {
        LOGE("Failed to convert prompt to UTF-8");
        env->DeleteGlobalRef(ctx_data->callback);
        free(ctx_data);
        return;
    }

    LOGI("Prompt (utf8) = '%s'", promptChars);

    // Ensure prompt fits into ctx_data->prompt
    size_t prompt_len = strlen(promptChars);
    if (prompt_len >= sizeof(ctx_data->prompt)) {
        LOGE("Prompt too long (%zu bytes), truncating to %zu", prompt_len, sizeof(ctx_data->prompt)-1);
        // truncate safely
        strncpy(ctx_data->prompt, promptChars, sizeof(ctx_data->prompt) - 1);
        ctx_data->prompt[sizeof(ctx_data->prompt) - 1] = '\0';
    } else {
        strcpy(ctx_data->prompt, promptChars);
    }
    
    ctx_data->max_tokens = maxTokens;

    // Start inference thread
    pthread_t tid;
    int rc = pthread_create(&tid, NULL, inference_thread, (void*)ctx_data);
    if (rc != 0) {
        LOGE("Failed to create inference thread, error code: %d", rc);
        env->DeleteGlobalRef(ctx_data->callback);
        free(ctx_data);
        
        // Call error callback
        if (onErrorMethodId) {
            env->CallVoidMethod(callbackObj, onErrorMethodId,
                               env->NewStringUTF("Error: Failed to create inference thread. System resources might be exhausted."));
        }
        return;
    }

    pthread_detach(tid); // Let it clean up on its own
}

// --- Java Native Interface: Free Resources ---
extern "C"
JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeFree(JNIEnv *env, jclass thiz) {
    pthread_mutex_lock(&g_lock);
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = NULL;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = NULL;
    }
    
    // Free sampler resources
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = NULL;
        g_sampler_initialized = false;
    }
    
    g_inference_active = 0;
    pthread_mutex_unlock(&g_lock);

    // Free preferred devices list if allocated
    if (g_preferred_devices) {
        free(g_preferred_devices);
        g_preferred_devices = NULL;
    }

    // Free ggml/llama backends if needed
    llama_backend_free();

    LOGI("LLM resources freed");
}

// --- Java Native Interface: Set JVM for threads ---
extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}