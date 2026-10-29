# Airacare V5 Failure-Driven Model Plan

V5 must not start until the Android runtime pipeline is stable enough to produce trustworthy failures.

> Historical reference/planning document. Start with [the current project guide](../README.md).
> Paths shown as code are relative to the repository root; plans are not runtime verification.

## Current model

- Keep V4 dataset: `airacare_v4_road_safety_final`
- Keep V4 model: 15-epoch custom model, `airacare_yolo_v4_15epoch_finetune`
- Do not retrain V4.
- Do not rebuild V4.

## V5 gate

Start V5 only from proven real-world failures collected through the Android app:

- missed people
- missed animals
- false CAT
- false DOG
- false HORSE
- false ELEPHANT
- night misses
- far-object misses
- side-crossing misses
- hard negatives

Every candidate must be human-reviewed before entering the V5 dataset.

## Dataset rule

```text
V4
+
verified real-world failures
=
V5
```

No scraped, synthetic, guessed, or unreviewed samples should be added during this pass.

## Training rule

- Train V5 once.
- Keep the V4 Android model frozen while V5 is evaluated.
- Do not replace the Android model during training.

## Comparison rule

Compare V4 vs V5 on the same reviewed validation set:

- missed people
- missed animals
- false CAT/DOG/HORSE/ELEPHANT
- night misses
- far-object misses
- side-crossing misses
- hard negatives
- Android runtime latency and FPS

Only replace V4 if V5 is clearly better overall and does not regress runtime performance.
