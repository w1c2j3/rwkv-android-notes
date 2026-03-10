#pragma once

#include <cstddef>
#include <string>

namespace rwkv {

struct ModelMapping {
    int fd = -1;
    std::size_t size = 0;
    void *addr = nullptr;
    std::string path;
    bool IsLoaded() const { return fd >= 0 && addr != nullptr && size > 0 && !path.empty(); }
};

bool LoadModelMapping(const std::string &model_path, ModelMapping &mapping, std::string &error_message);
void UnloadModelMapping(ModelMapping &mapping);

}  // namespace rwkv
