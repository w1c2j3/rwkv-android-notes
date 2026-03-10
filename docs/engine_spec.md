# Engine Spec

## Scope
- Android NDK native inference core for RWKV-style autoregressive generation.
- Fail-fast behavior for unsupported model format / invalid metadata / invalid runtime state.
- The inference module is internal to the app and exposes only config + input/output APIs to upper layers.

## Public Native Lifecycle
- `init(config_json) -> handle`
- `infer_stream(handle, request_json, callback) -> final_json`
- `cancel(handle)`
- `destroy(handle)`

## Upper-Layer Boundary
- Kotlin upper layers consume `InferenceRuntime` only.
- Upper layers provide:
  - config json
  - request json
- Upper layers receive:
  - token callback json
  - final envelope json
- Upper layers must not depend on vendored backend file layout or rwkv.cpp symbols directly.

## Runtime State
- Engine handle owns:
  - `model_mapping` (`fd`, `addr`, `size`, `path`)
  - `inference_state` (token cache, generation cursor, cancel flag)
  - `sampler_state` (repeat penalty cache)
- All transitions are serialized by engine mutex.

## Request Contract (JSON)
- Required:
  - `prompt` string
  - `modelPath` string
  - `maxTokens` int > 0
  - `temperature` double >= 0
  - `topP` double in [0,1]
- Optional:
  - `stream` bool

## Response Contract (JSON envelope)
- Success:
  - `{ "ok": true, "result": { "markdown": "...", "tags": [...], "raw": "..." } }`
- Failure:
  - `{ "ok": false, "error": { "code": "...", "message": "..." } }`

## Model Format
- Current hard requirement: rwkv.cpp-compatible runtime format.
- `.pth` is rejected at native load phase with explicit error code.

## Fail-Fast Rules
- Invalid input or unsupported format -> immediate error.
- Missing required weights / invalid shapes -> immediate error.
- Inference call without loaded model -> immediate error.
