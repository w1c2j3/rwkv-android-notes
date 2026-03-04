Place upstream `rwkv.cpp` source files in this directory.

Current CMake behavior:
- If `third_party/rwkv.cpp` exists, build links real RWKV core.
- Otherwise it falls back to `rwkv_engine_stub.cpp` so the app remains runnable.
