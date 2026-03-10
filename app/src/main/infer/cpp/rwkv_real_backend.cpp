#include "rwkv_real_backend.h"

#include <algorithm>
#include <mutex>
#include <thread>

#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
#include "rwkv.h"
#endif

namespace rwkv {
namespace {
#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
std::mutex g_backend_mutex;
rwkv_context *g_ctx = nullptr;
uint32_t ResolveThreadCount() {
    const unsigned int hc = std::thread::hardware_concurrency();
    if (hc == 0) return 1;
    return std::max(1u, std::min(hc, 8u));
}
#endif
}

bool RealBackendLoadModel(const std::string &model_path, std::string &error_message) {
#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_ctx != nullptr) {
        rwkv_free(g_ctx);
        g_ctx = nullptr;
    }
    rwkv_set_print_errors(nullptr, false);
    g_ctx = rwkv_init_from_file(model_path.c_str(), ResolveThreadCount(), 0);
    if (g_ctx == nullptr) {
        error_message = "REAL_RWKV_LOAD_MODEL_FAILED";
        return false;
    }
    rwkv_set_print_errors(g_ctx, false);
    return true;
#else
    (void)model_path;
    error_message = "REAL_RWKV_SOURCE_MISSING";
    return false;
#endif
}

void RealBackendUnloadModel() {
#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_ctx != nullptr) {
        rwkv_free(g_ctx);
        g_ctx = nullptr;
    }
#endif
}

bool RealBackendIsLoaded() {
#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    return g_ctx != nullptr;
#else
    return false;
#endif
}

bool RealBackendBuildLogits(
    const std::vector<int32_t> &history,
    std::vector<float> &logits,
    std::string &error_message) {
#if defined(RWKV_REAL_BACKEND_AVAILABLE) && RWKV_REAL_BACKEND_AVAILABLE
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_ctx == nullptr) {
        error_message = "MODEL_NOT_LOADED";
        return false;
    }
    const std::size_t state_len = rwkv_get_state_len(g_ctx);
    const std::size_t logits_len = rwkv_get_logits_len(g_ctx);
    if (state_len == 0 || logits_len == 0) {
        error_message = "REAL_RWKV_MODEL_INVALID";
        return false;
    }
    std::vector<float> state_a(state_len, 0.0f);
    std::vector<float> state_b(state_len, 0.0f);
    rwkv_init_state(g_ctx, state_a.data());
    logits.assign(logits_len, 0.0f);
    bool has_token = false;
    for (int32_t token : history) {
        if (token < 0 || static_cast<std::size_t>(token) >= logits_len) continue;
        if (!rwkv_eval(g_ctx, static_cast<uint32_t>(token), state_a.data(), state_b.data(), logits.data())) {
            error_message = "REAL_RWKV_EVAL_FAILED";
            return false;
        }
        has_token = true;
        state_a.swap(state_b);
    }
    if (!has_token) {
        error_message = "TOKENIZER_TOKEN_OUT_OF_RANGE";
        return false;
    }
    return true;
#else
    (void)history;
    (void)logits;
    error_message = "REAL_RWKV_SOURCE_MISSING";
    return false;
#endif
}
}  // namespace rwkv
