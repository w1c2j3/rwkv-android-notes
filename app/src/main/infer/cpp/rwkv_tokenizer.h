#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rwkv {

constexpr int32_t kTokenBos = 256;
constexpr int32_t kTokenEos = 257;

bool LoadTokenizerVocabulary(const std::string &vocab_path, std::string &error_message);
void ResetTokenizerVocabulary();
bool EncodeUtf8Bytes(const std::string &text, std::vector<int32_t> &tokens, std::string &error_message);
bool DecodeUtf8Bytes(const std::vector<int32_t> &tokens, std::string &text, std::string &error_message);

}  // namespace rwkv
