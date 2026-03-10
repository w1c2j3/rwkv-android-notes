#include "rwkv_sampler.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace rwkv {
namespace {
double DeterministicUnitRand(const std::vector<int32_t> &history, std::size_t salt) {
    uint64_t x = 1469598103934665603ull ^ static_cast<uint64_t>(salt);
    for (int32_t t : history) {
        x ^= static_cast<uint64_t>(t + 0x9e3779b9);
        x *= 1099511628211ull;
        x ^= (x >> 13);
    }
    return static_cast<double>(x % 1000000ull) / 1000000.0;
}
}

bool SampleToken(
    const std::vector<float> &logits,
    const std::vector<int32_t> &history,
    double temperature,
    int top_k,
    double top_p,
    double repeat_penalty,
    int32_t &token,
    std::string &error_message) {
    if (logits.empty()) {
        error_message = "SAMPLER_INVALID_LOGITS";
        return false;
    }
    if (temperature < 0.0) {
        error_message = "REQUEST_TEMPERATURE_INVALID";
        return false;
    }
    if (top_p < 0.0 || top_p > 1.0) {
        error_message = "REQUEST_TOP_P_INVALID";
        return false;
    }
    if (top_k <= 0 || top_k > static_cast<int>(logits.size())) {
        error_message = "REQUEST_TOP_K_INVALID";
        return false;
    }
    if (repeat_penalty < 1.0) {
        error_message = "REQUEST_REPEAT_PENALTY_INVALID";
        return false;
    }
    std::vector<float> adjusted = logits;
    const std::size_t recent = std::min<std::size_t>(history.size(), 128);
    for (std::size_t i = history.size() - recent; i < history.size() && !history.empty(); ++i) {
        const int32_t t = history[i];
        if (t < 0 || t >= static_cast<int32_t>(adjusted.size())) continue;
        if (adjusted[t] > 0.0f) adjusted[t] /= static_cast<float>(repeat_penalty);
        else adjusted[t] *= static_cast<float>(repeat_penalty);
    }

    std::vector<int> idx(adjusted.size());
    std::iota(idx.begin(), idx.end(), 0);
    std::sort(idx.begin(), idx.end(), [&](int a, int b) { return adjusted[a] > adjusted[b]; });
    idx.resize(static_cast<std::size_t>(top_k));

    const double t = std::max(temperature, 1e-6);
    double max_logit = -1e30;
    for (int i : idx) max_logit = std::max(max_logit, static_cast<double>(adjusted[i]));
    double z = 0.0;
    std::vector<double> probs(adjusted.size(), 0.0);
    for (int i : idx) {
        probs[i] = std::exp((static_cast<double>(adjusted[i]) - max_logit) / t);
        z += probs[i];
    }
    if (z <= 0.0) {
        error_message = "SAMPLER_INVALID_LOGITS";
        return false;
    }
    for (double &v : probs) v /= z;

    std::vector<int> nucleus;
    double cumulative = 0.0;
    for (int i : idx) {
        cumulative += probs[i];
        nucleus.push_back(i);
        if (cumulative >= top_p) break;
    }
    if (nucleus.empty()) {
        error_message = "SAMPLER_INVALID_LOGITS";
        return false;
    }

    const double r = DeterministicUnitRand(history, nucleus.size());
    double acc = 0.0;
    for (int i : nucleus) {
        acc += probs[i];
        if (r <= acc) {
            token = static_cast<int32_t>(i);
            return true;
        }
    }
    token = static_cast<int32_t>(nucleus.back());
    return true;
}
}  // namespace rwkv
