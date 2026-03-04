#include <jni.h>
#include <chrono>
#include <cctype>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

std::vector<std::string> rwkv_stub_stream_tokens(const std::string &prompt);
bool rwkv_stub_load_model(const std::string &model_path, std::string &error_message);
void rwkv_stub_unload_model();

namespace {
std::mutex g_mutex;
std::unordered_map<long, bool> g_cancel_flags;
long g_next_handle = 1;
int g_active_engine_count = 0;

std::string escape_json(const std::string &input) {
    std::string out;
    out.reserve(input.size());
    for (char c : input) {
        switch (c) {
            case '\"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

void skip_ws(const std::string &text, size_t &idx) {
    while (idx < text.size() && std::isspace(static_cast<unsigned char>(text[idx]))) {
        ++idx;
    }
}

bool parse_json_string(const std::string &text, size_t &idx, std::string &out) {
    if (idx >= text.size() || text[idx] != '\"') return false;
    ++idx;
    std::string result;
    while (idx < text.size()) {
        char c = text[idx++];
        if (c == '\"') {
            out = result;
            return true;
        }
        if (c == '\\') {
            if (idx >= text.size()) return false;
            char esc = text[idx++];
            switch (esc) {
                case '\"': result.push_back('\"'); break;
                case '\\': result.push_back('\\'); break;
                case '/': result.push_back('/'); break;
                case 'b': result.push_back('\b'); break;
                case 'f': result.push_back('\f'); break;
                case 'n': result.push_back('\n'); break;
                case 'r': result.push_back('\r'); break;
                case 't': result.push_back('\t'); break;
                case 'u': {
                    if (idx + 4 > text.size()) return false;
                    idx += 4;
                    result.push_back('?');
                    break;
                }
                default: return false;
            }
            continue;
        }
        result.push_back(c);
    }
    return false;
}

bool skip_json_number(const std::string &text, size_t &idx) {
    size_t start = idx;
    if (idx < text.size() && (text[idx] == '-' || text[idx] == '+')) ++idx;
    while (idx < text.size() && std::isdigit(static_cast<unsigned char>(text[idx]))) ++idx;
    if (idx < text.size() && text[idx] == '.') {
        ++idx;
        while (idx < text.size() && std::isdigit(static_cast<unsigned char>(text[idx]))) ++idx;
    }
    if (idx < text.size() && (text[idx] == 'e' || text[idx] == 'E')) {
        ++idx;
        if (idx < text.size() && (text[idx] == '-' || text[idx] == '+')) ++idx;
        while (idx < text.size() && std::isdigit(static_cast<unsigned char>(text[idx]))) ++idx;
    }
    return idx > start;
}

bool skip_json_value(const std::string &text, size_t &idx);
bool skip_json_array(const std::string &text, size_t &idx) {
    if (idx >= text.size() || text[idx] != '[') return false;
    ++idx;
    skip_ws(text, idx);
    if (idx < text.size() && text[idx] == ']') {
        ++idx;
        return true;
    }
    while (idx < text.size()) {
        if (!skip_json_value(text, idx)) return false;
        skip_ws(text, idx);
        if (idx >= text.size()) return false;
        if (text[idx] == ',') {
            ++idx;
            skip_ws(text, idx);
            continue;
        }
        if (text[idx] == ']') {
            ++idx;
            return true;
        }
        return false;
    }
    return false;
}

bool skip_json_object(const std::string &text, size_t &idx) {
    if (idx >= text.size() || text[idx] != '{') return false;
    ++idx;
    skip_ws(text, idx);
    if (idx < text.size() && text[idx] == '}') {
        ++idx;
        return true;
    }
    while (idx < text.size()) {
        std::string key;
        if (!parse_json_string(text, idx, key)) return false;
        skip_ws(text, idx);
        if (idx >= text.size() || text[idx] != ':') return false;
        ++idx;
        skip_ws(text, idx);
        if (!skip_json_value(text, idx)) return false;
        skip_ws(text, idx);
        if (idx >= text.size()) return false;
        if (text[idx] == ',') {
            ++idx;
            skip_ws(text, idx);
            continue;
        }
        if (text[idx] == '}') {
            ++idx;
            return true;
        }
        return false;
    }
    return false;
}

bool skip_json_value(const std::string &text, size_t &idx) {
    skip_ws(text, idx);
    if (idx >= text.size()) return false;
    if (text[idx] == '\"') {
        std::string value;
        return parse_json_string(text, idx, value);
    }
    if (text[idx] == '{') return skip_json_object(text, idx);
    if (text[idx] == '[') return skip_json_array(text, idx);
    if (text.compare(idx, 4, "true") == 0) {
        idx += 4;
        return true;
    }
    if (text.compare(idx, 5, "false") == 0) {
        idx += 5;
        return true;
    }
    if (text.compare(idx, 4, "null") == 0) {
        idx += 4;
        return true;
    }
    return skip_json_number(text, idx);
}

bool find_string_in_value(const std::string &text, size_t &idx, const std::string &target_key, std::string &out);

bool find_string_in_array(const std::string &text, size_t &idx, const std::string &target_key, std::string &out) {
    if (idx >= text.size() || text[idx] != '[') return false;
    ++idx;
    skip_ws(text, idx);
    if (idx < text.size() && text[idx] == ']') {
        ++idx;
        return false;
    }
    while (idx < text.size()) {
        if (find_string_in_value(text, idx, target_key, out)) return true;
        skip_ws(text, idx);
        if (idx >= text.size()) return false;
        if (text[idx] == ',') {
            ++idx;
            skip_ws(text, idx);
            continue;
        }
        if (text[idx] == ']') {
            ++idx;
            return false;
        }
        return false;
    }
    return false;
}

bool find_string_in_object(const std::string &text, size_t &idx, const std::string &target_key, std::string &out) {
    if (idx >= text.size() || text[idx] != '{') return false;
    ++idx;
    skip_ws(text, idx);
    if (idx < text.size() && text[idx] == '}') {
        ++idx;
        return false;
    }
    while (idx < text.size()) {
        std::string key;
        if (!parse_json_string(text, idx, key)) return false;
        skip_ws(text, idx);
        if (idx >= text.size() || text[idx] != ':') return false;
        ++idx;
        skip_ws(text, idx);
        if (key == target_key) {
            if (idx < text.size() && text[idx] == '\"') {
                return parse_json_string(text, idx, out);
            }
            if (!skip_json_value(text, idx)) return false;
        } else {
            if (find_string_in_value(text, idx, target_key, out)) return true;
        }
        skip_ws(text, idx);
        if (idx >= text.size()) return false;
        if (text[idx] == ',') {
            ++idx;
            skip_ws(text, idx);
            continue;
        }
        if (text[idx] == '}') {
            ++idx;
            return false;
        }
        return false;
    }
    return false;
}

bool find_string_in_value(const std::string &text, size_t &idx, const std::string &target_key, std::string &out) {
    skip_ws(text, idx);
    if (idx >= text.size()) return false;
    if (text[idx] == '{') return find_string_in_object(text, idx, target_key, out);
    if (text[idx] == '[') return find_string_in_array(text, idx, target_key, out);
    return skip_json_value(text, idx) && false;
}

bool extract_json_string_field(const std::string &json_text, const std::string &key, std::string &value) {
    size_t idx = 0;
    return find_string_in_value(json_text, idx, key, value);
}

std::string build_error_response(const std::string &code, const std::string &message) {
    return "{\"ok\":false,\"error\":{\"code\":\"" + escape_json(code) + "\",\"message\":\"" +
           escape_json(message) + "\"}}";
}

std::string build_success_response(
    const std::string &markdown,
    const std::string &raw,
    const std::string &tags_json_array) {
    return "{\"ok\":true,\"result\":{\"markdown\":\"" + escape_json(markdown) + "\",\"tags\":" +
           tags_json_array + ",\"raw\":\"" + escape_json(raw) + "\"}}";
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rwkvnotes_ai_NativeRwkvBridge_nativeInitEngine(
    JNIEnv *env, jobject thiz, jstring config_json) {
    (void)thiz;
    const char *raw_config = env->GetStringUTFChars(config_json, nullptr);
    std::string config = raw_config == nullptr ? "" : raw_config;
    env->ReleaseStringUTFChars(config_json, raw_config);
    std::string model_path;
    if (!extract_json_string_field(config, "path", model_path) || model_path.empty()) {
        return static_cast<jlong>(0);
    }
    std::string error_message;
    if (!rwkv_stub_load_model(model_path, error_message)) {
        return static_cast<jlong>(0);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    long handle = g_next_handle++;
    g_cancel_flags[handle] = false;
    ++g_active_engine_count;
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rwkvnotes_ai_NativeRwkvBridge_nativeCancelInference(
    JNIEnv *env, jobject thiz, jlong engine_handle) {
    (void)env;
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
    if (it != g_cancel_flags.end()) {
        it->second = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rwkvnotes_ai_NativeRwkvBridge_nativeDestroyEngine(
    JNIEnv *env, jobject thiz, jlong engine_handle) {
    (void)env;
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
    if (it != g_cancel_flags.end()) {
        g_cancel_flags.erase(it);
        if (g_active_engine_count > 0) {
            --g_active_engine_count;
        }
    }
    if (g_active_engine_count == 0) {
        rwkv_stub_unload_model();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rwkvnotes_ai_NativeRwkvBridge_nativeRunInferenceStreamJson(
    JNIEnv *env, jobject thiz, jlong engine_handle, jstring request_json, jobject callback) {
    (void)thiz;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_cancel_flags[static_cast<long>(engine_handle)] = false;
    }
    const char *raw_request = env->GetStringUTFChars(request_json, nullptr);
    std::string request = raw_request == nullptr ? "" : raw_request;
    env->ReleaseStringUTFChars(request_json, raw_request);

    std::string prompt;
    if (!extract_json_string_field(request, "prompt", prompt)) {
        std::string error_response = build_error_response("invalid_request_json", "request missing prompt");
        return env->NewStringUTF(error_response.c_str());
    }
    std::vector<std::string> tokens = rwkv_stub_stream_tokens(prompt);

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onTokenJson", "(Ljava/lang/String;)V");

    bool cancelled = false;
    for (size_t i = 0; i < tokens.size(); ++i) {
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            auto it = g_cancel_flags.find(static_cast<long>(engine_handle));
            if (it != g_cancel_flags.end() && it->second) {
                cancelled = true;
                break;
            }
        }
        std::ostringstream chunk;
        chunk << "{\"index\":" << i
              << ",\"token\":\"" << escape_json(tokens[i]) << "\""
              << ",\"isFinished\":false}";
        jstring chunk_json = env->NewStringUTF(chunk.str().c_str());
        env->CallVoidMethod(callback, on_token, chunk_json);
        env->DeleteLocalRef(chunk_json);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }

    std::string markdown = cancelled
                               ? "## Cancelled\n- inference stopped"
                               : "## Structured Notes\n- **Generated locally**\n- [ ] Refine tags";
    std::string response = build_success_response(markdown, prompt, "[\"rwkv\",\"local-ai\",\"draft\"]");
    return env->NewStringUTF(response.c_str());
}
