// JNI bridge between the Compose UI and llama.cpp.
//
// Threading contract: nativeGenerate() runs synchronously on the calling thread.
// Kotlin invokes it from Dispatchers.IO inside a coroutine, so there is no reason
// to spawn a pthread here and attach it to the JVM. Cancellation is cooperative:
// nativeStop() raises a flag that the decode loop polls between tokens.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
// Staging API for multi-token prediction. See the note in CMakeLists.txt.
#include "llama-ext.h"

#define LOG_TAG "LlamaWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Global model state
// ---------------------------------------------------------------------------

// g_lock guards every mutation of the model/context/sampler below. It is held for
// the whole duration of a generation, so nothing the UI thread calls may take it
// -- see g_model_loaded for the lock-free "is it ready?" query.
std::mutex g_lock;

llama_model   * g_model   = nullptr;
llama_context * g_ctx     = nullptr;
llama_sampler * g_sampler = nullptr;

// Multi-token prediction.
//
// The model ships a small extra decoder block ("nextn"/MTP) trained to guess the
// token after next. Running it is far cheaper than a full forward pass, so it can
// draft several tokens which the main model then confirms in a single batch --
// reading the multi-gigabyte weights once instead of once per token.
//
// That trade is exactly right here: generation on a phone is bound by memory
// bandwidth, not by arithmetic, so the extra compute of a wider batch is close to
// free while the saved weight reads are the whole cost.
llama_context * g_ctx_mtp = nullptr;   // draft context, shares g_model
int             g_mtp_draft = 0;       // tokens to draft per step; 0 disables MTP
int             g_mtp_n_embd = 0;      // width of a hidden-state row

// Hidden state carried between calls: the MTP head pairs the hidden state at
// position p with the token at p+1, so the final row of one decode has to wait
// for the next one to find its partner.
std::vector<float> g_mtp_pending_h;

// Hidden rows from the most recent target batch, used to re-seed g_mtp_pending_h
// once we know how many drafted tokens were accepted.
std::vector<float> g_mtp_verify_h;
int                g_mtp_verify_rows = 0;

// Read from the UI thread on every recomposition, so these must never block.
std::atomic<bool> g_model_loaded{false};
std::atomic<bool> g_generating{false};
std::atomic<bool> g_stop_requested{false};

// Tokens currently resident in the KV cache of sequence 0, used to reuse the
// common prefix between turns instead of reprocessing the whole conversation.
std::vector<llama_token> g_cached_tokens;

struct sampling_params {
    float    temp           = 0.7f;
    float    top_p          = 0.95f;
    int32_t  top_k          = 40;
    float    min_p          = 0.05f;
    float    repeat_penalty = 1.1f;
    int32_t  repeat_last_n  = 64;
    uint32_t seed           = LLAMA_DEFAULT_SEED;
};

sampling_params g_sparams;

constexpr size_t MAX_LOG_CAPTURE = 8192;

// Errors llama.cpp logged during the current load. It reports precisely why a
// load failed, then swallows the exception, leaving the JNI layer with only a
// NULL model. Keeping the text lets us tell an unsupported architecture apart
// from a truncated download instead of guessing.
//
// These are accumulated rather than overwritten: llama.cpp emits a log line in
// several callback invocations, and a trailing fragment would otherwise replace
// the part that names the cause.
std::mutex  g_log_lock;
std::string g_log_errors;

// ---------------------------------------------------------------------------
// String helpers
//
// JNI's NewStringUTF/GetStringUTFChars speak *modified* UTF-8, which differs from
// real UTF-8 for U+0000 and for every character above the BMP. Model output
// routinely contains emoji (4-byte UTF-8), and handing those to NewStringUTF is
// undefined behaviour that aborts the VM on ART. So we convert by hand in both
// directions and use GetStringChars/NewString, which are plain UTF-16.
// ---------------------------------------------------------------------------

