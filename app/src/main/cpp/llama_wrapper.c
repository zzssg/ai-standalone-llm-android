#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdlib.h>  // For malloc and free

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

// --- Helper: Safe JNI String to C ---
static char* jstringToUtf8(JNIEnv *env, jstring jstr) {
    if (!jstr) return NULL;

    jsize len = (*env)->GetStringLength(env, jstr);   // length in UTF-16 units
    const jchar* raw = (*env)->GetStringChars(env, jstr, NULL);

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
    (*env)->ReleaseStringChars(env, jstr, raw);
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
    int attached = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
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
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Model not loaded"));
        }
        goto unlock_and_cleanup;
    } else {
        LOGI("Model initialized!");
    }

    // Get vocabulary from model
    const struct llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Failed to get vocabulary from model!");
        if (ctx_data->onErrorMethodId) {
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Failed to get vocabulary from model"));
        }
        goto unlock_and_cleanup;
    } else {
        LOGI("Got vocabulary from model");
    }
    
    // Tokenize prompt
    int prompt_len = strlen(ctx_data->prompt);
    LOGI("Before llama_tokenize (count)");
    int n_tokens = llama_tokenize(vocab, ctx_data->prompt, prompt_len, NULL, 0, true, false);
    LOGI("After llama_tokenize (count), n_tokens=%d", n_tokens);
    if (n_tokens <= 0) {
        LOGE("Tokenization failed!");
        if (ctx_data->onErrorMethodId) {
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Tokenization failed"));
        }
        goto unlock_and_cleanup;
    } else {
        LOGI("Tokenization completed. n_tokens=%d", n_tokens);
    }

    LOGI("Before llama_batch_init");
    struct llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    LOGI("After llama_batch_init");
    llama_token* tokens = malloc(sizeof(llama_token) * n_tokens);
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
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Failed to process prompt"));
        }
        goto batch_cleanup;
    }

    // Generate response
    char* output = malloc(4096);
    if (!output) {
        LOGE("Memory allocation failed for output");
        if (ctx_data->onErrorMethodId) {
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Memory allocation failed"));
        }
        goto batch_cleanup;
    }
    memset(output, 0, 4096);

    int n_generated = 0;
    bool done = false;

    // Initialize sampling
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) {
        LOGE("Failed to initialize sampler");
        if (ctx_data->onErrorMethodId) {
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Failed to initialize sampler"));
        }
        goto batch_cleanup;
    }
    struct llama_sampler * dist_sampler = llama_sampler_init_dist(1234);
    if (!dist_sampler) {
        LOGE("Failed to initialize distribution sampler");
        if (ctx_data->onErrorMethodId) {
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Failed to initialize distribution sampler"));
        }
        llama_sampler_free(smpl);
        goto batch_cleanup;
    }
    llama_sampler_chain_add(smpl, dist_sampler);

    while (n_generated < ctx_data->max_tokens && !done) {
        // Sample next token
        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        
        // Check if sampling was successful
        if (new_token_id == -1) {
            LOGE("Failed to sample next token");
            break;
        }
        
        // Accept the token (updates sampler state)
        llama_sampler_accept(smpl, new_token_id);

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
        char token_str[32];
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

        // Notify progress
        if (ctx_data->onProgressMethodId && n_generated % 5 == 0) {
            jstring js = (*env)->NewStringUTF(env, output);
            (*env)->CallVoidMethod(env, ctx_data->callback,
                                   ctx_data->onProgressMethodId, js);
            (*env)->DeleteLocalRef(env, js);
        }

        // Prepare next batch
        struct llama_batch next_batch = llama_batch_init(1, 0, 1);
        next_batch.token[0] = new_token_id;
        next_batch.pos[0] = n_tokens + n_generated;
        next_batch.n_seq_id[0] = 1;
        next_batch.seq_id[0][0] = 0;
        next_batch.logits[0] = true;
        next_batch.n_tokens = 1;

        if (llama_decode(g_ctx, next_batch) != 0) {
            LOGE("Failed to decode token");
            break;
        }

        llama_batch_free(next_batch);
        ++n_generated;
    }

    // Clean up sampler
    llama_sampler_free(smpl);

    // Send final result
    jstring js = (*env)->NewStringUTF(env, output);
    if (ctx_data->onResultMethodId) {
        (*env)->CallVoidMethod(env, ctx_data->callback,
                               ctx_data->onResultMethodId, js);
    }

    free(output);
batch_cleanup:
    llama_batch_free(batch);

unlock_and_cleanup:
    pthread_mutex_unlock(&g_lock);
    if (attached == JNI_OK)
        (*g_jvm)->DetachCurrentThread(g_jvm);
    if (ctx_data->callback) {
        (*env)->DeleteGlobalRef(env, ctx_data->callback);
    }
    free(ctx_data);
    return NULL;
}

