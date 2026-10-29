# Airacare Sensor Fusion Extension Plan

Sensor fusion is a later extension. The current Android project remains the base.

> Historical reference/planning document. Start with [the current project guide](../README.md).
> Paths shown as code are relative to the repository root; plans are not runtime verification.

## Current base

Keep the existing Airacare runtime pipeline:

```text
Camera YOLO
-> tracking
-> distance
-> motion/TTC
-> path prediction
-> risk
-> warning
```

Do not retrain YOLO just because GPS, phone IMU, radar, or thermal data is added.

## Later fusion inputs

Add these as separate sensor streams when the base pipeline is stable:

- Camera YOLO detections
- GPS speed, location, and bearing
- phone IMU acceleration and rotation
- radar object range and velocity
- thermal object cues

## Fusion output

```text
fusion engine
-> better distance
-> better motion
-> better TTC
-> better risk
```

Fusion should improve downstream estimates. It should not replace the YOLO detector or create a second AI pipeline.

## Dataset rule

Sensor data belongs in a separate event dataset, not the YOLO image dataset.

The sensor event dataset should store synchronized event rows such as:

- timestamp
- source mode: phone camera, recorded dashcam, live dashcam
- YOLO detections and track ids
- bounding boxes and confidence
- GPS speed/location/bearing
- IMU acceleration/rotation
- radar range/velocity when available
- thermal cues when available
- distance, closing speed, TTC, path prediction, risk, warning state
- human-reviewed event outcome

Use this event dataset to tune fusion, TTC, and risk behavior. Use the V5 image dataset only for human-reviewed detection failures.
