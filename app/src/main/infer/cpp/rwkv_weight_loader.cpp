#include "rwkv_weight_loader.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace rwkv {

bool LoadModelMapping(const std::string &model_path, ModelMapping &mapping, std::string &error_message) {
    if (model_path.empty()) {
        error_message = "MODEL_PATH_EMPTY";
        return false;
    }
    if (mapping.IsLoaded() && mapping.path == model_path) return true;
    UnloadModelMapping(mapping);
    int fd = open(model_path.c_str(), O_RDONLY);
    if (fd < 0) {
        error_message = "MODEL_OPEN_FAILED";
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        error_message = "MODEL_STAT_FAILED";
        return false;
    }
    void *addr = mmap(nullptr, static_cast<std::size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        error_message = "MODEL_MMAP_FAILED";
        return false;
    }
    mapping.fd = fd;
    mapping.size = static_cast<std::size_t>(st.st_size);
    mapping.addr = addr;
    mapping.path = model_path;
    return true;
}

void UnloadModelMapping(ModelMapping &mapping) {
    if (mapping.addr != nullptr && mapping.addr != MAP_FAILED && mapping.size > 0) {
        munmap(mapping.addr, mapping.size);
    }
    if (mapping.fd >= 0) close(mapping.fd);
    mapping = {};
}

}  // namespace rwkv