std::string jstring_to_utf8(JNIEnv * env, jstring jstr) {
    std::string out;
    if (!jstr) return out;

    const jsize   len = env->GetStringLength(jstr);
    const jchar * raw = env->GetStringChars(jstr, nullptr);
    if (!raw) return out;

    out.reserve((size_t) len * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = raw[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = raw[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back((char) cp);
        } else if (cp < 0x800) {
            out.push_back((char) (0xC0 | (cp >> 6)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char) (0xE0 | (cp >> 12)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char) (0xF0 | (cp >> 18)));
            out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(jstr, raw);
    return out;
}

jstring utf8_to_jstring(JNIEnv * env, const char * s, size_t len) {
    std::vector<jchar> u16;
    u16.reserve(len);

    size_t i = 0;
    while (i < len) {
        const unsigned char c = (unsigned char) s[i];
        uint32_t cp;
        size_t   extra;

        if      (c < 0x80)           { cp = c;        extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { ++i; continue; } // stray continuation byte or invalid lead

        if (i + extra >= len) break; // truncated sequence at end of buffer

        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            const unsigned char cc = (unsigned char) s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        i += extra + 1;
        if (!ok || cp > 0x10FFFF) continue;

        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back((jchar) (0xD800 + (cp >> 10)));
            u16.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back((jchar) cp);
        }
    }

    return env->NewString(u16.data(), (jsize) u16.size());
}

jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    return utf8_to_jstring(env, s.data(), s.size());
}

// Length of `s` truncated to the last complete UTF-8 sequence. Streaming deltas
// arrive one token at a time and a token can end mid-character; emitting the
// partial bytes renders as a replacement char that then disappears.
size_t utf8_safe_len(const std::string & s) {
    const size_t n = s.size();
    // A sequence is at most 4 bytes, so we never walk back further than that.
    for (size_t back = 0; back < 4 && back < n; ++back) {
        const size_t pos = n - 1 - back;
        const unsigned char c = (unsigned char) s[pos];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = 1;
        if      ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return (pos + need <= n) ? n : pos; // complete? emit all : cut before it
    }
    return n;
}


// ---------------------------------------------------------------------------
// Multi-token prediction
// ---------------------------------------------------------------------------

// A batch that carries tokens *and* hidden states.
//
// llama_batch_init allocates one or the other, never both, but the MTP head is
// fed a token together with the hidden state that preceded it. The reference
// implementation in common/speculative.cpp works around this the same way.
struct mtp_batch {
    llama_batch batch{};
    bool token_owned = false;

    void init(int32_t capacity, int32_t n_embd) {
        batch = llama_batch_init(capacity, n_embd, /*n_seq_max=*/ 1);
        batch.token = (llama_token *) malloc(sizeof(llama_token) * (size_t) capacity);
        token_owned = batch.token != nullptr;
    }

    void free() {
        if (token_owned && batch.token) {
            ::free(batch.token);
            batch.token = nullptr;
        }
        token_owned = false;
        llama_batch_free(batch);
        batch = llama_batch{};
    }

    void clear() { batch.n_tokens = 0; }

    void add(llama_token id, llama_pos pos, const float * h, int32_t n_embd, bool logits) {
        const int32_t i = batch.n_tokens;
        batch.token[i]     = id;
        batch.pos[i]       = pos;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = logits;
        std::memcpy(batch.embd + (size_t) i * n_embd, h, (size_t) n_embd * sizeof(float));
        batch.n_tokens = i + 1;
    }
};


// Cumulative time per reply, split by where it goes. Speculation only pays when
// the draft work is a small fraction of the verification it replaces.
double g_mtp_ms_draft  = 0.0;
double g_mtp_ms_target = 0.0;
double g_mtp_ms_follow = 0.0;

// Puts the draft head back to a blank slate.
//
// It has to happen everywhere the target's memory is cleared. The two contexts
// only produce useful drafts while they agree on what has been said, and leaving
// the draft head holding a previous conversation makes its decode either fail
// outright or return guesses about a history that no longer exists.
void mtp_reset_locked() {
    if (!g_ctx_mtp) return;
    llama_memory_clear(llama_get_memory(g_ctx_mtp), true);
    std::fill(g_mtp_pending_h.begin(), g_mtp_pending_h.end(), 0.0f);
    g_mtp_verify_h.clear();
    g_mtp_verify_rows = 0;
}

// Mirrors the target's decode into the draft head.
//
// The MTP head predicts the token after next, so it pairs the hidden state at
// position p with the token at p+1: the hidden rows are shifted right by one and
// the gap at the front is filled from the previous call. That carryover is the
// whole reason g_mtp_pending_h exists -- the last row of one decode has no
// partner until the next decode supplies it.
bool mtp_follow_target(const std::vector<llama_token> & tokens, int pos0) {
    if (!g_ctx_mtp || tokens.empty()) return false;
    const auto t_follow = std::chrono::steady_clock::now();
    struct scope_timer {
        std::chrono::steady_clock::time_point t;
        ~scope_timer() {
            g_mtp_ms_follow += std::chrono::duration_cast<
                std::chrono::duration<double, std::milli>>(
                    std::chrono::steady_clock::now() - t).count();
        }
    } timer{t_follow};

    const int32_t n_embd = g_mtp_n_embd;
    const size_t  row_bytes = (size_t) n_embd * sizeof(float);
    const int32_t n = (int32_t) tokens.size();

    mtp_batch mb;
    mb.init(n, n_embd);
    if (!mb.token_owned) { mb.free(); return false; }

    const float * h_tgt = llama_get_embeddings_nextn(g_ctx);
    if (!h_tgt) { mb.free(); return false; }

    // Copy the target's rows out before touching the draft context, so nothing
    // depends on how long that pointer stays valid across another decode.
    //
    // h_nextn covers every position in the batch. The _ith accessor would index
    // the output-ordered view instead, which only lines up when logits were
    // requested on every row -- and the prompt is decoded with logits on its
    // last token alone.
    g_mtp_verify_rows = n;
    g_mtp_verify_h.resize((size_t) n * n_embd);
    std::memcpy(g_mtp_verify_h.data(), h_tgt, row_bytes * (size_t) n);

    // The MTP head pairs the hidden state at position p with the token at p+1,
    // so the rows shift right by one and the gap at the front is filled from the
    // previous call.
    for (int32_t i = 0; i < n; ++i) {
        const float * h = (i == 0)
            ? g_mtp_pending_h.data()
            : (g_mtp_verify_h.data() + (size_t) (i - 1) * n_embd);
        mb.add(tokens[(size_t) i], pos0 + i, h, n_embd, /*logits=*/ false);
    }

    const int rc = llama_decode(g_ctx_mtp, mb.batch);
    mb.free();
    if (rc != 0) {
        g_mtp_verify_rows = 0;
        LOGW("MTP follow decode failed rc=%d", rc);
        return false;
    }

    // Keep the target's rows: which one becomes the next carryover depends on
    // how many drafted tokens survive verification, and that is not known yet.
    std::memcpy(g_mtp_pending_h.data(),
                g_mtp_verify_h.data() + (size_t) (n - 1) * n_embd, row_bytes);
    return true;
}

// Once verification is done, the carryover is the hidden row belonging to the
// last token that survived.
void mtp_commit(int n_accepted) {
    if (g_mtp_verify_rows <= 0) return;
    const int i = std::min(n_accepted, g_mtp_verify_rows - 1);
    std::memcpy(g_mtp_pending_h.data(),
                g_mtp_verify_h.data() + (size_t) i * g_mtp_n_embd,
                (size_t) g_mtp_n_embd * sizeof(float));
}

// Runs the draft head forward to guess up to g_mtp_draft tokens.
//
// Greedy on purpose: a draft is a guess that the target will confirm or throw
// away, so spending sampling variety on it only lowers the acceptance rate.
// Cumulative time spent in the draft head versus the target, for one reply.
// Speculation only pays when a draft costs a fraction of a full forward pass;
// if it costs anything like a full pass, drafting is strictly worse than just
// generating the token.
std::vector<llama_token> mtp_draft_tokens(llama_token id_last, int n_past) {
    const auto t0 = std::chrono::steady_clock::now();
    std::vector<llama_token> draft;
    if (!g_ctx_mtp || g_mtp_draft <= 0) return draft;

    const int32_t n_embd = g_mtp_n_embd;

    mtp_batch mb;
    mb.init(1, n_embd);
    if (!mb.token_owned) { mb.free(); return draft; }

    llama_sampler * greedy = llama_sampler_init_greedy();
    if (!greedy) { mb.free(); return draft; }

    std::vector<float> h(g_mtp_pending_h);

    for (int i = 0; i < g_mtp_draft; ++i) {
        mb.clear();
        const llama_token id = draft.empty() ? id_last : draft.back();
        mb.add(id, n_past + i, h.data(), n_embd, /*logits=*/ true);

        if (llama_decode(g_ctx_mtp, mb.batch) != 0) break;

        const llama_token next = llama_sampler_sample(greedy, g_ctx_mtp, 0);
        if (next < 0) break;
        draft.push_back(next);

        const float * h_row = llama_get_embeddings_nextn_ith(g_ctx_mtp, 0);
        if (!h_row) break;
        h.assign(h_row, h_row + n_embd);
    }

    llama_sampler_free(greedy);
    mb.free();
    g_mtp_ms_draft += std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(
        std::chrono::steady_clock::now() - t0).count();
    return draft;
}

// ---------------------------------------------------------------------------
// Callback plumbing
// ---------------------------------------------------------------------------

struct java_callback {
    JNIEnv *  env      = nullptr;
    jobject   obj      = nullptr;
    jmethodID on_token = nullptr;
    jmethodID on_done  = nullptr;
    jmethodID on_error = nullptr;

    bool bind(JNIEnv * e, jobject o) {
        env = e;
        obj = o;
        jclass cls = e->GetObjectClass(o);
        if (!cls) return false;
        on_token = e->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        on_done  = e->GetMethodID(cls, "onDone",  "(IJII)V");
        on_error = e->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
        e->DeleteLocalRef(cls);
        return on_token && on_done && on_error;
    }

    void token(const std::string & piece) const {
        if (!on_token || piece.empty()) return;
        jstring js = utf8_to_jstring(env, piece);
        env->CallVoidMethod(obj, on_token, js);
        env->DeleteLocalRef(js);
    }

    void done(int n_tokens, long long elapsed_ms, int drafted, int accepted) const {
        if (on_done) {
            env->CallVoidMethod(obj, on_done, (jint) n_tokens, (jlong) elapsed_ms,
                                (jint) drafted, (jint) accepted);
        }
    }

    void error(const std::string & msg) const {
        if (!on_error) return;
        LOGE("%s", msg.c_str());
        jstring js = utf8_to_jstring(env, msg);
        env->CallVoidMethod(obj, on_error, js);
        env->DeleteLocalRef(js);
    }
};

// ---------------------------------------------------------------------------
// Sampler
// ---------------------------------------------------------------------------

// Order matters: penalties operate on the full distribution, then the truncation
// samplers narrow the candidate set, then temperature reshapes it, then `dist`
// draws. The previous build applied penalties *after* top-p/min-p had already
// discarded most candidates, which made the penalty close to a no-op.
void rebuild_sampler_locked() {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    auto chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    g_sampler = llama_sampler_chain_init(chain_params);
    if (!g_sampler) return;

    // llama_sampler_init_penalties gained a leading n_vocab parameter; the
    // sampler is only ever rebuilt while a model is resident, so the vocab is
    // always available here.
    const int32_t n_vocab = g_model ? llama_vocab_n_tokens(llama_model_get_vocab(g_model)) : 0;

    if (g_sparams.repeat_penalty != 1.0f && n_vocab > 0) {
        llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
            n_vocab, g_sparams.repeat_last_n, g_sparams.repeat_penalty, 0.0f, 0.0f));
    }

    if (g_sparams.temp <= 0.0f) {
        // Temperature 0 means deterministic decoding. A dist sampler over a
        // one-hot distribution would still consume the RNG, so use greedy.
        llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
        return;
    }

    if (g_sparams.top_k > 0)    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(g_sparams.top_k));
    if (g_sparams.top_p < 1.0f) llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_sparams.top_p, 1));
    if (g_sparams.min_p > 0.0f) llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_sparams.min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_sparams.temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_sparams.seed));
}

