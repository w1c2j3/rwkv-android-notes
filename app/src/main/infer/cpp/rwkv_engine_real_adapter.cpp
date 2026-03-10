#include "rwkv_engine_core.h"

#include <string>
#include <vector>

namespace {
rwkv::EngineCore g_core;
}

bool rwkv_engine_load_model(const std::string &model_path, std::string &error_message) {
    return g_core.LoadModel(model_path, error_message);
}

void rwkv_engine_unload_model() {
    g_core.UnloadModel();
}

bool rwkv_engine_stream_tokens(
    const std::string &prompt,
    int max_tokens,
    double temperature,
    double top_p,
    int top_k,
    double repeat_penalty,
    std::vector<std::string> &tokens,
    std::string &error_message) {
    rwkv::InferenceOptions options;
    options.max_tokens = max_tokens;
    options.temperature = temperature;
    options.top_p = top_p;
    options.top_k = top_k;
    options.repeat_penalty = repeat_penalty;
    return g_core.StreamTokens(prompt, options, tokens, error_message);
}

void rwkv_engine_set_equation_params(
    double h_decay,
    double x_mix,
    double o_mix,
    double att_base_decay,
    double att_decay_scale,
    int window_size,
    int proj_fan_in) {
    (void)h_decay;
    (void)x_mix;
    (void)o_mix;
    (void)att_base_decay;
    (void)att_decay_scale;
    (void)window_size;
    (void)proj_fan_in;
}
