#include <jni.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "ggml-backend.h"

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_chat_templates_ptr chat_templates;
    double model_load_duration_ms = 0.0;
    int32_t max_tokens = 0;
    float temperature = 0.0f;
    float top_p = 0.95f;
    int32_t top_k = 40;
    uint32_t seed = 0;
    bool use_jinja_template = true;
    std::mutex mutex;
    std::atomic<uint64_t> generation_state {0};
    uint64_t next_generation_id = 1;

    ~Engine() {
        if (context != nullptr) {
            llama_free(context);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }
};

std::once_flag backend_once;

void silent_log_callback(enum ggml_log_level, const char *, void *) {
    // Prompts, outputs, and full model paths must not be logged by default.
}

void initialize_backend() {
    std::call_once(backend_once, [] {
        llama_log_set(silent_log_callback, nullptr);
#ifdef SMOOTH_CPU_VARIANTS
        Dl_info library_info {};
        if (dladdr(reinterpret_cast<const void *>(&initialize_backend), &library_info) != 0 &&
            library_info.dli_fname != nullptr) {
            std::string library_path(library_info.dli_fname);
            const size_t separator = library_path.find_last_of('/');
            if (separator != std::string::npos) {
                const std::string library_dir = library_path.substr(0, separator);
                ggml_backend_load_all_from_path(library_dir.c_str());
            }
        }
#endif
        llama_backend_init();
#ifdef SMOOTH_CPU_VARIANTS
        if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
            throw std::runtime_error("Optimized CPU backend variant could not be loaded");
        }
#endif
    });
}

void throw_native_failure(JNIEnv * env, const char * message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass exception_class = env->FindClass(
        "com/theemrock/smooth/sensus/inference/LocalInferenceException$NativeFailure"
    );
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

void append_utf8(std::string & output, uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

std::string jstring_to_utf8(JNIEnv * env, jstring value, const char * field_name) {
    if (value == nullptr) {
        throw std::runtime_error(std::string(field_name) + " is missing");
    }
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error(std::string(field_name) + " could not be read");
    }

    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);
    try {
        for (jsize index = 0; index < length; ++index) {
            uint32_t code_point = chars[index];
            if (code_point >= 0xd800 && code_point <= 0xdbff) {
                if (++index >= length || chars[index] < 0xdc00 || chars[index] > 0xdfff) {
                    throw std::runtime_error(std::string(field_name) + " contains invalid Unicode");
                }
                code_point = 0x10000 + ((code_point - 0xd800) << 10) + (chars[index] - 0xdc00);
            } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
                throw std::runtime_error(std::string(field_name) + " contains invalid Unicode");
            }
            if (code_point == 0) {
                throw std::runtime_error(std::string(field_name) + " contains a NUL character");
            }
            append_utf8(output, code_point);
        }
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return output;
}

size_t complete_utf8_prefix(const std::string & value) {
    size_t index = 0;
    while (index < value.size()) {
        const auto first = static_cast<uint8_t>(value[index]);
        size_t width;
        uint32_t code_point;
        if (first <= 0x7f) {
            width = 1;
            code_point = first;
        } else if (first >= 0xc2 && first <= 0xdf) {
            width = 2;
            code_point = first & 0x1f;
        } else if (first >= 0xe0 && first <= 0xef) {
            width = 3;
            code_point = first & 0x0f;
        } else if (first >= 0xf0 && first <= 0xf4) {
            width = 4;
            code_point = first & 0x07;
        } else {
            throw std::runtime_error("Generated output contains invalid UTF-8");
        }
        if (index + width > value.size()) {
            return index;
        }
        for (size_t offset = 1; offset < width; ++offset) {
            const auto next = static_cast<uint8_t>(value[index + offset]);
            if ((next & 0xc0) != 0x80) {
                throw std::runtime_error("Generated output contains invalid UTF-8");
            }
            code_point = (code_point << 6) | (next & 0x3f);
        }
        if ((width == 3 && code_point < 0x800) ||
            (width == 4 && code_point < 0x10000) ||
            (code_point >= 0xd800 && code_point <= 0xdfff) ||
            code_point > 0x10ffff) {
            throw std::runtime_error("Generated output contains invalid UTF-8");
        }
        index += width;
    }
    return index;
}

using ChatMessages = std::vector<std::pair<std::string, std::string>>;

