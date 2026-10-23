// The JNI bridge between the app and llama.cpp: load a GGUF model, answer a conversation piece by
// piece, free it. The prompt shared with the previous turn stays in the cache, so each message
// only costs its own tokens. Called from one thread at a time (the engine service's).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Session {
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
    std::vector<llama_token> cached;
    int n_ctx;
    int n_batch;
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "llama", "%s", text);
}

/** Length of the longest prefix of [s] made of whole UTF-8 characters. */
size_t utf8_complete(const std::string &s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        auto c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + n > s.size()) break;
        i += n;
        last = i;
    }
    return last;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/** Strings from Java come through as UTF-16: rebuild real UTF-8 (emojis included). */
std::string jstring_to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, get_bytes, charset));
    jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    tokens.resize(static_cast<size_t>(std::max(n, 0)));
    // Templates that write the BOS themselves would get it twice.
    llama_token bos = llama_vocab_bos(vocab);
    if (tokens.size() > 1 && tokens[0] == bos && tokens[1] == bos) tokens.erase(tokens.begin());
    return tokens;
}

/** The conversation in the model's chat format; [answer] opens her turn at the end. */
std::string apply_template(const llama_model *model, const std::vector<llama_chat_message> &messages, bool answer) {
    const char *model_template = llama_model_chat_template(model, nullptr);
    size_t total = 0;
    for (const auto &m : messages) total += strlen(m.content) + 64;
    for (const char *tmpl : {model_template, "chatml"}) {
        if (tmpl == nullptr) continue;
        std::vector<char> buf(total * 2 + 1024);
        int n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), answer, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int>(buf.size())) {
            buf.resize(static_cast<size_t>(n));
            n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), answer, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n >= 0) return {buf.data(), static_cast<size_t>(n)};
    }
    return {};
}

/** [system], then [texts] said by the user when [roles] is true, by her otherwise. */
std::string read_prompt(JNIEnv *env, const Session *session, jstring jsystem, jbooleanArray jroles, jobjectArray jtexts, bool answer) {
    std::vector<std::string> contents;
    std::vector<const char *> roles;
    std::string system = jstring_to_utf8(env, jsystem);
    if (!system.empty()) {
        contents.push_back(system);
        roles.push_back("system");
    }
    jsize count = env->GetArrayLength(jtexts);
    std::vector<jboolean> from_user(static_cast<size_t>(count), JNI_TRUE);
    if (count > 0) env->GetBooleanArrayRegion(jroles, 0, count, from_user.data());
    for (jsize i = 0; i < count; i++) {
        auto text = static_cast<jstring>(env->GetObjectArrayElement(jtexts, i));
        contents.push_back(jstring_to_utf8(env, text));
        env->DeleteLocalRef(text);
        roles.push_back(from_user[static_cast<size_t>(i)] ? "user" : "assistant");
    }
    std::vector<llama_chat_message> messages;
    for (size_t i = 0; i < contents.size(); i++) messages.push_back({roles[i], contents[i].c_str()});
    return apply_template(session->model, messages, answer);
}

/**
 * Brings the cache to [tokens]: what it already holds of them stays, the rest is decoded. The last
 * token is always decoded again, for the logits of what comes next. False when decoding fails.
 */
bool feed(Session *session, const std::vector<llama_token> &tokens) {
    llama_memory_t memory = llama_get_memory(session->ctx);
    size_t keep = 0;
    while (keep < session->cached.size() && keep < tokens.size() && session->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--;
    if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(keep), -1)) {
        // Some caches (sliding windows) cannot be cut in the middle: start over.
        llama_memory_clear(memory, true);
        keep = 0;
    }
    session->cached.resize(keep);
    for (size_t i = keep; i < tokens.size(); i += static_cast<size_t>(session->n_batch)) {
        auto n = static_cast<int32_t>(std::min(tokens.size() - i, static_cast<size_t>(session->n_batch)));
        if (llama_decode(session->ctx, llama_batch_get_one(const_cast<llama_token *>(tokens.data()) + i, n)) != 0) {
            llama_memory_clear(memory, true);
            session->cached.clear();
            return false;
        }
        session->cached.insert(session->cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(i) + n);
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_netrunner_dates_engine_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint threads, jint n_ctx) {
    static bool initialized = false;
    if (!initialized) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        initialized = true;
    }
    std::string path = jstring_to_std(env, jpath);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) return 0;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(n_ctx);
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *session = new Session{model, ctx, llama_model_get_vocab(model), {}, n_ctx, 512};
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_netrunner_dates_engine_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

/**
 * Answers the conversation ([system], then [texts] said by the user when [roles] is true, by her
 * otherwise, the last one being the user's). Each piece of the answer goes to callback.onBytes
 * (UTF-8), which returns false to stop. Returns false when the prompt does not fit or decoding fails.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_netrunner_dates_engine_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jstring jsystem, jbooleanArray jroles,
                                                    jobjectArray jtexts, jfloat temperature, jint max_tokens, jobject callback) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return JNI_FALSE;

    std::string prompt = read_prompt(env, session, jsystem, jroles, jtexts, true);
    if (prompt.empty()) return JNI_FALSE;
    std::vector<llama_token> tokens = tokenize(session->vocab, prompt);
    if (tokens.empty() || static_cast<int>(tokens.size()) + 64 > session->n_ctx) return JNI_FALSE;
    if (!feed(session, tokens)) return JNI_FALSE;
    llama_memory_t memory = llama_get_memory(session->ctx);

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(256, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_bytes = env->GetMethodID(callback_class, "onBytes", "([B)Z");
    std::string pending;
    auto emit = [&](size_t length) -> bool {
        if (length == 0) return true;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(pending.data()));
        jboolean go_on = env->CallBooleanMethod(callback, on_bytes, bytes);
        env->DeleteLocalRef(bytes);
        pending.erase(0, length);
        return go_on == JNI_TRUE && !env->ExceptionCheck();
    };

    bool ok = true;
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        char piece[256];
        int n = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, static_cast<size_t>(n));
        if (!emit(utf8_complete(pending))) break;
        if (static_cast<int>(session->cached.size()) + 1 >= session->n_ctx) break;
        if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) {
            ok = false;
            break;
        }
        session->cached.push_back(token);
    }
    emit(utf8_complete(pending));
    llama_sampler_free(sampler);
    env->DeleteLocalRef(callback_class);
    if (!ok) {
        llama_memory_clear(memory, true);
        session->cached.clear();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Reads the conversation ahead, without answering: the instructions are the longest part of the
 * prompt, and decoding them while the runner types makes the first answer come much sooner.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_netrunner_dates_engine_LlamaNative_prefill(JNIEnv *env, jclass, jlong handle, jstring jsystem, jbooleanArray jroles, jobjectArray jtexts) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return JNI_FALSE;
    std::string prompt = read_prompt(env, session, jsystem, jroles, jtexts, false);
    if (prompt.empty()) return JNI_FALSE;
    std::vector<llama_token> tokens = tokenize(session->vocab, prompt);
    if (tokens.empty() || static_cast<int>(tokens.size()) + 64 > session->n_ctx) return JNI_FALSE;
    return feed(session, tokens) ? JNI_TRUE : JNI_FALSE;
}