// --- Java Native Interface: Init Model ---
JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeInitModel(JNIEnv *env, jobject thiz, jstring modelPath) {
    const char* path = (*env)->GetStringUTFChars(env, modelPath, 0);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    struct llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // No GPU layers for Android

    pthread_mutex_lock(&g_lock);
    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path);
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, modelPath, path);
        return (*env)->NewStringUTF(env, "Error: Failed to load model. Please check if the file is a valid GGUF format and accessible.");
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
        (*env)->ReleaseStringUTFChars(env, modelPath, path);
        return (*env)->NewStringUTF(env, "Error: Failed to create model context. The model might be corrupted or incompatible with this version.");
    }

    g_inference_active = 1;
    pthread_mutex_unlock(&g_lock);

    LOGI("Model loaded successfully from %s", path);
    (*env)->ReleaseStringUTFChars(env, modelPath, path);
    return (*env)->NewStringUTF(env, "Success");
}

// --- Java Native Interface: Check if Model is Loaded ---
JNIEXPORT jboolean JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeIsModelLoaded(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&g_lock);
    jboolean result = (g_model != NULL && g_ctx != NULL) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_lock);
    return result;
}

// --- Java Native Interface: Generate (async) ---
JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeGenerate(JNIEnv *env, jobject thiz, jstring prompt, jint maxTokens, jobject callbackObj) {
    if (!prompt || !callbackObj) {
        LOGE("Invalid parameters: prompt or callback object is null");
        return;
    }

    pthread_mutex_lock(&g_lock);
    if (!g_ctx || !g_model) {
        LOGE("Model not loaded! Cannot generate response.");
        pthread_mutex_unlock(&g_lock);
        
        // Get method ID for error callback
        jclass callbackClass = (*env)->GetObjectClass(env, callbackObj);
        jmethodID onErrorMethodId = (*env)->GetMethodID(env, callbackClass, "onError", "(Ljava/lang/String;)V");
        
        if (onErrorMethodId) {
            (*env)->CallVoidMethod(env, callbackObj,
                                   onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Model not initialized. Please load a valid GGUF model file first."));
        }
        return;
    }
    pthread_mutex_unlock(&g_lock);

    // Prepare data for thread
    inference_context_t* ctx_data = malloc(sizeof(inference_context_t));
    if (!ctx_data) {
        LOGE("Failed to allocate context for inference");
        
        // Get method ID for error callback
        jclass callbackClass = (*env)->GetObjectClass(env, callbackObj);
        jmethodID onErrorMethodId = (*env)->GetMethodID(env, callbackClass, "onError", "(Ljava/lang/String;)V");
        
        if (onErrorMethodId) {
            (*env)->CallVoidMethod(env, callbackObj,
                                   onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Memory allocation failed. Not enough memory for inference."));
        }
        return;
    }

    // Get method IDs
    jclass callbackClass = (*env)->GetObjectClass(env, callbackObj);
    jmethodID onResultMethodId = (*env)->GetMethodID(env, callbackClass, "onResult", "(Ljava/lang/String;)V");
    jmethodID onErrorMethodId = (*env)->GetMethodID(env, callbackClass, "onError", "(Ljava/lang/String;)V");
    jmethodID onProgressMethodId = (*env)->GetMethodID(env, callbackClass, "onProgress", "(Ljava/lang/String;)V");

    ctx_data->env = env;
    ctx_data->callback = (*env)->NewGlobalRef(env, callbackObj);
    ctx_data->onResultMethodId = onResultMethodId;
    ctx_data->onErrorMethodId = onErrorMethodId;
    ctx_data->onProgressMethodId = onProgressMethodId;
    
    // Copy prompt safely
    const char* promptChars = jstringToUtf8(env, prompt);
    //const char* promptChars = (*env)->GetStringUTFChars(env, prompt, 0);
    if (!promptChars) {
        LOGE("Failed to convert prompt to UTF-8");
        (*env)->DeleteGlobalRef(env, ctx_data->callback);
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
        (*env)->DeleteGlobalRef(env, ctx_data->callback);
        free(ctx_data);
        
        // Call error callback
        if (onErrorMethodId) {
            (*env)->CallVoidMethod(env, callbackObj, onErrorMethodId,
                                   (*env)->NewStringUTF(env, "Error: Failed to create inference thread. System resources might be exhausted."));
        }
        return;
    }

    pthread_detach(tid); // Let it clean up on its own
}

// --- Java Native Interface: Free Resources ---
JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_LlamaWrapper_nativeFree(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&g_lock);
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = NULL;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = NULL;
    }
    g_inference_active = 0;
    pthread_mutex_unlock(&g_lock);

    LOGI("LLM resources freed");
}

// --- Java Native Interface: Set JVM for threads ---
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}