std::string apply_chat_template(Engine & engine, const ChatMessages & messages, bool enable_thinking) {
    if (!engine.use_jinja_template) {
        const char * source = llama_model_chat_template(engine.model, nullptr);
        if (source == nullptr || source[0] == '\0') return messages.back().second;
        std::vector<llama_chat_message> legacy;
        for (const auto & message : messages) legacy.push_back({message.first.c_str(), message.second.c_str()});
        int32_t required = llama_chat_apply_template(source, legacy.data(), legacy.size(), true, nullptr, 0);
        if (required < 0) throw std::runtime_error("Model chat template could not be applied");
        std::vector<char> buffer(static_cast<size_t>(required) + 1);
        int32_t written = llama_chat_apply_template(
            source, legacy.data(), legacy.size(), true, buffer.data(), static_cast<int32_t>(buffer.size())
        );
        if (written < 0 || written > required) throw std::runtime_error("Model chat template output was invalid");
        return std::string(buffer.data(), static_cast<size_t>(written));
    }
    if (!engine.chat_templates) return messages.back().second;
    common_chat_templates_inputs inputs;
    for (const auto & message : messages) inputs.messages.push_back({message.first, message.second});
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = enable_thinking;
    return common_chat_templates_apply(engine.chat_templates.get(), inputs).prompt;
}

std::vector<llama_token> tokenize(
    const llama_vocab * vocab,
    const std::string & text,
    bool parse_special
) {
    int32_t required = llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        nullptr,
        0,
        true,
        parse_special
    );
    if (required == INT32_MIN) {
        throw std::runtime_error("Prompt tokenization overflowed");
    }
    required = required < 0 ? -required : required;
    if (required <= 0) {
        throw std::runtime_error("Prompt tokenization failed");
    }

    std::vector<llama_token> tokens(static_cast<size_t>(required));
    int32_t count = llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        parse_special
    );
    if (count < 0) {
        throw std::runtime_error("Prompt tokenization failed");
    }
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int32_t length = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<int32_t>(buffer.size()),
        0,
        true
    );
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true
        );
    }
    if (length < 0) {
        throw std::runtime_error("Generated token could not be decoded");
    }
    return std::string(buffer.data(), static_cast<size_t>(length));
}

std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> create_sampler(
    Engine & engine,
    const llama_vocab * vocab,
    const char * grammar
) {
    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()),
        llama_sampler_free
    );
    if (!sampler) {
        throw std::runtime_error("Sampler creation failed");
    }

    if (grammar != nullptr) {
        if (grammar[0] == '\0') {
            throw std::runtime_error("Grammar must not be empty");
        }
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(vocab, grammar, "root");
        if (grammar_sampler == nullptr) {
            throw std::runtime_error("Grammar is invalid");
        }
        llama_sampler_chain_add(sampler.get(), grammar_sampler);
    }

    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(engine.top_k));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(engine.top_p, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(engine.temperature));
    if (engine.temperature == 0.0f) {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(engine.seed));
    }
    return sampler;
}

struct GenerationOutcome {
    std::string text;
    int32_t prompt_tokens = 0;
    int32_t generated_tokens = 0;
    int32_t context_used = 0;
    int32_t context_max = 0;
    double prompt_ms = 0.0;
    double ttft_ms = 0.0;
    double generation_ms = 0.0;
    double total_ms = 0.0;
    const char * termination = "ERROR";
};

struct PendingJavaException {};

using TextChunkEmitter = std::function<void(const std::string &)>;

class ActiveGeneration {
public:
    explicit ActiveGeneration(Engine & engine)
        : engine_(engine),
          active_state_(engine.next_generation_id++ << 1),
          cancelled_state_(active_state_ | 1) {
        engine_.generation_state.store(active_state_, std::memory_order_release);
    }

    ActiveGeneration(const ActiveGeneration &) = delete;
    ActiveGeneration & operator=(const ActiveGeneration &) = delete;

    ~ActiveGeneration() {
        if (!finished_) {
            engine_.generation_state.store(0, std::memory_order_release);
        }
    }

    bool cancellation_requested() const {
        return engine_.generation_state.load(std::memory_order_acquire) == cancelled_state_;
    }

    bool finish() {
        uint64_t observed = active_state_;
        const bool completed_before_cancel = engine_.generation_state.compare_exchange_strong(
            observed,
            0,
            std::memory_order_acq_rel,
            std::memory_order_acquire
        );
        const bool cancelled = !completed_before_cancel && observed == cancelled_state_;
        if (!completed_before_cancel) {
            engine_.generation_state.store(0, std::memory_order_release);
        }
        finished_ = true;
        return cancelled;
    }

private:
    Engine & engine_;
    const uint64_t active_state_;
    const uint64_t cancelled_state_;
    bool finished_ = false;
};

