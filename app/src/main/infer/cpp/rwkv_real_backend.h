#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rwkv {

bool RealBackendLoadModel(const std::string &model_path, std::string &error_message);
void RealBackendUnloadModel();
bool RealBackendIsLoaded();
bool RealBackendBuildLogits(
    const std::vector<int32_t> &history,
    std::vector<float> &logits,
    std::string &error_message);

}  // namespace rwkv
