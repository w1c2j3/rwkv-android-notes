# Tokenizer Spec

## Goal
- Provide deterministic encode/decode behavior for native inference path.
- Keep implementation Android-safe and dependency-light.

## MVP Tokenizer
- Encoding strategy:
  - UTF-8 byte-level tokenizer (1 byte -> 1 token id in [0,255]).
- Decoding strategy:
  - token ids in [0,255] -> single byte -> UTF-8 string reconstruction.
- This is an MVP tokenizer for integration plumbing and end-to-end verification.

## Constraints
- Input text must be non-empty.
- Decoder rejects out-of-range token ids.

## Upgrade Path
- Replace MVP byte tokenizer with model-aligned tokenizer:
  - vocab file + merges/rules.
  - deterministic roundtrip tests must remain.