bool request_generation_cancellation(Engine & engine) {
    uint64_t observed = engine.generation_state.load(std::memory_order_acquire);
    if (observed == 0) {
        return false;
    }
    if ((observed & 1) != 0) {
        return true;
    }

    const uint64_t cancelled = observed | 1;
    if (engine.generation_state.compare_exchange_strong(
            observed,
            cancelled,
            std::memory_order_acq_rel,
            std::memory_order_acquire
        )) {
        return true;
    }
    return observed == cancelled;
}

double elapsed_ms(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

GenerationOutcome generate(
    Engine & engine,
    const ChatMessages & messages,
    const char * grammar,
    bool enable_thinking,
    int32_t max_tokens,
    const TextChunkEmitter & emit_chunk = {}
) {
    const auto total_start = std::chrono::steady_clock::now();
    ActiveGeneration active_generation(engine);
    GenerationOutcome outcome;
    const llama_vocab * vocab = llama_model_get_vocab(engine.model);
    const char * chat_template = llama_model_chat_template(engine.model, nullptr);
    const bool has_chat_template = chat_template != nullptr && chat_template[0] != '\0';
    const std::string formatted_prompt = apply_chat_template(engine, messages, enable_thinking);
    std::vector<llama_token> prompt_tokens = tokenize(vocab, formatted_prompt, has_chat_template);
    outcome.prompt_tokens = static_cast<int32_t>(prompt_tokens.size());

    const uint32_t context_size = llama_n_ctx(engine.context);
    if (prompt_tokens.size() >= context_size) {
        throw std::runtime_error("Prompt does not fit in the configured context");
    }

    llama_memory_clear(llama_get_memory(engine.context), false);
    auto sampler = create_sampler(engine, vocab, grammar);

    size_t used_tokens = 0;
    bool cancelled = active_generation.cancellation_requested();
    const int32_t batch_size = static_cast<int32_t>(llama_n_batch(engine.context));
    const auto prompt_start = std::chrono::steady_clock::now();
    for (size_t offset = 0; offset < prompt_tokens.size() && !cancelled; offset += batch_size) {
        const int32_t count = static_cast<int32_t>(
            std::min(prompt_tokens.size() - offset, static_cast<size_t>(batch_size))
        );
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, count);
        if (llama_decode(engine.context, batch) != 0) {
            throw std::runtime_error("Prompt evaluation failed");
        }
        used_tokens += static_cast<size_t>(count);
        cancelled = active_generation.cancellation_requested();
    }
    outcome.prompt_ms = elapsed_ms(prompt_start);

    std::string output;
    std::string pending_utf8;
    const auto generation_start = std::chrono::steady_clock::now();
    outcome.termination = "MAX_TOKENS";
    for (int32_t generated = 0;
         !cancelled && generated < max_tokens && used_tokens < context_size;
         ++generated, ++used_tokens) {
        if (active_generation.cancellation_requested()) {
            cancelled = true;
            break;
        }
        const llama_token token = llama_sampler_sample(sampler.get(), engine.context, -1);
        if (outcome.generated_tokens == 0) outcome.ttft_ms = elapsed_ms(total_start);
        if (llama_vocab_is_eog(vocab, token)) {
            outcome.termination = "EOG";
            break;
        }

        const std::string piece = token_piece(vocab, token);
        output += piece;
        ++outcome.generated_tokens;
        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(engine.context, batch) != 0) {
            throw std::runtime_error("Token generation failed");
        }
        if (emit_chunk) {
            pending_utf8 += piece;
            const size_t complete_length = complete_utf8_prefix(pending_utf8);
            if (complete_length > 0) {
                emit_chunk(pending_utf8.substr(0, complete_length));
                pending_utf8.erase(0, complete_length);
            }
        }
    }
    const bool cancellation_at_finish = active_generation.finish();
    cancelled = cancelled || cancellation_at_finish;
    if (cancelled) {
        outcome.termination = "CANCELLED";
    } else if (used_tokens >= context_size) {
        outcome.termination = "CONTEXT_FULL";
    }
    outcome.context_used = static_cast<int32_t>(used_tokens);
    outcome.context_max = static_cast<int32_t>(context_size);
    outcome.generation_ms = elapsed_ms(generation_start);
    outcome.total_ms = elapsed_ms(total_start);
    output.resize(complete_utf8_prefix(output));
    outcome.text = std::move(output);
    return outcome;
}

