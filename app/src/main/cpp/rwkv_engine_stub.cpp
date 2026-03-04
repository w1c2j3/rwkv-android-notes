#include <algorithm>
#include <fcntl.h>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
struct StubModelMapping {
    int fd = -1;
    size_t size = 0;
    void *addr = nullptr;
    bool loaded = false;
} g_model;
}  // namespace

bool rwkv_stub_load_model(const std::string &model_path, std::string &error_message) {
    if (g_model.loaded) return true;
    int fd = open(model_path.c_str(), O_RDONLY);
    if (fd < 0) {
        error_message = "failed to open model file";
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        error_message = "invalid model file size";
        return false;
    }
    void *addr = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        error_message = "mmap failed for model file";
        return false;
    }
    g_model.fd = fd;
    g_model.size = static_cast<size_t>(st.st_size);
    g_model.addr = addr;
    g_model.loaded = true;
    return true;
}

void rwkv_stub_unload_model() {
    if (!g_model.loaded) return;
    if (g_model.addr != nullptr && g_model.addr != MAP_FAILED) {
        munmap(g_model.addr, g_model.size);
    }
    if (g_model.fd >= 0) {
        close(g_model.fd);
    }
    g_model.fd = -1;
    g_model.size = 0;
    g_model.addr = nullptr;
    g_model.loaded = false;
}

std::vector<std::string> rwkv_stub_stream_tokens(const std::string &prompt) {
    std::string preview = prompt.substr(0, std::min<size_t>(prompt.size(), 160));
    std::string modelInfo = g_model.loaded ? "mmap_bytes=" + std::to_string(g_model.size) : "mmap=not_loaded";
    std::string markdown =
        "## Structured Notes\n"
        "- **Summary**: " + preview + "\n"
        "- **Model**: " + modelInfo + "\n"
        "- [ ] Review this draft\n"
        "- [ ] Add project-specific details\n";
    std::vector<std::string> tokens;
    std::stringstream ss(markdown);
    std::string part;
    while (std::getline(ss, part, ' ')) {
        tokens.push_back(part + " ");
    }
    return tokens;
}
