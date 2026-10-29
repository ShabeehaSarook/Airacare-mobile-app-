# Airacare System Architecture

> Historical reference/planning document. Start with [the current project guide](../README.md).
> File paths shown as code are relative to the repository root. Planned features are not proof of runtime availability.

This is the target Airacare stack. Current Android work remains the base pipeline; future sensors extend it through fusion and event data.

```text
INPUTS
Phone camera
Dashcam
GPS
IMU
Future radar
Future thermal
    |
    v
VISION
YOLO detection
    |
    v
TRACKING
Object ID
movement history
    |
    v
MOTION
animal direction
vehicle direction
    |
    v
DISTANCE
camera estimate
future radar/depth
    |
    v
COLLISION LOGIC
closing speed
TTC
path relation
path intersection
    |
    v
RISK
LOW
MEDIUM
HIGH
    |
    v
OUTPUT
visual alert
audio alert
vibration
hazard sharing
```

## Implementation Boundary

- Current active inputs: phone camera, recorded dashcam video, live MJPEG/HTTP dashcam, live RTSP dashcam, GPS speed/bearing.
- Future inputs: phone IMU, radar, thermal.
- Current output: visual alert, audio alert, vibration.
- Future output extension: production hazard sharing after runtime validation.
- Sensor fusion must be implemented as a separate extension over this pipeline.
- YOLO should not be retrained just because GPS, IMU, radar, or thermal data is added.
- Sensor/fusion samples belong in a separate event dataset.
