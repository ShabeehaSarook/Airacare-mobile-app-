# Current architecture

## Detection

MainActivity coordinates input and UI. AnimalSafetyAnalyzer passes CameraX frames
to AnimalDetector; YoloTfliteAnimalDetector handles conversion, upright rotation,
model preprocessing, inference, output parsing and NMS. Its input dimensions come
from the model tensor.

MainActivity applies tracking, distance, vehicle-speed/location data, relative
motion/TTC, path estimation, movement prediction and risk evaluation. WarningManager
drives warnings. DetectionOverlayView draws results.

DetectionHistoryRepository stores local records via DetectionDatabaseHelper.
FirestoreDetectionRepository sends selected events to `detections`; event
suppression decisions live in MainActivity. Missing sensors must remain nullable.

## Capture & Store

1. A recent dog/cat makes capture eligible.
2. Phone capture uses CameraX ImageCapture (requested target 1280x960; device
   negotiation decides actual dimensions). External streams use their available frame.
3. PetCropper suggests a padded crop; PetCropAdjustView lets the user correct it.
4. The selected crop is limited to 1536px on its longest side without upscaling.
5. PetCaptureRepository sends PNG to `/api/remove-background`.
6. Backend/PhotoRoom returns PNG bytes. MainActivity previews those bytes.
7. On explicit Save, the same returned bytes go to `/api/captured-pets/upload`.
8. Backend stores the image in Cloudinary. CapturedPetRepository stores metadata
   in `captured_pets`; CapturedPetLocalStore provides local metadata fallback.
9. CapturedPetsActivity loads saved image URLs with Glide.
10. Delete confirmation leads to backend image deletion and metadata deletion.

ImageCapture initially supplies a camera JPEG at configured quality 95; Android
then encodes the crop as PNG. PNG encoding cannot recover detail already lost.
The high-resolution photo is captured later than the detection frame; movement
or differing camera crops can affect the suggested box. Manual crop review remains
important. Alpha diagnostics and successful builds alone do not prove clean edges.

## Service boundaries

- On device: inference, camera, tracking, risk, UI, SQLite and local preferences.
- Firebase: detection and captured-pet metadata.
- Render backend: PhotoRoom and Cloudinary integration.
- Cloudinary: saved image assets.
- Browser: Firebase client in frontend/web; Flask client in frontend/backend-web.
- Backend source: backend/web_backend.py with dependencies in backend/src.

The backend health response describes backend configuration, not Android runtime
model selection or successful end-to-end uploads.

## Current model and optional features

MainActivity explicitly selects PRETRAINED_COCO. AiracareModelInfo identifies
`yolo26n_w8a32.tflite` and `coco_labels.txt`; retained custom assets remain available.
Inspect runtime tensor logs instead of relying on old 320x320 assumptions.
Hazard sharing and nearby alerts are disabled; files for these features remain
as extension code. See [source map](PROJECT_MAP.md).
