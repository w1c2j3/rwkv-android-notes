#include <jni.h>

#include <chrono>
#include <cctype>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

bool rwkv_engine_load_model(const std::string &model_path, std::string &error_message);
void rwkv_engine_unload_model();
bool rwkv_engine_stream_tokens(
    const std::string &prompt,
    int max_tokens,
    double temperature,
    double top_p,
    int top_k,
    double repeat_penalty,
    std::vector<std::string> &tokens,
    std::string &error_message);
void rwkv_engine_set_equation_params(
    double h_decay,
    double x_mix,
    double o_mix,
    double att_base_decay,
    double att_decay_scale,
    int window_size,
    int proj_fan_in);

namespace {
std::mutex g_mutex;
std::unordered_map<long, bool> g_cancel_flags;
long g_next_handle = 1;
int g_active_engine_count = 0;
std::string g_last_error_json;

const char *kUtf8Replacement = "\xEF\xBF\xBD";

std::string BuildError(const std::string &code, const std::string &message);

void StoreLastErrorLocked(const std::string &code, const std::string &message) {
    g_last_error_json = BuildError(code, message);
}

void ClearLastErrorLocked() {
    g_last_error_json.clear();
}

std::string DescribeErrorCode(const std::string &code) {
    if (code == "ENGINE_INIT_FAILED") return "native engine init failed";
    if (code == "MODEL_PATH_EMPTY") return "model path is empty";
    if (code == "MODEL_OPEN_FAILED") return "failed to open model file";
    if (code == "MODEL_STAT_FAILED") return "failed to stat model file";
    if (code == "MODEL_MMAP_FAILED") return "failed to mmap model file";
    if (code == "UNSUPPORTED_MODEL_FORMAT_PTH") return "rwkv.cpp runtime format required, .pth is not supported";
    if (code == "REAL_RWKV_SOURCE_MISSING") return "real rwkv backend source is not wired into this build";
    if (code == "REAL_RWKV_LOAD_MODEL_FAILED") return "rwkv backend failed to load model";
    if (code == "REAL_RWKV_MODEL_INVALID") return "rwkv backend reported invalid model metadata";
    if (code == "REAL_RWKV_EVAL_FAILED") return "rwkv backend failed during eval";
    if (code == "TOKENIZER_VOCAB_OPEN_FAILED") return "failed to open tokenizer vocab";
    if (code == "TOKENIZER_VOCAB_PARSE_FAILED") return "failed to parse tokenizer vocab";
    return "native operation failed";
}

std::string EscapeJson(const std::string &input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (unsigned char c : input) {
        switch (c) {
            case '\"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    std::ostringstream escaped;
                    escaped << "\\u"
                            << std::hex
                            << std::setw(4)
                            << std::setfill('0')
                            << static_cast<int>(c);
                    out += escaped.str();
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return out;
}

void SkipWs(const std::string &text, size_t &idx) {
    while (idx < text.size() && std::isspace(static_cast<unsigned char>(text[idx]))) ++idx;
}

bool ParseNumber(const std::string &text, size_t &idx) {
    size_t start = idx;
    if (idx < text.size() && (text[idx] == '-' || text[idx] == '+')) ++idx;
    while (idx < text.size() && std::isdigit(static_cast<unsigned char>(text[idx]))) ++idx;
    if (idx < text.size() && text[idx] == '.') {
        ++idx;
        while (idx < text.size() && std::isdigit(static_cast<unsigned char>(text[idx]))) ++idx;
    }
    return idx > start;
}

bool ExtractString(const std::string &json_text, const std::string &key, std::string &value) {
    const std::string marker = "\"" + key + "\"";
    size_t pos = json_text.find(marker);
    if (pos == std::string::npos) return false;
    pos = json_text.find(':', pos + marker.size());
    if (pos == std::string::npos) return false;
    ++pos;
    SkipWs(json_text, pos);
    if (pos >= json_text.size() || json_text[pos] != '\"') return false;
    ++pos;
    std::string out;
    while (pos < json_text.size()) {
        char c = json_text[pos++];
        if (c == '\"') {
            value = out;
            return true;
        }
        if (c == '\\' && pos < json_text.size()) {
            out.push_back(json_text[pos++]);
        } else {
            out.push_back(c);
        }
    }
    return false;
}

bool ExtractNumber(const std::string &json_text, const std::string &key, double &value) {
    const std::string marker = "\"" + key + "\"";
    size_t pos = json_text.find(marker);
    if (pos == std::string::npos) return false;
    pos = json_text.find(':', pos + marker.size());
    if (pos == std::string::npos) return false;
    ++pos;
    SkipWs(json_text, pos);
    size_t start = pos;
    if (!ParseNumber(json_text, pos)) return false;
    try {
        value = std::stod(json_text.substr(start, pos - start));
        return true;
    } catch (...) {
        return false;
    }
}

std::string BuildError(const std::string &code, const std::string &message) {
    return "{\"ok\":false,\"error\":{\"code\":\"" + EscapeJson(code) + "\",\"message\":\"" + EscapeJson(message) + "\"}}";
}

std::size_t Utf8SequenceLength(unsigned char lead) {
    if ((lead & 0x80u) == 0u) return 1;
    if (lead >= 0xC2u && lead <= 0xDFu) return 2;
    if (lead >= 0xE0u && lead <= 0xEFu) return 3;
    if (lead >= 0xF0u && lead <= 0xF4u) return 4;
    return 0;
}

bool IsContinuationByte(unsigned char byte) {
    return (byte & 0xC0u) == 0x80u;
}

bool IsValidUtf8Sequence(const std::string &text, std::size_t pos, std::size_t len) {
    const unsigned char lead = static_cast<unsigned char>(text[pos]);
    if (len == 1) return lead <= 0x7Fu;
    if (pos + len > text.size()) return false;
    const unsigned char b1 = static_cast<unsigned char>(text[pos + 1]);
    if (!IsContinuationByte(b1)) return false;
    if (len == 2) return true;
    const unsigned char b2 = static_cast<unsigned char>(text[pos + 2]);
    if (!IsContinuationByte(b2)) return false;
    if (len == 3) {
        if (lead == 0xE0u) return b1 >= 0xA0u && b1 <= 0xBFu;
        if (lead == 0xEDu) return b1 >= 0x80u && b1 <= 0x9Fu;
        return (lead >= 0xE1u && lead <= 0xECu) || (lead >= 0xEEu && lead <= 0xEFu);
    }
    const unsigned char b3 = static_cast<unsigned char>(text[pos + 3]);
    if (!IsContinuationByte(b3)) return false;
    if (lead == 0xF0u) return b1 >= 0x90u && b1 <= 0xBFu;
    if (lead == 0xF4u) return b1 >= 0x80u && b1 <= 0x8Fu;
    return lead >= 0xF1u && lead <= 0xF3u;
}

std::string ConsumeUtf8Chunk(std::string &buffer, bool flush_incomplete) {
    std::string out;
    std::size_t pos = 0;
    while (pos < buffer.size()) {
        const unsigned char lead = static_cast<unsigned char>(buffer[pos]);
        const std::size_t len = Utf8SequenceLength(lead);
        if (len == 0) {
            out += kUtf8Replacement;
            pos += 1;
            continue;
        }
        if (pos + len > buffer.size()) {
            if (!flush_incomplete) break;
            out += kUtf8Replacement;
            pos = buffer.size();
            break;
        }
        if (!IsValidUtf8Sequence(buffer, pos, len)) {
            out += kUtf8Replacement;
            pos += 1;
            continue;
        }
        out.append(buffer, pos, len);
        pos += len;
    }
    buffer.erase(0, pos);
    return out;
}

std::string NormalizeUtf8(const std::string &input) {
    std::string buffer = input;
    return ConsumeUtf8Chunk(buffer, true);
}

std::string JoinTokens(const std::vector<std::string> &tokens) {
    std::string joined;
    std::size_t total_size = 0;
    for (const auto &token : tokens) total_size += token.size();
    joined.reserve(total_size);
    for (const auto &token : tokens) joined += token;
    return joined;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rwkvnotes_infer_NativeRwkvBridge_nativeInitEngine(JNIEnv *env, jobject thiz, jstring config_json) {
    (void)thiz;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        ClearLastErrorLocked();
    }
    const char *raw_config = env->GetStringUTFChars(config_json, nullptr);
    std::string config = raw_config == nullptr ? "" : raw_config;
    env->ReleaseStringUTFChars(config_json, raw_config);
    std::string model_path;
    if (!ExtractString(config, "path", model_path) || model_path.empty()) {
        std::lock_guard<std::mutex> lock(g_mutex);
        StoreLastErrorLocked("MODEL_PATH_EMPTY", "model path is empty");
        return static_cast<jlong>(0);
    }
    double eq_h_decay = 0.62;
    double eq_x_mix = 0.18;
    double eq_o_mix = 0.20;
    double eq_att_base_decay = 0.92;
    double eq_att_decay_scale = 0.07;
    double eq_window_size = 192.0;
    double eq_proj_fan_in = 8.0;
    ExtractNumber(config, "hDecay", eq_h_decay);
    ExtractNumber(config, "xMix", eq_x_mix);
    ExtractNumber(config, "oMix", eq_o_mix);
    ExtractNumber(config, "attBaseDecay", eq_att_base_decay);
    ExtractNumber(config, "attDecayScale", eq_att_decay_scale);
    ExtractNumber(config, "windowSize", eq_window_size);
    ExtractNumber(config, "projFanIn", eq_proj_fan_in);
    rwkv_engine_set_equation_params(
        eq_h_decay, eq_x_mix, eq_o_mix, eq_att_base_decay, eq_att_decay_scale, static_cast<int>(eq_window_size), static_cast<int>(eq_proj_fan_in));
    std::string error_message;
    if (!rwkv_engine_load_model(model_path, error_message)) {
        const std::string code = error_message.empty() ? "ENGINE_INIT_FAILED" : error_message;
        std::lock_guard<std::mutex> lock(g_mutex);
        StoreLastErrorLocked(code, DescribeErrorCode(code));
        return static_cast<jlong>(0);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    ClearLastErrorLocked();
    const long handle = g_next_handle++;
    g_cancel_flags[handle] = false;
    ++g_active_engine_count;
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rwkvnotes_infer_NativeRwkvBridge_nativeConsumeLastErrorJson(JNIEnv *env, jobject thiz) {
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_last_error_json.empty()) return nullptr;
    const std::string error = g_last_error_json;
    g_last_error_json.clear();
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rwkvnotes_infer_NativeRwkvBridge_nativeCancelInference(JNIEnv *env, jobject thiz, jlong engine_handle) {
    (void)env;
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
    if (it != g_cancel_flags.end()) it->second = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rwkvnotes_infer_NativeRwkvBridge_nativeDestroyEngine(JNIEnv *env, jobject thiz, jlong engine_handle) {
    (void)env;
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
    if (it != g_cancel_flags.end()) {
        g_cancel_flags.erase(it);
        if (g_active_engine_count > 0) --g_active_engine_count;
    }
    if (g_active_engine_count == 0) rwkv_engine_unload_model();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rwkvnotes_infer_NativeRwkvBridge_nativeRunInferenceStreamJson(
    JNIEnv *env, jobject thiz, jlong engine_handle, jstring request_json, jobject callback) {
    (void)thiz;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_cancel_flags.find(static_cast<long>(engine_handle)) == g_cancel_flags.end()) {
            const std::string error_response = BuildError("ENGINE_HANDLE_INVALID", "invalid engine handle");
            return env->NewStringUTF(error_response.c_str());
        }
        g_cancel_flags[static_cast<long>(engine_handle)] = false;
    }
    const char *raw_request = env->GetStringUTFChars(request_json, nullptr);
    std::string request = raw_request == nullptr ? "" : raw_request;
    env->ReleaseStringUTFChars(request_json, raw_request);

    std::string prompt;
    if (!ExtractString(request, "prompt", prompt)) {
        const std::string error_response = BuildError("REQUEST_JSON_INVALID", "request missing prompt");
        return env->NewStringUTF(error_response.c_str());
    }
    double max_tokens_num = 256.0;
    double temperature = 0.7;
    double top_p = 0.9;
    double top_k = 40.0;
    double repeat_penalty = 1.10;
    if (!ExtractNumber(request, "maxTokens", max_tokens_num) || max_tokens_num <= 0.0) {
        const std::string error_response = BuildError("REQUEST_MAX_TOKENS_INVALID", "request maxTokens invalid");
        return env->NewStringUTF(error_response.c_str());
    }
    if (!ExtractNumber(request, "temperature", temperature) || temperature < 0.0) {
        const std::string error_response = BuildError("REQUEST_TEMPERATURE_INVALID", "request temperature invalid");
        return env->NewStringUTF(error_response.c_str());
    }
    if (!ExtractNumber(request, "topP", top_p) || top_p < 0.0 || top_p > 1.0) {
        const std::string error_response = BuildError("REQUEST_TOP_P_INVALID", "request topP invalid");
        return env->NewStringUTF(error_response.c_str());
    }
    ExtractNumber(request, "topK", top_k);
    ExtractNumber(request, "repeatPenalty", repeat_penalty);
    if (top_k <= 0.0) {
        const std::string error_response = BuildError("REQUEST_TOP_K_INVALID", "request topK invalid");
        return env->NewStringUTF(error_response.c_str());
    }
    if (repeat_penalty < 1.0) {
        const std::string error_response = BuildError("REQUEST_REPEAT_PENALTY_INVALID", "request repeatPenalty invalid");
        return env->NewStringUTF(error_response.c_str());
    }

    std::vector<std::string> tokens;
    std::string inference_error;
    if (!rwkv_engine_stream_tokens(
            prompt, static_cast<int>(max_tokens_num), temperature, top_p, static_cast<int>(top_k), repeat_penalty, tokens, inference_error)) {
        const std::string code = inference_error.empty() ? "INFER_UNAVAILABLE" : inference_error;
        const std::string error_response = BuildError(code, "native inference failed");
        return env->NewStringUTF(error_response.c_str());
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onTokenJson", "(Ljava/lang/String;)V");
    bool cancelled = false;
    std::string pending_utf8;
    std::size_t chunk_index = 0;
    for (size_t i = 0; i < tokens.size(); ++i) {
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
            if (it != g_cancel_flags.end() && it->second) {
                cancelled = true;
                break;
            }
        }
        pending_utf8 += tokens[i];
        const std::string chunk_text = ConsumeUtf8Chunk(pending_utf8, false);
        if (chunk_text.empty()) continue;
        std::ostringstream chunk;
        chunk << "{\"index\":" << chunk_index++ << ",\"token\":\"" << EscapeJson(chunk_text) << "\",\"isFinished\":false}";
        jstring chunk_json = env->NewStringUTF(chunk.str().c_str());
        env->CallVoidMethod(callback, on_token, chunk_json);
        env->DeleteLocalRef(chunk_json);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    if (cancelled) {
        const std::string error_response = BuildError("INFER_CANCELLED", "inference cancelled");
        return env->NewStringUTF(error_response.c_str());
    }
    const std::string tail_chunk = ConsumeUtf8Chunk(pending_utf8, true);
    if (!tail_chunk.empty()) {
        std::ostringstream chunk;
        chunk << "{\"index\":" << chunk_index++ << ",\"token\":\"" << EscapeJson(tail_chunk) << "\",\"isFinished\":false}";
        jstring chunk_json = env->NewStringUTF(chunk.str().c_str());
        env->CallVoidMethod(callback, on_token, chunk_json);
        env->DeleteLocalRef(chunk_json);
    }
    const std::string generated = NormalizeUtf8(JoinTokens(tokens));
    const std::string response = "{\"ok\":true,\"result\":{\"markdown\":\"" + EscapeJson(generated) +
                                 "\",\"tags\":[],\"raw\":\"" + EscapeJson(generated) + "\"}}";
    return env->NewStringUTF(response.c_str());
}
