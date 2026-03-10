#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rwkv {

bool SampleToken(
    const std::vector<float> &logits,
    const std::vector<int32_t> &history,
    double temperature,
    int top_k,
    double top_p,
    double repeat_penalty,
    int32_t &token,
    std::string &error_message);

}  // namespace rwkv
