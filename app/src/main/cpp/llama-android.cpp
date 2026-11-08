#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <atomic>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 0;  // watch: use all 4 A53 cores

constexpr int   DEFAULT_CONTEXT_SIZE    = 2048;  // watch-friendly KV cache cap (M0)
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static bool                               g_enable_thinking = true;

// 用户自定义停止序列：一旦累积输出命中其中任意字符串，立即终止生成。
static std::vector<std::string> g_stop_strings;
static std::string g_tail_text;   // 用于跨 token 匹配 stop string 的尾部缓冲

static bool match_stop_string(std::string & out) {
    // 从尾部（最近生成的部分）匹配，避免每 token 扫描全量输出。
    for (const auto & s : g_stop_strings) {
        if (s.empty()) continue;
        const std::string haystack = g_tail_text;
        const auto pos = haystack.rfind(s);
        if (pos != std::string::npos && pos + s.size() == haystack.size()) {
            // 命中且位于末尾：去掉命中的部分，返回 true 表示应停止。
            g_tail_text = haystack.substr(0, pos);
            out = s;
            return true;
        }
    }
    return false;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_load(JNIEnv *env, jobject, jstring jmodel_path, jboolean use_mmap, jboolean use_mlock) {
    llama_model_params model_params = llama_model_default_params();
    // 注意：此版本 llama.cpp 的 model_params 没有 use_mmap/use_mlock 字段
    // 这些功能在加载后通过其他方式控制

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);
    LOGi("%s: use_mmap=%s, use_mlock=%s (not supported in this llama.cpp version)", __func__, use_mmap ? "true" : "false", use_mlock ? "true" : "false");

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx, const int n_threads) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    const int threads = n_threads > 0 ? n_threads : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(
        float temp,
        float top_p,
        int32_t top_k,
        float presence_penalty,
        float frequency_penalty,
        int32_t reasoning_budget,
        const std::vector<llama_token> & budget_start,
        const std::vector<llama_token> & budget_end,
        const std::vector<llama_token> & budget_forced) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_p = top_p;
    sparams.top_k = top_k;
    sparams.penalty_present = presence_penalty;
    sparams.penalty_freq = frequency_penalty;

    // 思考(token)预算：限制模型在思考/推理块里的 token 数，超出即强制结束思考。
    // <0 表示不限制；>=0 时按 start/end 标记激活计数。
    sparams.reasoning_budget_tokens = reasoning_budget;
    sparams.reasoning_budget_start = budget_start;
    sparams.reasoning_budget_end = budget_end;
    sparams.reasoning_budget_forced = budget_forced;

    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_prepare(JNIEnv *env, jobject /*unused*/, jint n_threads, jint n_ctx, jfloat temp, jfloat top_p, jint top_k, jfloat presence_penalty, jfloat frequency_penalty, jboolean enable_thinking, jint thinking_budget, jstring j_start_tag, jstring j_end_tag, jobjectArray j_stop_strings) {
    auto *context = init_context(g_model, n_ctx, n_threads);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_enable_thinking = enable_thinking;

    // 解析用户自定义停止序列
    g_stop_strings.clear();
    if (j_stop_strings != nullptr) {
        const jsize n = env->GetArrayLength(j_stop_strings);
        for (jsize i = 0; i < n; ++i) {
            auto *str = (jstring) env->GetObjectArrayElement(j_stop_strings, i);
            if (str == nullptr) continue;
            const auto *cstr = env->GetStringUTFChars(str, nullptr);
            if (cstr && *cstr) g_stop_strings.emplace_back(cstr);
            if (cstr) env->ReleaseStringUTFChars(str, cstr);
            env->DeleteLocalRef(str);
        }
        LOGi("%s: %zu stop string(s) configured", __func__, g_stop_strings.size());
    }

    // 解析思考预算：把思考开始/结束标记 tokenize 成序列，预算耗尽时强制输出结束标记。
    std::vector<llama_token> budget_start, budget_end, budget_forced;
    if (thinking_budget >= 0) {
        const auto *start_tag = env->GetStringUTFChars(j_start_tag, nullptr);
        const auto *end_tag = env->GetStringUTFChars(j_end_tag, nullptr);
        budget_start = common_tokenize(g_context, std::string(start_tag), false, true);
        budget_end = common_tokenize(g_context, std::string(end_tag), false, true);
        budget_forced = budget_end;  // 预算耗尽时强制补齐结束标记，使模型退出思考
        LOGi("%s: thinking budget set to %d tokens (start=%s, end=%s)", __func__,
             thinking_budget, start_tag, end_tag);
        env->ReleaseStringUTFChars(j_start_tag, start_tag);
        env->ReleaseStringUTFChars(j_end_tag, end_tag);
    }

    g_sampler = new_sampler(temp, top_p, top_k, presence_penalty, frequency_penalty,
                            thinking_budget, budget_start, budget_end, budget_forced);
    LOGi("%s: sampler temp=%.2f, top_p=%.2f, top_k=%d, presence_penalty=%.2f, frequency_penalty=%.2f, enable_thinking=%s",
         __func__, temp, top_p, top_k, presence_penalty, frequency_penalty, g_enable_thinking ? "true" : "false");
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp, 0);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;

    // Use common_chat_format_single but override enable_thinking via kwargs if supported
    common_chat_templates_inputs inputs;
    // use_jinja=true 使 enable_thinking 真正进入 chat template（jinja 分支），
    // 从而控制模型是否输出 CoT 思考；use_jinja=false 的 legacy 分支会忽略该参数。
    inputs.use_jinja = true;
    inputs.enable_thinking = g_enable_thinking;

    std::string fmt_past_msg;
    if (!chat_msgs.empty()) {
        inputs.messages = chat_msgs;
        inputs.add_generation_prompt = false;
        fmt_past_msg = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    }

    std::ostringstream ss;
    if (role == ROLE_USER && !fmt_past_msg.empty() && fmt_past_msg.back() == '\n') {
        ss << "\n";
    }

    inputs.messages.push_back(new_msg);
    inputs.add_generation_prompt = (role == ROLE_USER);
    auto fmt_new_msg = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    // 防止 size_t 无符号下溢导致 substr 越界（std::out_of_range）。
    // 正常情况加一条消息后模板输出应变长；若反而变短，则退化为全量输出。
    if (fmt_new_msg.size() > fmt_past_msg.size()) {
        ss << fmt_new_msg.substr(fmt_past_msg.size(), fmt_new_msg.size() - fmt_past_msg.size());
    } else {
        ss << fmt_new_msg;
    }

    chat_msgs.push_back(new_msg);
    auto formatted = ss.str();
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * JNI: 允许在会话进行中动态切换 "是否使用 CoT 思考"。
 * 与 prepare 时通过 enableThinking 初设的 g_enable_thinking 解耦，
 * 以便 /think、/no_think 前缀可在任意时刻覆盖某一条消息的思考行为。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_setThinking(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enable_thinking) {
    g_enable_thinking = enable_thinking;
    LOGi("%s: g_enable_thinking = %s", __func__, enable_thinking ? "true" : "false");
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static std::atomic<bool> g_stop_generation{false};   // user-requested stop flag

// Authoritative timing (independent of dropped verbose logs).
static int64_t gen_t0_us;   // wall clock of the first decoded token
static int     gen_count;   // number of tokens decoded this turn

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    g_stop_generation = false;
    g_tail_text.clear();
    gen_t0_us = 0;
    gen_count = 0;
}

