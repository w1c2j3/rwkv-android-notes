# Release Gate

## P0 Must Pass
- [ ] Vendored rwkv.cpp source under `app/src/main` is wired by `app/src/main/infer/CMakeLists.txt`
- [ ] Native init/load/infer/cancel/destroy path passes smoke test
- [ ] Model download (retry + mirror + checksum) verified on unstable network
- [ ] Model switch rollback verified (forced load fail then auto restore)
- [ ] End-to-end flow passes: input/upload -> stream -> result -> save -> history
- [ ] Crash-free long-run test passes

## P1 Should Pass
- [ ] Prompt / inference settings dynamic update works
- [ ] Model management page shows progress/speed/eta/error code correctly
- [ ] PDF/DOCX/MD/TEX ingest sample set pass
- [ ] Performance baseline report committed

## P2 Iteration
- [ ] Tokenizer upgrade from byte-level MVP to model-aligned vocab tokenizer
- [ ] Native infer step refinement stays internal to app inference module
- [ ] Further NEON and memory locality tuning
