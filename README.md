# RWKV Android Notes

本项目是一个 **Android 端侧本地 AI 笔记整理应用**，目标是在手机上通过 `rwkv.cpp + JNI + Kotlin` 实现：

- 杂乱文本结构化（Markdown）
- 本地语义标签提取（Tags）
- 流式输出（Token Streaming）
- 全离线推理（隐私优先）

---

## 技术栈

- Android: Kotlin + Jetpack Compose + Material 3
- Native: C++ (NDK) + JNI
- 推理内核: rwkv.cpp（当前仓库内为 stub + mmap 骨架）
- 持久化: Room
- 异步: Coroutines + Flow
- 依赖注入: Hilt
- 配置: TOML
- 协议: JSON

---

## 当前进度（已完成）

### 1) 核心推理链路

- `Kotlin -> JNI -> C++ -> Kotlin` 流式推理链路已打通
- Token 回调已封装为 `Flow`
- 最终响应统一为 JSON envelope（`ok/result/error`）

### 2) Prompt 与协议

- Prompt 采用固定模板 + Glossary 注入
- 引入上下文长度估算与截断策略（防止超窗）
- Kotlin / JNI 层使用统一 JSON 请求响应结构

### 3) 模型与内存

- 模型路径使用 internal storage
- C++ stub 路径已采用 `mmap` 加载
- 引擎生命周期支持 cancel / destroy
- `switchModel` 已闭环到 `AiService` 引擎重建

### 4) 数据与模块

- Room 已支持笔记与标签持久化
- 已补齐 6 个后端模块骨架与可运行实现：
  - `ModelManager`
  - `DocumentIngestionPipeline`
  - `InferenceTaskOrchestrator`
  - `SemanticIndexService`
  - `InferenceMetricsService`
  - 解析器能力（pdf/docx/md/tex 基础路径）

### 5) UI 进度

- 已按 Wireframe 接入 6 视图路由：
  - Home / Stream / Result / History / Model / Settings
- Stream 页仅纯文本流式展示，Result 页再进行 Markdown 渲染

---

## 目前仍在进行 / 待完善

### A. 推理内核真实接入

- 当前 C++ 推理部分仍以 stub 为主
- 需将真实 `rwkv.cpp` 推理调用完整替换并压测

### B. 模型下载系统强化

- 现有下载逻辑为基础版
- 待补：断点续传、后台任务化（WorkManager）、失败重试策略、下载状态持久化

### C. 文件解析能力增强

- 当前已支持基础文本抽取
- 待补：更完整的 PDF 结构抽取、DOCX 样式语义保留、LaTeX 公式 AST 级解析

### D. 检索与索引

- 当前为标签倒排快照
- 待补：语义检索索引与分页检索能力

### E. 工程化与发布

- 待补：release 构建基线、签名流程、AAB 输出、性能基准报告

---

## 目录概览

- `app/src/main/java/com/example/rwkvnotes/ai`：推理服务、JNI 桥、协议
- `app/src/main/java/com/example/rwkvnotes/model`：模型管理
- `app/src/main/java/com/example/rwkvnotes/ingest`：文件导入与解析
- `app/src/main/java/com/example/rwkvnotes/orchestrator`：任务编排
- `app/src/main/java/com/example/rwkvnotes/data`：Room 数据层
- `app/src/main/cpp`：native 层（JNI、mmap、stub/rwkv 接入点）

---

## 本地开发说明（简版）

1. 使用 Android Studio 打开 `rwkv-android-notes`
2. 准备模型文件到 internal storage 对应路径
3. 按需修改 `app/src/main/assets/config/app_config.toml`
4. 运行到真机（推荐 arm64）

---

## 说明

- 本仓库默认不提交模型权重文件（见 `.gitignore`）
- 目前优先保证“架构闭环 + 可扩展性”，视觉样式可持续迭代