static void log_gen_stats(const char *reason) {
    if (gen_count <= 0 || gen_t0_us == 0) { return; }
    const double dt = double(ggml_time_us() - gen_t0_us) / 1e6;
    LOGi("GEN_STATS: %s | %d tokens in %.2f s = %.2f tok/s (decode)",
         reason, gen_count, dt, dt > 0 ? gen_count / dt : 0.0);
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches (prefill) — time it for pp tok/s.
    const int64_t pp_t0 = ggml_time_us();
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }
    const double pp_dt = double(ggml_time_us() - pp_t0) / 1e6;
    LOGi("GEN_STATS: prefill | %d tokens in %.2f s = %.2f tok/s (pp)",
         user_prompt_size, pp_dt, pp_dt > 0 ? user_prompt_size / pp_dt : 0.0);

    // Update position
    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

// 检测混元(Hunyuan)系列模型及类似中文大模型的特殊 token
// 格式: <｜hy_place▁holder▁no▁数字｜> 或 <｜hy_▁holder▁no▁数字｜> 等变体
// 这些 token 是特殊控制 token，不应出现在正常输出中
static bool is_hunyuan_eos(const std::string & piece) {
    // 检查是否包含混元特殊 token 特征
    // 模式: <｜..._..._no▁数字｜> 或包含 hy_place/hy_ 的占位符 token
    if (piece.find("hy_place") != std::string::npos ||
        piece.find("<｜hy_") != std::string::npos) {
        return true;
    }
    return false;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Check user-requested stop
    if (g_stop_generation) {
        LOGw("%s: STOP: user interrupted", __func__);
        log_gen_stats("user-stop");
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // Infinite text generation via context shifting
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        log_gen_stats("max-tokens");
        return nullptr;
    }

    // Start the decode timer on the first generated token.
    if (gen_count == 0) { gen_t0_us = ggml_time_us(); }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;
    gen_count++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        log_gen_stats("eog");
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);

    // 检测混元(Hunyuan)系列模型 EOS token 并停止生成
    if (is_hunyuan_eos(new_token_chars)) {
        LOGd("id: %d,\tIS HUNYUAN EOS: `%s`\nSTOP.", new_token_id, new_token_chars.c_str());
        log_gen_stats("hunyuan-eos");
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    cached_token_chars += new_token_chars;

    // 检查累积缓存中是否也包含混元 EOS token（处理跨 token 分割的情况）
    if (is_hunyuan_eos(cached_token_chars)) {
        LOGd("id: %d,\tIS HUNYUAN EOS in cache: `%s`\nSTOP.", new_token_id, cached_token_chars.c_str());
        log_gen_stats("hunyuan-eos-cache");
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // 检查用户自定义停止序列：一旦命中，输出中剔除 stop string 并终止。
    g_tail_text += new_token_chars;
    if (g_tail_text.size() > 256) {
        g_tail_text = g_tail_text.substr(g_tail_text.size() - 256);
    }
    std::string matched;
    if (match_stop_string(matched)) {
        LOGd("%s: hit stop string `%s`\nSTOP.", __func__, matched.c_str());
        // 从 assistant_ss 末尾剔除命中的 stop string，确保返回文本不含停止序列。
        const std::string cur = assistant_ss.str();
        const size_t base = cur.size() > matched.size() ? cur.size() - matched.size() : 0;
        // 仅当尾部确实以 stop string 结尾时才剔除
        if (cur.rfind(matched) == base) {
            std::string trimmed = cur.substr(0, base);
            assistant_ss.str("");
            assistant_ss << trimmed;
        }
        cached_token_chars.clear();
        log_gen_stats("stop-string");
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_stopGeneration(JNIEnv * /*unused*/, jobject /*unused*/) {
    g_stop_generation = true;
    LOGi("%s: stop flag set", __func__);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states (only if context is valid)
    if (g_context) {
        reset_long_term_states();
        reset_short_term_states();
    }

    // Free up resources (null-safe)
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    if (g_batch.n_tokens > 0 || g_batch.token) {
        llama_batch_free(g_batch);
        g_batch = {};
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_ueiol_liteai_llm_LlamaBridge_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}