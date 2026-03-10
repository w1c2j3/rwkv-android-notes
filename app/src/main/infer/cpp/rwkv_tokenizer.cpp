#include "rwkv_tokenizer.h"

#include <algorithm>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>

namespace rwkv {
namespace {
struct TokenizerState {
    bool loaded = false;
    std::vector<std::string> id_to_token;
    std::unordered_map<std::string, int32_t> token_to_id;
    std::size_t max_token_len = 1;
};
TokenizerState g_state;
std::mutex g_mutex;
}

bool LoadTokenizerVocabulary(const std::string &vocab_path, std::string &error_message) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::ifstream in(vocab_path);
    if (!in.is_open()) {
        error_message = "TOKENIZER_VOCAB_OPEN_FAILED";
        return false;
    }
    TokenizerState next;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        std::size_t p1 = line.find(' ');
        std::size_t p2 = line.find(' ', p1 == std::string::npos ? p1 : p1 + 1);
        if (p1 == std::string::npos || p2 == std::string::npos) continue;
        int idx = std::stoi(line.substr(0, p1));
        std::string token = line.substr(p1 + 1, p2 - p1 - 1);
        if (token.size() >= 2 && token.front() == '\'' && token.back() == '\'') {
            token = token.substr(1, token.size() - 2);
        }
        int32_t id = static_cast<int32_t>(idx - 1);
        if (id < 0) continue;
        if (static_cast<std::size_t>(id) >= next.id_to_token.size()) {
            next.id_to_token.resize(static_cast<std::size_t>(id) + 1);
        }
        next.id_to_token[static_cast<std::size_t>(id)] = token;
        next.token_to_id[token] = id;
        next.max_token_len = std::max(next.max_token_len, token.size());
    }
    if (next.id_to_token.empty()) {
        error_message = "TOKENIZER_VOCAB_PARSE_FAILED";
        return false;
    }
    next.loaded = true;
    g_state = std::move(next);
    return true;
}

void ResetTokenizerVocabulary() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state = {};
}

bool EncodeUtf8Bytes(const std::string &text, std::vector<int32_t> &tokens, std::string &error_message) {
    if (text.empty()) {
        error_message = "TOKENIZER_INPUT_EMPTY";
        return false;
    }
    TokenizerState local;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        local = g_state;
    }
    tokens.clear();
    tokens.push_back(kTokenBos);
    if (local.loaded) {
        std::size_t pos = 0;
        while (pos < text.size()) {
            bool matched = false;
            const std::size_t max_len = std::min(local.max_token_len, text.size() - pos);
            for (std::size_t len = max_len; len >= 1; --len) {
                auto piece = text.substr(pos, len);
                auto it = local.token_to_id.find(piece);
                if (it != local.token_to_id.end()) {
                    tokens.push_back(it->second);
                    pos += len;
                    matched = true;
                    break;
                }
                if (len == 1) break;
            }
            if (!matched) {
                tokens.push_back(static_cast<int32_t>(static_cast<unsigned char>(text[pos++])));
            }
        }
    } else {
        for (unsigned char c : text) tokens.push_back(static_cast<int32_t>(c));
    }
    tokens.push_back(kTokenEos);
    return true;
}

bool DecodeUtf8Bytes(const std::vector<int32_t> &tokens, std::string &text, std::string &error_message) {
    TokenizerState local;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        local = g_state;
    }
    text.clear();
    for (int32_t token : tokens) {
        if (token == kTokenBos || token == kTokenEos) continue;
        if (local.loaded) {
            if (token < 0 || static_cast<std::size_t>(token) >= local.id_to_token.size()) {
                error_message = "TOKENIZER_TOKEN_OUT_OF_RANGE";
                return false;
            }
            text.append(local.id_to_token[static_cast<std::size_t>(token)]);
        } else {
            if (token < 0 || token > 255) {
                error_message = "TOKENIZER_TOKEN_OUT_OF_RANGE";
                return false;
            }
            text.push_back(static_cast<char>(token));
        }
    }
    return true;
}
}  // namespace rwkv