// ---------------------------------------------------------------------------
// Decoding helpers
// ---------------------------------------------------------------------------

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    const int32_t needed = -llama_tokenize(vocab, text.data(), (int32_t) text.size(),
                                           nullptr, 0, add_special, true);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens((size_t) needed);
    const int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(),
                                     tokens.data(), needed, add_special, true);
    if (n < 0) return {};
    tokens.resize((size_t) n);
    return tokens;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token id) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
    if (n >= 0) return std::string(buf, (size_t) n);

    // A negative return means "buffer too small, needed -n bytes".
    std::string out((size_t) -n, '\0');
    n = llama_token_to_piece(vocab, id, &out[0], (int32_t) out.size(), 0, true);
    if (n < 0) return {};
    out.resize((size_t) n);
    return out;
}

// Feed `tokens` starting at position `pos0`, split into n_batch-sized chunks.
// llama_decode rejects any batch larger than the context's logical batch size,
// which is what made long prompts fail with "Failed to process prompt".
// `logits_on_all` is what speculative decoding needs: every position in the run
// has to be checkable, not just the final one.
bool decode_tokens(const std::vector<llama_token> & tokens, int pos0, bool logits_on_last,
                   bool logits_on_all = false) {
    const int total = (int) tokens.size();
    if (total == 0) return true;

    const int n_batch = std::max(1, (int) llama_n_batch(g_ctx));

    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    bool ok = true;

    for (int off = 0; off < total && ok; off += n_batch) {
        const int n = std::min(n_batch, total - off);
        batch.n_tokens = n;
        for (int i = 0; i < n; ++i) {
            batch.token[i]     = tokens[(size_t) (off + i)];
            batch.pos[i]       = pos0 + off + i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = logits_on_all;
        }
        if (logits_on_last && off + n == total) {
            batch.logits[n - 1] = true;
        }
        if (llama_decode(g_ctx, batch) != 0) {
            ok = false;
        }
    }

    llama_batch_free(batch);
    return ok;
}



