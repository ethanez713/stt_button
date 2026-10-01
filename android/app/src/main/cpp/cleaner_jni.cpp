// S1-mini on llama.cpp with prompt-lookup decoding: the loop of llama.cpp's speculative-simple
// example (b11264) with --spec-type ngram-simple, greedy, ported onto llama.h alone.
#include <jni.h>
#include <sched.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "ggml-cpu.h"
#include "llama.h"

namespace {

constexpr int N_CTX = 2048;
constexpr int NGRAM = 3;
constexpr int MGRAM = 16;

struct Cleaner {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * greedy = nullptr;
    ggml_threadpool * pool = nullptr;
    int wanted_threads = 0;
    int pool_threads = 0;
    std::atomic<bool> cancel{false};
    int out_tokens = 0;
    int drafted = 0;
    int accepted = 0;
};

int allowed_cpus() {
    cpu_set_t set;
    CPU_ZERO(&set);
    return sched_getaffinity(0, sizeof set, &set) == 0 ? std::max(CPU_COUNT(&set), 1) : 1;
}

// ggml's workers spin at a barrier after every operation. When the system has confined the process to
// fewer cores than threads (it does, for example, while another app is in the foreground), the spinning
// threads starve the ones with work and decoding runs 15 to 50 times slower, so the pool follows the
// cores the process may use right now.
void fit_threads_to_cores(Cleaner & c) {
    const int threads = std::min(c.wanted_threads, allowed_cpus());
    if (threads == c.pool_threads) {
        return;
    }
    ggml_threadpool_params params = ggml_threadpool_params_default(threads);
    ggml_threadpool * next = ggml_threadpool_new(&params);
    llama_attach_threadpool(c.ctx, next, nullptr);
    llama_set_n_threads(c.ctx, threads, threads);
    if (c.pool != nullptr) {
        ggml_threadpool_free(c.pool);
    }
    c.pool = next;
    c.pool_threads = threads;
}

std::string bytes_to_string(JNIEnv * env, jbyteArray array) {
    const jsize n = env->GetArrayLength(array);
    std::string text(n, '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(text.data()));
    return text;
}

std::vector<llama_token> tokenize(const Cleaner & c, const std::string & text, bool special) {
    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(c.vocab, text.data(), text.size(), tokens.data(), tokens.size(), special, special);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(c.vocab, text.data(), text.size(), tokens.data(), tokens.size(), special, special);
    }
    tokens.resize(std::max(n, 0));
    return tokens;
}

std::string piece(const Cleaner & c, llama_token token) {
    char buf[256];
    const int n = llama_token_to_piece(c.vocab, token, buf, sizeof buf, 0, true);
    return n > 0 ? std::string(buf, n) : std::string();
}

// llama.cpp common/ngram-map.cpp, common_ngram_simple_draft: the tokens that followed the most
// recent earlier occurrence of the last NGRAM tokens (the last one being the sampled token).
std::vector<llama_token> draft_from_history(const std::vector<llama_token> & tokens, llama_token sampled) {
    std::vector<llama_token> draft;
    const size_t len = tokens.size();
    if (len <= static_cast<size_t>(NGRAM + MGRAM + 1)) {
        return draft;
    }
    std::vector<llama_token> pattern(tokens.end() - (NGRAM - 1), tokens.end());
    pattern.push_back(sampled);
    size_t match = 0;
    for (size_t j = len - NGRAM - 1; j > 0; --j) {
        if (std::equal(pattern.begin(), pattern.end(), tokens.begin() + j)) {
            match = j;
            break;
        }
    }
    if (match == 0) {
        return draft;
    }
    const size_t copy = std::min<size_t>(MGRAM, len - (match + NGRAM));
    if (copy < static_cast<size_t>(NGRAM)) {
        return draft;
    }
    draft.assign(tokens.begin() + match + NGRAM, tokens.begin() + match + NGRAM + copy);
    return draft;
}

void batch_add(llama_batch & batch, llama_token token, llama_pos pos, bool logits) {
    const int i = batch.n_tokens++;
    batch.token[i] = token;
    batch.pos[i] = pos;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = logits;
}

bool generate(Cleaner & c, const std::vector<llama_token> & prompt, int n_predict_max, std::string & out) {
    c.out_tokens = c.drafted = c.accepted = 0;
    fit_threads_to_cores(c);
    llama_memory_clear(llama_get_memory(c.ctx), true);

    const int n_input = prompt.size();
    if (n_input < 2 || n_input > N_CTX) {
        return false;
    }
    llama_batch batch = llama_batch_init(N_CTX, 0, 1);
    for (int i = 0; i < n_input - 1; ++i) {
        batch_add(batch, prompt[i], i, false);
    }
    bool ok = llama_decode(c.ctx, batch) == 0;

    llama_token id_last = prompt.back();
    std::vector<llama_token> history(prompt.begin(), prompt.end() - 1);
    int n_past = n_input - 1;
    int n_predict = 0;
    bool eos = false;

    while (ok && !c.cancel.load()) {
        int n_draft_max = std::max(N_CTX - n_past - 2, 0);
        n_draft_max = std::min(n_draft_max, n_predict_max - n_predict - 1);
        n_draft_max = std::max(n_draft_max, 0);
        std::vector<llama_token> draft = draft_from_history(history, id_last);
        if (static_cast<int>(draft.size()) > n_draft_max) {
            draft.resize(n_draft_max);
        }

        batch.n_tokens = 0;
        batch_add(batch, id_last, n_past++, true);
        for (size_t i = 0; i < draft.size(); ++i) {
            batch_add(batch, draft[i], n_past + i, true);
        }
        if (llama_decode(c.ctx, batch) != 0) {
            ok = false;
            break;
        }

        // The target's own greedy choice at each position; stop at the first one the draft missed.
        std::vector<llama_token> ids;
        for (size_t i = 0; i <= draft.size(); ++i) {
            const llama_token sampled = llama_sampler_sample(c.greedy, c.ctx, i);
            ids.push_back(sampled);
            if (i == draft.size() || sampled != draft[i]) {
                break;
            }
        }

        n_past += ids.size() - 1;
        c.drafted += draft.size();
        c.accepted += ids.size() - 1;
        n_predict += ids.size();

        for (llama_token id : ids) {
            history.push_back(id_last);
            id_last = id;
            if (llama_vocab_is_eog(c.vocab, id_last)) {
                eos = true;
                break;
            }
            out += piece(c, id_last);
            c.out_tokens++;
        }

        llama_memory_seq_rm(llama_get_memory(c.ctx), 0, n_past, -1);
        if (n_predict > n_predict_max || eos) {
            break;
        }
    }
    llama_batch_free(batch);
    return ok && !c.cancel.load();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_ezhou_sttbutton_LlamaNative_load(JNIEnv * env, jobject, jstring path, jint threads) {
    llama_backend_init();
    const char * chars = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(chars, mp);
    env->ReleaseStringUTFChars(path, chars);
    if (model == nullptr) {
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = N_CTX;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.n_outputs_max = MGRAM + 16;
    cp.n_outputs_max_per_seq = MGRAM + 16;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto * c = new Cleaner();
    c->model = model;
    c->ctx = ctx;
    // Without a pool of its own, ggml starts and joins its worker threads on every llama_decode.
    c->wanted_threads = threads;
    fit_threads_to_cores(*c);
    c->vocab = llama_model_get_vocab(model);
    c->greedy = llama_sampler_init_greedy();
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jint JNICALL Java_com_ezhou_sttbutton_LlamaNative_countTokens(JNIEnv * env, jobject, jlong handle, jbyteArray text) {
    auto * c = reinterpret_cast<Cleaner *>(handle);
    return tokenize(*c, bytes_to_string(env, text), false).size();
}

// Returns the generated UTF-8 bytes, or null when cancelled or the prompt does not fit.
JNIEXPORT jbyteArray JNICALL Java_com_ezhou_sttbutton_LlamaNative_clean(JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint maxTokens) {
    auto * c = reinterpret_cast<Cleaner *>(handle);
    c->cancel.store(false);
    std::string out;
    if (!generate(*c, tokenize(*c, bytes_to_string(env, prompt), true), maxTokens, out)) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(out.size());
    env->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL Java_com_ezhou_sttbutton_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Cleaner *>(handle)->cancel.store(true);
}

JNIEXPORT jintArray JNICALL Java_com_ezhou_sttbutton_LlamaNative_lastStats(JNIEnv * env, jobject, jlong handle) {
    auto * c = reinterpret_cast<Cleaner *>(handle);
    const jint stats[4] = {c->out_tokens, c->drafted, c->accepted, c->pool_threads};
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, stats);
    return result;
}

JNIEXPORT void JNICALL Java_com_ezhou_sttbutton_LlamaNative_release(JNIEnv *, jobject, jlong handle) {
    auto * c = reinterpret_cast<Cleaner *>(handle);
    llama_sampler_free(c->greedy);
    llama_free(c->ctx);
    ggml_threadpool_free(c->pool);
    llama_model_free(c->model);
    delete c;
}

}  // extern "C"
