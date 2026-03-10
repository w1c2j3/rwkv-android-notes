# Perf Baseline Template

## Device
- Model:
- Android:
- CPU ABI:
- RAM:

## Build
- Variant:
- NDK:
- Commit:
- Infer equation params (`hDecay/xMix/oMix/attBaseDecay/attDecayScale/windowSize/projFanIn`):

## Model Case A (0.4B)
- Load time (ms):
- TTFT (ms):
- Decode speed (token/s):
- Peak RSS / PSS:
- 5-run avg:

## Model Case B (1.5B)
- Load time (ms):
- TTFT (ms):
- Decode speed (token/s):
- Peak RSS / PSS:
- 5-run avg:

## Stability
- 30x inference loop crash-free: [ ]
- 30x cancel/restart crash-free: [ ]
- 20x switch model rollback pass: [ ]

## Notes
- Use same prompt set for all runs.
- Record thermal throttling if observed.
- Record exact config TOML snapshot hash for reproducibility.