constexpr const char * THINK_OPEN_TAG  = "<think>";
constexpr const char * THINK_SUFFIX_ON  = "<think>\n";
constexpr const char * THINK_SUFFIX_OFF = "<think>\n\n</think>\n\n";

// Whether the loaded model is a reasoning model.
//
// Detected from its chat template rather than from the architecture name, which
// says nothing about it: Qwen3, DeepSeek-R1 and others all mark reasoning with a
// <think> block, and a template that never mentions one belongs to a model that
// does not reason.
bool model_uses_think_blocks_locked() {
    if (!g_model) return false;
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    return tmpl && strstr(tmpl, THINK_OPEN_TAG) != nullptr;
}


// Thinking mode, mirroring org.zzssg.llmchatapp.llm.ThinkingMode.
enum thinking_mode { THINKING_AUTO = 0, THINKING_ON = 1, THINKING_OFF = 2 };

// Reproduces what the model's own Jinja template does for enable_thinking.
//
// llama_chat_apply_template is the non-Jinja path: it matches a template to a
// hardcoded C++ implementation and cannot evaluate a variable like
// enable_thinking. The templates that support it all express the choice the same
// way -- open a <think> block for the model to fill, or pre-fill an empty one so
// it skips straight to the answer -- so appending the suffix ourselves gives the
// same prompt the Jinja path would have produced.
std::string apply_thinking_suffix(const std::string & prompt, int mode) {
    if (mode == THINKING_AUTO) return prompt;
    if (!model_uses_think_blocks_locked()) return prompt;

    // Some templates already emit the opener. Do not add a second one.
    const size_t tail = prompt.size() < 32 ? 0 : prompt.size() - 32;
    if (prompt.find(THINK_OPEN_TAG, tail) != std::string::npos) return prompt;

    return prompt + (mode == THINKING_OFF ? THINK_SUFFIX_OFF : THINK_SUFFIX_ON);
}

// Reads general.architecture straight out of the GGUF header.
//
// llama.cpp rejects an unknown architecture with a runtime_error that it logs
// and swallows, so all the JNI layer sees is a NULL model -- indistinguishable
// from a corrupt file. Re-reading the header lets us tell the user which of the
// two actually happened, and name the architecture when it is the culprit.
std::string read_gguf_architecture(const char * path) {
    gguf_init_params params = { /*no_alloc =*/ true, /*ctx =*/ nullptr };
    gguf_context * ctx = gguf_init_from_file(path, params);
    if (!ctx) return {};

    std::string arch;
    const int64_t key = gguf_find_key(ctx, "general.architecture");
    if (key >= 0) {
        const char * value = gguf_get_val_str(ctx, key);
        if (value) arch = value;
    }
    gguf_free(ctx);
    return arch;
}


// Number of cores in the fastest frequency tier.
//
// Inference threads run in lockstep and synchronise at every layer, so a thread
// on a slow core stalls all the others. What matters is therefore how many
// *equally fast* cores exist, not how many cores there are.
//
// Guessing that from the core count alone breaks on both ends: a 4+4 big.LITTLE
// phone and an all-performance flagship both report 8, but want 4 and 8 threads
// respectively. Linux exposes the real answer through cpufreq.
int count_fastest_cores() {
    const long online = sysconf(_SC_NPROCESSORS_ONLN);
    const int  cpus   = (int) (online > 0 ? online : 1);

    std::vector<long> max_freq;
    max_freq.reserve((size_t) cpus);

    for (int i = 0; i < cpus; ++i) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (!f) continue;
        long khz = 0;
        if (fscanf(f, "%ld", &khz) == 1 && khz > 0) max_freq.push_back(khz);
        fclose(f);
    }

    if (max_freq.empty()) {
        // No cpufreq (some emulators, restricted sandboxes). Fall back to the
        // conservative big.LITTLE assumption.
        return std::max(1, cpus > 4 ? cpus / 2 : cpus);
    }

    const long fastest = *std::max_element(max_freq.begin(), max_freq.end());
    // Within 15% counts as the same tier: prime and performance cores in one
    // cluster differ slightly in clock but are equally useful here.
    const long threshold = fastest - fastest / 7;

    int n = 0;
    for (long khz : max_freq) {
        if (khz >= threshold) ++n;
    }

    return std::clamp(n, 1, cpus);
}


