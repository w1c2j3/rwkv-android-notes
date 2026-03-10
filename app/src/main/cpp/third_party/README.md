Bundled native dependencies for the in-app inference module.

Current build behavior:
- Native inference is implemented under `app/src/main/infer`.
- `app/src/main/infer/CMakeLists.txt` links against the vendored source at
  `app/src/main/cpp/third_party/rwkv_cpp`.
- Upper layers should only use the Kotlin/native API boundary and must not depend on
  third-party source layout details.

Integration note:
- `.pth` weights are not accepted by the mobile runtime path.
- Runtime model files must be converted into a rwkv.cpp-compatible standalone format first.

Repository note:
- A root-level `rwkv.cpp/` folder may still exist temporarily for comparison or validation.
- Active app build must remain self-contained inside `app/src/main`.
