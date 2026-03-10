#pragma once

#include <string>
#include <vector>

#include "rwkv_weight_loader.h"

namespace rwkv {

struct InferenceOptions {
    int max_tokens = 256;
    double temperature = 0.7;
    double top_p = 0.9;
    int top_k = 40;
    double repeat_penalty = 1.10;
};

class EngineCore {
public:
    EngineCore() = default;
    ~EngineCore();

    bool LoadModel(const std::string &model_path, std::string &error_message);
    void UnloadModel();
    bool IsModelLoaded() const;
    bool StreamTokens(
        const std::string &prompt,
        const InferenceOptions &options,
        std::vector<std::string> &tokens,
        std::string &error_message);

private:
    ModelMapping mapping_;
    static bool IsUnsupportedModelPath(const std::string &model_path);
};
}  // namespace rwkv