// Brings up the draft context that runs the MTP head.
//
// It is a second context over the *same* model -- the weights are shared, only
// the graph differs -- so the cost is the MTP block plus its own small state,
// not another copy of the model.
//
// Failure here is not fatal: MTP is an optimisation, and a model without a
// usable nextn block simply generates the ordinary way.
void setup_mtp_locked(int draft, const llama_context_params & base) {
    g_ctx_mtp = nullptr;
    g_mtp_draft = 0;

    const int n_layer_nextn = (int) llama_model_n_layer_nextn(g_model);
    if (n_layer_nextn <= 0) {
        LOGI("model has no MTP block, speculative decoding disabled");
        return;
    }

    llama_context_params mparams = base;
    mparams.ctx_type  = LLAMA_CONTEXT_TYPE_MTP;
    mparams.ctx_other = g_ctx;
    // The draft head never needs to rewind: rejected drafts are dropped whole.
    mparams.n_rs_seq  = 0;
    mparams.embeddings = false;

    g_ctx_mtp = llama_init_from_model(g_model, mparams);
    if (!g_ctx_mtp) {
        LOGW("could not create the MTP draft context, falling back to plain decoding");
        return;
    }

    g_mtp_n_embd = (int) llama_model_n_embd_out(g_model);
    if (g_mtp_n_embd <= 0) {
        LOGW("MTP hidden width is %d, disabling", g_mtp_n_embd);
        llama_free(g_ctx_mtp);
        g_ctx_mtp = nullptr;
        return;
    }

    // The target has to publish the hidden state that feeds the MTP head, and
    // the draft context has to consume it.
    llama_set_embeddings_nextn(g_ctx,     true, /*masked=*/ false);
    llama_set_embeddings_nextn(g_ctx_mtp, true, /*masked=*/ true);

    g_mtp_draft = draft;
    g_mtp_pending_h.assign((size_t) g_mtp_n_embd, 0.0f);

    LOGI("MTP enabled: draft=%d nextn_layers=%d n_embd=%d n_rs_seq=%d",
         draft, n_layer_nextn, g_mtp_n_embd, (int) base.n_rs_seq);
}

void ggml_log_to_logcat(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
        if (text) {
            std::lock_guard<std::mutex> guard(g_log_lock);
            if (g_log_errors.size() < MAX_LOG_CAPTURE) g_log_errors += text;
        }
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("%s", text);
    }
}

std::string take_log_errors() {
    std::lock_guard<std::mutex> guard(g_log_lock);
    std::string out;
    out.swap(g_log_errors);
    return out;
}

} // namespace

// ===========================================================================
// JNI entry points
// ===========================================================================

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jclass, jstring jpath, jint threads, jint ctxSize, jint gpuLayers,
        jint mtpDraft) {

    const std::string path = jstring_to_utf8(env, jpath);

    std::lock_guard<std::mutex> guard(g_lock);

    // Loading a second model used to overwrite the globals and leak the first.
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx_mtp) { llama_free(g_ctx_mtp);         g_ctx_mtp = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    g_model_loaded.store(false);
    g_cached_tokens.clear();
    g_mtp_draft = 0;
    g_mtp_pending_h.clear();
    g_mtp_verify_h.clear();
    g_mtp_verify_rows = 0;

    llama_backend_init();
    llama_log_set(ggml_log_to_logcat, nullptr);

    int n_threads = threads;
    if (n_threads <= 0) {
        n_threads = count_fastest_cores();
        LOGI("auto thread count: %d", n_threads);
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpuLayers;
    // The separate use_mmap/use_mlock booleans were replaced by a single mode.
    // MMAP without MLOCK is deliberate: pinning a multi-gigabyte model gets the
    // app killed by Android's low-memory killer on most phones.
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;
    // The MTP block is only worth its memory when we are going to draft with it.
    mparams.load_mtp     = mtpDraft > 0;

    LOGI("loading model: %s (threads=%d ctx=%d gpu_layers=%d)",
         path.c_str(), n_threads, (int) ctxSize, (int) gpuLayers);

    take_log_errors(); // discard anything from a previous attempt
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        const std::string reason = take_log_errors();

        // llama.cpp names the architecture it does not recognise. Trust that
        // over re-reading the header, which cannot tell a supported
        // architecture from an unsupported one.
        if (reason.find("unknown model architecture") != std::string::npos) {
            std::string arch = read_gguf_architecture(path.c_str());
            if (arch.empty()) arch = "unknown";
            LOGE("unsupported architecture '%s'", arch.c_str());
            return utf8_to_jstring(env,
                "E_ARCH|This model uses the \"" + arch + "\" architecture, which the bundled "
                "inference engine does not support. Try a model built on a more established "
                "architecture, such as qwen3, llama or gemma3.");
        }

        if (reason.find("not within the file bounds") != std::string::npos ||
            reason.find("corrupted or incomplete") != std::string::npos) {
            return utf8_to_jstring(env,
                "E_TRUNCATED|This model file is incomplete. The download probably did not "
                "finish -- check the file size against the source and download it again.");
        }

        if (read_gguf_architecture(path.c_str()).empty()) {
            return utf8_to_jstring(env,
                "E_LOAD|This file is not a readable GGUF model.");
        }

        return utf8_to_jstring(env,
            "E_LOAD|The model could not be loaded." + (reason.empty() ? "" : " " + reason));
    }

    const int n_ctx_train = llama_model_n_ctx_train(g_model);
    int n_ctx = ctxSize > 0 ? (int) ctxSize : 4096;
    if (n_ctx_train > 0 && n_ctx > n_ctx_train) {
        LOGW("requested ctx %d exceeds the model's training ctx %d, clamping", n_ctx, n_ctx_train);
        n_ctx = n_ctx_train;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) std::min(n_ctx, 512);
    cparams.n_ubatch        = (uint32_t) std::min(n_ctx, 256);
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf         = true;
    // AUTO lets llama.cpp enable flash attention only when the loaded model and
    // backend actually support it. The old code logged the flag and did nothing.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    // Rejecting a drafted token means rewinding the model's state by up to
    // mtpDraft positions. Attention caches rewind for free, but a recurrent or
    // hybrid model carries one rolling state and can only step back through
    // snapshots it was told to keep. Each snapshot costs memory, which is why
    // this is sized to the draft depth and left at zero when MTP is off.
    const int mtp_requested = std::max(0, (int) mtpDraft);
    cparams.n_rs_seq = (uint32_t) mtp_requested;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return utf8_to_jstring(env,
            "E_CONTEXT|Not enough memory to open this model. Try a smaller model or a shorter context.");
    }

    if (mtp_requested > 0) {
        setup_mtp_locked(mtp_requested, cparams);
    }

    rebuild_sampler_locked();
    if (!g_sampler) {
        llama_free(g_ctx);         g_ctx   = nullptr;
        llama_model_free(g_model); g_model = nullptr;
        return utf8_to_jstring(env, "E_SAMPLER|Could not initialise the sampler.");
    }

    g_model_loaded.store(true);
    LOGI("model loaded: n_ctx=%d n_ctx_train=%d", n_ctx, n_ctx_train);
    return utf8_to_jstring(env, "OK");
}

JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeUnloadModel(JNIEnv *, jclass) {
    // Ask any in-flight generation to stop before we block on its lock.
    g_stop_requested.store(true);
    std::lock_guard<std::mutex> guard(g_lock);

    g_model_loaded.store(false);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    g_cached_tokens.clear();
    g_stop_requested.store(false);
    LOGI("model unloaded");
}

// Lock-free on purpose: this is polled from the UI thread. The old version took
// the same mutex that inference holds for minutes, which froze the app.
JNIEXPORT jboolean JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeIsModelLoaded(JNIEnv *, jclass) {
    return g_model_loaded.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeContextSize(JNIEnv *, jclass) {
    if (!g_model_loaded.load()) return 0;
    std::lock_guard<std::mutex> guard(g_lock);
    return g_ctx ? (jint) llama_n_ctx(g_ctx) : 0;
}

JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeModelDescription(JNIEnv * env, jclass) {
    if (!g_model_loaded.load()) return utf8_to_jstring(env, "", 0);
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_model) return utf8_to_jstring(env, "", 0);

    char buf[256] = {0};
    llama_model_desc(g_model, buf, sizeof(buf));
    return utf8_to_jstring(env, buf, strnlen(buf, sizeof(buf)));
}

JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeSetSampling(
        JNIEnv *, jclass, jfloat temp, jfloat topP, jint topK, jfloat minP,
        jfloat repeatPenalty, jint seed) {

    std::lock_guard<std::mutex> guard(g_lock);
    g_sparams.temp           = temp;
    g_sparams.top_p          = topP;
    g_sparams.top_k          = topK;
    g_sparams.min_p          = minP;
    g_sparams.repeat_penalty = repeatPenalty;
    g_sparams.seed           = seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;
    // Rebuild immediately so a settings change applies to the next turn. The old
    // build created the sampler once and ignored every later parameter change.
    if (g_ctx) rebuild_sampler_locked();
}

// Drops the KV cache so the next turn starts from an empty context.
JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeResetSession(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx)     llama_memory_clear(llama_get_memory(g_ctx), true);
    if (g_sampler) llama_sampler_reset(g_sampler);
    mtp_reset_locked();
    g_cached_tokens.clear();
}

JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeStop(JNIEnv *, jclass) {
    g_stop_requested.store(true);
}

// Formats `roles`/`contents` with the model's own chat template and returns the
// prompt string. Exposed separately so the UI can measure the prompt before
// committing to a turn.

// Parameter count of the loaded model, used to decide whether MTP fits.
JNIEXPORT jlong JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeModelParams(JNIEnv *, jclass) {
    if (!g_model_loaded.load()) return 0;
    std::lock_guard<std::mutex> guard(g_lock);
    return g_model ? (jlong) llama_model_n_params(g_model) : 0;
}

