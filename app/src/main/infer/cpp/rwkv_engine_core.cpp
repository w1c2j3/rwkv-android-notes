#include "rwkv_engine_core.h"

#include <algorithm>
#include <cctype>
#include <sys/stat.h>

#include "rwkv_real_backend.h"
#include "rwkv_sampler.h"
#include "rwkv_tokenizer.h"

namespace rwkv {
EngineCore::~EngineCore() {
    UnloadModel();
}

bool EngineCore::LoadModel(const std::string &model_path, std::string &error_message) {
    if (model_path.empty()) {
        error_message = "MODEL_PATH_EMPTY";
        return false;
    }
    if (IsUnsupportedModelPath(model_path)) {
        error_message = "UNSUPPORTED_MODEL_FORMAT_PTH";
        return false;
    }
    if (!LoadModelMapping(model_path, mapping_, error_message)) {
        return false;
    }
    if (!RealBackendLoadModel(model_path, error_message)) {
        UnloadModelMapping(mapping_);
        return false;
    }
    ResetTokenizerVocabulary();
    const std::size_t slash = model_path.find_last_of("/\\");
    const std::string dir = slash == std::string::npos ? "." : model_path.substr(0, slash);
    const std::string vocab_path = dir + "/rwkv_vocab_v20230424.txt";
    struct stat st {};
    if (stat(vocab_path.c_str(), &st) == 0) {
        std::string vocab_error;
        if (!LoadTokenizerVocabulary(vocab_path, vocab_error)) {
            error_message = vocab_error;
            UnloadModel();
            return false;
        }
    }
    return true;
}

void EngineCore::UnloadModel() {
    RealBackendUnloadModel();
    UnloadModelMapping(mapping_);
}

bool EngineCore::IsModelLoaded() const {
    return RealBackendIsLoaded();
}

bool EngineCore::IsUnsupportedModelPath(const std::string &model_path) {
    const std::string suffix = ".pth";
    if (model_path.size() < suffix.size()) return false;
    const std::size_t offset = model_path.size() - suffix.size();
    for (std::size_t i = 0; i < suffix.size(); ++i) {
        if (static_cast<char>(std::tolower(static_cast<unsigned char>(model_path[offset + i]))) != suffix[i]) {
            return false;
        }
    }
    return true;
}

bool EngineCore::StreamTokens(
    const std::string &prompt,
    const InferenceOptions &options,
    std::vector<std::string> &tokens,
    std::string &error_message) {
    if (!IsModelLoaded()) {
        error_message = "MODEL_NOT_LOADED";
        return false;
    }
    if (options.max_tokens <= 0) {
        error_message = "REQUEST_MAX_TOKENS_INVALID";
        return false;
    }
    if (options.temperature < 0.0) {
        error_message = "REQUEST_TEMPERATURE_INVALID";
        return false;
    }
    if (options.top_p < 0.0 || options.top_p > 1.0) {
        error_message = "REQUEST_TOP_P_INVALID";
        return false;
    }
    if (options.top_k <= 0) {
        error_message = "REQUEST_TOP_K_INVALID";
        return false;
    }
    if (options.repeat_penalty < 1.0) {
        error_message = "REQUEST_REPEAT_PENALTY_INVALID";
        return false;
    }

    std::vector<int32_t> history;
    if (!EncodeUtf8Bytes(prompt, history, error_message)) return false;
    tokens.clear();
    tokens.reserve(static_cast<std::size_t>(options.max_tokens));
    for (int step = 0; step < options.max_tokens; ++step) {
        std::vector<float> logits;
        if (!RealBackendBuildLogits(history, logits, error_message)) return false;
        int32_t next = 0;
        if (!SampleToken(
                logits,
                history,
                options.temperature,
                std::min(options.top_k, static_cast<int>(logits.size())),
                options.top_p,
                options.repeat_penalty,
                next,
                error_message)) {
            return false;
        }
        history.push_back(next);
        std::string piece;
        if (!DecodeUtf8Bytes({next}, piece, error_message)) return false;
        tokens.push_back(piece);
    }
    return true;
}
}  // namespace rwkv