jstring new_utf8_string(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) {
        return nullptr;
    }
    if (!value.empty()) {
        env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }

    jclass charset_class = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8_field = env->GetStaticFieldID(
        charset_class,
        "UTF_8",
        "Ljava/nio/charset/Charset;"
    );
    jobject utf8 = env->GetStaticObjectField(charset_class, utf8_field);
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
        string_class,
        "<init>",
        "([BLjava/nio/charset/Charset;)V"
    );
    return static_cast<jstring>(env->NewObject(string_class, constructor, bytes, utf8));
}

jobject new_inference_result(JNIEnv * env, const Engine & engine, const GenerationOutcome & outcome) {
    jclass reason_class = env->FindClass("com/theemrock/smooth/sensus/inference/InferenceTerminationReason");
    jfieldID reason_field = env->GetStaticFieldID(
        reason_class, outcome.termination,
        "Lcom/theemrock/smooth/sensus/inference/InferenceTerminationReason;"
    );
    jobject reason = env->GetStaticObjectField(reason_class, reason_field);
    jclass metrics_class = env->FindClass("com/theemrock/smooth/sensus/inference/InferenceMetrics");
    jmethodID metrics_ctor = env->GetMethodID(
        metrics_class, "<init>",
        "(DIDDIDDLcom/theemrock/smooth/sensus/inference/InferenceTerminationReason;ZII)V"
    );
    jobject metrics = env->NewObject(
        metrics_class, metrics_ctor,
        engine.model_load_duration_ms,
        outcome.prompt_tokens,
        outcome.prompt_ms,
        outcome.ttft_ms,
        outcome.generated_tokens,
        outcome.generation_ms,
        outcome.total_ms,
        reason,
        JNI_TRUE,
        outcome.context_used,
        outcome.context_max
    );
    jclass result_class = env->FindClass("com/theemrock/smooth/sensus/inference/InferenceResult");
    jmethodID result_ctor = env->GetMethodID(
        result_class, "<init>",
        "(Ljava/lang/String;Lcom/theemrock/smooth/sensus/inference/InferenceMetrics;)V"
    );
    return env->NewObject(result_class, result_ctor, new_utf8_string(env, outcome.text), metrics);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint context_size,
    jint thread_count,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jint seed,
    jboolean use_jinja_template
) {
    try {
        initialize_backend();
        const std::string path = jstring_to_utf8(env, model_path, "Model path");
        const auto load_start = std::chrono::steady_clock::now();

        auto engine = std::make_unique<Engine>();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.check_tensors = true;
        engine->model = llama_model_load_from_file(path.c_str(), model_params);
        if (engine->model == nullptr) {
            throw std::runtime_error("Model could not be loaded as GGUF");
        }
        engine->chat_templates = common_chat_templates_init(engine->model, "");

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = static_cast<uint32_t>(std::min(context_size, 512));
        context_params.n_ubatch = context_params.n_batch;
        context_params.n_threads = thread_count;
        context_params.n_threads_batch = thread_count;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        engine->context = llama_init_from_model(engine->model, context_params);
        if (engine->context == nullptr) {
            throw std::runtime_error("Model context could not be created");
        }
        engine->model_load_duration_ms = elapsed_ms(load_start);

        engine->max_tokens = max_tokens;
        engine->temperature = temperature;
        engine->top_p = top_p;
        engine->top_k = top_k;
        engine->seed = static_cast<uint32_t>(seed);
        engine->use_jinja_template = use_jinja_template == JNI_TRUE;
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & error) {
        throw_native_failure(env, error.what());
        return 0;
    } catch (...) {
        throw_native_failure(env, "Unknown native model initialization failure");
        return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeGenerate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring prompt,
    jstring grammar,
    jboolean enable_thinking,
    jint max_tokens
) {
    try {
        auto * engine = reinterpret_cast<Engine *>(handle);
        if (engine == nullptr) {
            throw std::runtime_error("Native engine handle is invalid");
        }
        const std::string prompt_text = jstring_to_utf8(env, prompt, "Prompt");
        const std::string grammar_text = grammar == nullptr
            ? std::string()
            : jstring_to_utf8(env, grammar, "Grammar");

        std::lock_guard<std::mutex> guard(engine->mutex);
        return new_inference_result(
            env, *engine,
            generate(
                *engine,
                ChatMessages{{"user", prompt_text}},
                grammar == nullptr ? nullptr : grammar_text.c_str(),
                enable_thinking == JNI_TRUE,
                max_tokens
            )
        );
    } catch (const std::exception & error) {
        throw_native_failure(env, error.what());
        return nullptr;
    } catch (...) {
        throw_native_failure(env, "Unknown native generation failure");
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeGenerateChat(
    JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean enable_thinking, jint max_tokens
) {
    try {
        auto * engine = reinterpret_cast<Engine *>(handle);
        if (engine == nullptr) throw std::runtime_error("Native engine handle is invalid");
        if (roles == nullptr || contents == nullptr || env->GetArrayLength(roles) != env->GetArrayLength(contents) || env->GetArrayLength(roles) == 0) {
            throw std::runtime_error("Chat messages are invalid");
        }
        if (max_tokens <= 0) throw std::runtime_error("maxTokens must be positive");
        ChatMessages messages;
        for (jsize index = 0; index < env->GetArrayLength(roles); ++index) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
            messages.emplace_back(jstring_to_utf8(env, role, "Role"), jstring_to_utf8(env, content, "Content"));
            env->DeleteLocalRef(role); env->DeleteLocalRef(content);
        }
        std::lock_guard<std::mutex> guard(engine->mutex);
        return new_inference_result(env, *engine, generate(*engine, messages, nullptr, enable_thinking == JNI_TRUE, max_tokens));
    } catch (const std::exception & error) {
        throw_native_failure(env, error.what()); return nullptr;
    } catch (...) {
        throw_native_failure(env, "Unknown native chat generation failure"); return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeGenerateChatStreaming(
    JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean enable_thinking, jint max_tokens, jobject callback
) {
    try {
        auto * engine = reinterpret_cast<Engine *>(handle);
        if (engine == nullptr) throw std::runtime_error("Native engine handle is invalid");
        if (roles == nullptr || contents == nullptr || env->GetArrayLength(roles) != env->GetArrayLength(contents) || env->GetArrayLength(roles) == 0) {
            throw std::runtime_error("Chat messages are invalid");
        }
        if (max_tokens <= 0) throw std::runtime_error("maxTokens must be positive");
        if (callback == nullptr) throw std::runtime_error("Text chunk callback is missing");

        ChatMessages messages;
        for (jsize index = 0; index < env->GetArrayLength(roles); ++index) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
            messages.emplace_back(jstring_to_utf8(env, role, "Role"), jstring_to_utf8(env, content, "Content"));
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
        }

        jclass callback_class = env->GetObjectClass(callback);
        if (callback_class == nullptr) throw PendingJavaException{};
        jmethodID on_text_chunk = env->GetMethodID(
            callback_class,
            "onTextChunk",
            "(Ljava/lang/String;)V"
        );
        env->DeleteLocalRef(callback_class);
        if (on_text_chunk == nullptr) throw PendingJavaException{};

        const TextChunkEmitter emit_chunk = [env, callback, on_text_chunk](const std::string & chunk) {
            if (env->PushLocalFrame(8) < 0) throw PendingJavaException{};
            jstring text = new_utf8_string(env, chunk);
            if (text == nullptr || env->ExceptionCheck()) {
                env->PopLocalFrame(nullptr);
                throw PendingJavaException{};
            }
            env->CallVoidMethod(callback, on_text_chunk, text);
            const bool callback_failed = env->ExceptionCheck();
            env->PopLocalFrame(nullptr);
            if (callback_failed) throw PendingJavaException{};
        };

        std::lock_guard<std::mutex> guard(engine->mutex);
        return new_inference_result(
            env,
            *engine,
            generate(
                *engine,
                messages,
                nullptr,
                enable_thinking == JNI_TRUE,
                max_tokens,
                emit_chunk
            )
        );
    } catch (const PendingJavaException &) {
        return nullptr;
    } catch (const std::exception & error) {
        throw_native_failure(env, error.what());
        return nullptr;
    } catch (...) {
        throw_native_failure(env, "Unknown native streaming generation failure");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeCancelGeneration(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    return request_generation_cancellation(*engine) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_theemrock_smooth_sensus_inference_JniLlamaCppNativeBridge_nativeRelease(
    JNIEnv *,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<Engine *>(handle);
}