// Whether the loaded model ships an MTP block at all, regardless of whether we
// asked for it. Lets the UI say "unavailable" instead of silently disabling.
JNIEXPORT jboolean JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeHasMtpBlock(JNIEnv *, jclass) {
    if (!g_model_loaded.load()) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(g_lock);
    return (g_model && llama_model_n_layer_nextn(g_model) > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeMtpDraft(JNIEnv *, jclass) {
    if (!g_model_loaded.load()) return 0;
    std::lock_guard<std::mutex> guard(g_lock);
    return (jint) (g_ctx_mtp ? g_mtp_draft : 0);
}

JNIEXPORT jboolean JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeSupportsThinking(JNIEnv *, jclass) {
    if (!g_model_loaded.load()) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(g_lock);
    return model_uses_think_blocks_locked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeFormatPrompt(
        JNIEnv * env, jclass, jobjectArray jroles, jobjectArray jcontents, jint thinkingMode) {

    if (!g_model_loaded.load()) return utf8_to_jstring(env, "", 0);

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string>        roles((size_t) n), contents((size_t) n);
    std::vector<llama_chat_message> msgs((size_t) n);

    for (jsize i = 0; i < n; ++i) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[(size_t) i]    = jstring_to_utf8(env, jr);
        contents[(size_t) i] = jstring_to_utf8(env, jc);
        if (jr) env->DeleteLocalRef(jr);
        if (jc) env->DeleteLocalRef(jc);
    }
    // Fill the message array only after the strings are in their final storage,
    // so c_str() cannot dangle from a vector reallocation.
    for (jsize i = 0; i < n; ++i) {
        msgs[(size_t) i].role    = roles[(size_t) i].c_str();
        msgs[(size_t) i].content = contents[(size_t) i].c_str();
    }

    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_model) return utf8_to_jstring(env, "", 0);

    const char * tmpl = llama_model_chat_template(g_model, nullptr);

    size_t cap = 1024;
    for (jsize i = 0; i < n; ++i) cap += 2 * (roles[(size_t) i].size() + contents[(size_t) i].size());

    std::string buf(cap, '\0');
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            &buf[0], (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize((size_t) len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                        &buf[0], (int32_t) buf.size());
    }

    if (len < 0) {
        // No usable template -- a base (non-instruct) model, or a Jinja template
        // llama.cpp's non-Jinja parser does not recognise. Fall back to a plain
        // transcript instead of failing the turn.
        std::string fallback;
        for (jsize i = 0; i < n; ++i) {
            const std::string & role = roles[(size_t) i];
            if      (role == "user")      fallback += "User: ";
            else if (role == "assistant") fallback += "Assistant: ";
            fallback += contents[(size_t) i];
            fallback += "\n";
        }
        fallback += "Assistant:";
        return utf8_to_jstring(env, apply_thinking_suffix(fallback, thinkingMode));
    }

    buf.resize((size_t) len);
    return utf8_to_jstring(env, apply_thinking_suffix(buf, thinkingMode));
}

JNIEXPORT void JNICALL
Java_org_zzssg_llmchatapp_llm_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jstring jprompt, jint maxTokens, jobject jcallback) {

    java_callback cb;
    if (!jcallback || !cb.bind(env, jcallback)) {
        LOGE("callback object does not implement the expected interface");
        return;
    }

    if (!g_model_loaded.load()) {
        cb.error("E_NOT_LOADED|No model is loaded.");
        return;
    }

    // Reject overlapping generations instead of silently queueing them on the
    // mutex, which used to interleave positions and corrupt the KV cache.
    bool expected = false;
    if (!g_generating.compare_exchange_strong(expected, true)) {
        cb.error("E_BUSY|A response is already being generated.");
        return;
    }

    const std::string prompt = jstring_to_utf8(env, jprompt);
    g_stop_requested.store(false);

    std::lock_guard<std::mutex> guard(g_lock);

    if (!g_ctx || !g_model || !g_sampler) {
        g_generating.store(false);
        cb.error("E_NOT_LOADED|No model is loaded.");
        return;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int           n_ctx = (int) llama_n_ctx(g_ctx);

    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);
    if (tokens.empty()) {
        g_generating.store(false);
        cb.error("E_TOKENIZE|Could not tokenize the prompt.");
        return;
    }

    // Leave room for the response. If the conversation no longer fits we drop
    // the oldest tokens rather than failing; the UI warns about this separately.
    const int n_prompt_budget = n_ctx - (int) maxTokens - 8;
    if (n_prompt_budget <= 0) {
        g_generating.store(false);
        cb.error("E_CONTEXT_FULL|The context window is too small for this request.");
        return;
    }
    if ((int) tokens.size() > n_prompt_budget) {
        LOGW("prompt is %zu tokens, budget is %d -- truncating from the front",
             tokens.size(), n_prompt_budget);
        tokens.erase(tokens.begin(), tokens.end() - n_prompt_budget);
    }

    // Prefix reuse: keep whatever the KV cache already holds and decode only the
    // divergent tail. Without this every turn reprocesses the whole transcript --
    // and the old code never cleared the cache at all, so the second turn decoded
    // fresh tokens onto positions that were already occupied, which is what
    // produced garbled replies after the first message.
    size_t common = 0;
    while (common < g_cached_tokens.size() && common < tokens.size() &&
           g_cached_tokens[common] == tokens[common]) {
        ++common;
    }
    // Always re-decode at least the final token so we have fresh logits to sample.
    if (common == tokens.size() && common > 0) --common;

    llama_memory_t mem = llama_get_memory(g_ctx);

    if (common > 0) {
        // Partial eviction is not universally supported. Recurrent and hybrid
        // models (Mamba, RWKV, Qwen3-Next/qwen35) carry a single rolling state
        // rather than a per-position cache, so llama.cpp can only roll it back a
        // few steps and returns false otherwise. Ignoring that return value left
        // the cache inconsistent and made llama_decode fail -- which is why the
        // *second* message of every conversation errored out on such models
        // while the first one worked.
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
            LOGI("partial cache eviction unsupported for this model, reprocessing from scratch");
            common = 0;
        } else if (g_ctx_mtp) {
            llama_memory_seq_rm(llama_get_memory(g_ctx_mtp), 0, (llama_pos) common, -1);
        }
    }

    if (common == 0) {
        llama_memory_clear(mem, true);
        mtp_reset_locked();
    }

    const std::vector<llama_token> tail(tokens.begin() + (long) common, tokens.end());
    const auto t_start = std::chrono::steady_clock::now();

    if (!decode_tokens(tail, (int) common, /*logits_on_last=*/true)) {
        llama_memory_clear(mem, true);
        mtp_reset_locked();
        g_cached_tokens.clear();
        g_generating.store(false);
        cb.error("E_DECODE|Failed to process the prompt.");
        return;
    }

    g_cached_tokens = tokens;

    // Prime the repetition penalty with the prompt so it sees the recent context
    // instead of starting from an empty window on every turn.
    llama_sampler_reset(g_sampler);
    for (llama_token t : tokens) {
        llama_sampler_accept(g_sampler, t);
    }

    g_mtp_ms_draft  = 0.0;
    g_mtp_ms_target = 0.0;
    g_mtp_ms_follow = 0.0;

    std::string pending;      // bytes held back as an incomplete UTF-8 sequence
    int         n_generated = 0;
    int         n_past      = (int) tokens.size();

    const bool speculative = g_ctx_mtp != nullptr && g_mtp_draft > 0;
    if (speculative) {
        // Mirror the prompt into the draft head so it starts from the same state.
        if (!mtp_follow_target(tail, (int) common)) {
            LOGW("MTP could not follow the prompt, generating without it");
        }
    }

    // Emits one token and folds it into every piece of state that tracks the
    // conversation. Shared by both paths so they cannot drift apart.
    int  n_drafted  = 0;
    int  n_accepted = 0;
    bool stop       = false;

    auto emit = [&](llama_token id) {
        llama_sampler_accept(g_sampler, id);

        if (llama_vocab_is_eog(vocab, id)) {
            stop = true;
            return;
        }

        pending += token_to_piece(vocab, id);
        const size_t safe = utf8_safe_len(pending);
        if (safe > 0) {
            cb.token(pending.substr(0, safe));
            pending.erase(0, safe);
        }

        g_cached_tokens.push_back(id);
        ++n_generated;
    };

    llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);

    while (!stop && n_generated < (int) maxTokens) {
        if (g_stop_requested.load()) break;

        if (id < 0) {
            LOGE("sampler returned an invalid token");
            break;
        }

        emit(id);
        if (stop || n_generated >= (int) maxTokens) break;

        if (n_past >= n_ctx - 1) {
            LOGW("context window exhausted after %d generated tokens", n_generated);
            break;
        }

        // --- plain path: one token in, one token out --------------------------
        if (!speculative) {
            const std::vector<llama_token> one{id};
            if (!decode_tokens(one, n_past, /*logits_on_last=*/true)) {
                LOGE("decode failed at position %d", n_past);
                break;
            }
            ++n_past;
            id = llama_sampler_sample(g_sampler, g_ctx, -1);
            continue;
        }

        // --- speculative path -------------------------------------------------
        //
        // The draft head guesses what follows `id`; the target then checks the
        // whole run in one pass. Whatever it agrees with is free -- those tokens
        // cost no extra reading of the weights, which is the entire point.
        std::vector<llama_token> draft = mtp_draft_tokens(id, n_past);

        // Never run past the context window with a speculative tail.
        while (!draft.empty() && n_past + 1 + (int) draft.size() >= n_ctx - 1) {
            draft.pop_back();
        }

        std::vector<llama_token> run;
        run.reserve(draft.size() + 1);
        run.push_back(id);
        run.insert(run.end(), draft.begin(), draft.end());

        const auto t_verify = std::chrono::steady_clock::now();
        if (!decode_tokens(run, n_past, /*logits_on_last=*/false, /*logits_on_all=*/true)) {
            LOGE("speculative decode failed at position %d", n_past);
            break;
        }
        g_mtp_ms_target += std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(
            std::chrono::steady_clock::now() - t_verify).count();
        mtp_follow_target(run, n_past);
        n_drafted += (int) draft.size();

        // Walk the guesses. Row j holds the distribution for the token that
        // follows run[j], so row j is what confirms or replaces draft[j].
        int accepted = 0;
        llama_token next = -1;

        // Diagnostic for the first round of a reply: whether the draft head is
        // producing plausible continuations that merely disagree, or unrelated
        // tokens. The two mean very different things, and an acceptance
        // percentage alone cannot tell them apart.
        const bool trace = (n_generated <= 1);
        if (trace && !draft.empty()) {
            std::string line;
            for (llama_token d : draft) line += "'" + token_to_piece(vocab, d) + "' ";
            LOGI("MTP draft after '%s': %s", token_to_piece(vocab, id).c_str(), line.c_str());
        }

        for (size_t j = 0; j <= draft.size(); ++j) {
            const llama_token sampled = llama_sampler_sample(g_sampler, g_ctx, (int32_t) j);

            if (trace && j < draft.size()) {
                LOGI("MTP verify[%d]: target='%s' draft='%s' %s", (int) j,
                     token_to_piece(vocab, sampled).c_str(),
                     token_to_piece(vocab, draft[j]).c_str(),
                     sampled == draft[j] ? "MATCH" : "miss");
            }

            if (j < draft.size() && sampled == draft[j]) {
                emit(sampled);
                ++accepted;
                if (stop || n_generated >= (int) maxTokens) break;
                continue;
            }

            // First disagreement: the target's own token wins and everything
            // after it was predicted from a future that will not happen.
            next = sampled;
            break;
        }

        n_accepted += accepted;
        n_past += 1 + accepted;

        // Drop the rejected tail so the next decode does not read state that
        // belongs to tokens the model just discarded.
        if (accepted < (int) draft.size()) {
            // Both contexts were fed the whole speculative run, so both have to
            // forget the part of it that did not survive. Rolling back only the
            // target leaves the draft head attending to tokens that never
            // happened -- after which its guesses are worthless, which is
            // exactly what a near-zero acceptance rate looks like.
            llama_memory_seq_rm(mem, 0, (llama_pos) n_past, -1);
            llama_memory_seq_rm(llama_get_memory(g_ctx_mtp), 0, (llama_pos) n_past, -1);
        }
        mtp_commit(accepted);

        if (stop || n_generated >= (int) maxTokens) break;
        id = next;
    }

    if (!pending.empty()) {
        cb.token(pending); // flush the tail even if it is malformed
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_start).count();

    LOGI("generated %d tokens in %lld ms (%.2f tok/s)", n_generated, (long long) elapsed,
         elapsed > 0 ? n_generated * 1000.0 / (double) elapsed : 0.0);
    if (n_drafted > 0) {
        LOGI("MTP cost: follow=%.0f ms draft=%.0f ms target=%.0f ms -> each draft token costs %.0f%% of a verify",
             g_mtp_ms_follow, g_mtp_ms_draft, g_mtp_ms_target,
             g_mtp_ms_target > 0 ? 100.0 * (g_mtp_ms_draft / n_drafted) / (g_mtp_ms_target / (double) (n_drafted / std::max(1, g_mtp_draft) + 1)) : 0.0);
        // Acceptance is the number that decides whether MTP is worth its memory.
        LOGI("MTP: drafted=%d accepted=%d (%.0f%%)", n_drafted, n_accepted,
             100.0 * n_accepted / (double) n_drafted);
    }

    g_stop_requested.store(false);
    g_generating.store(false);
    cb.done(n_generated, (long long) elapsed, n_drafted, n_accepted);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

} // extern "C"
