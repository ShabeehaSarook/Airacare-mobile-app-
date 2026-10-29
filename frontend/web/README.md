# Airacare Web Detection - Phase 1

This is the browser/iPhone Phase 1 version of Airacare.

## What Phase 1 Does

- Opens the browser camera.
- Runs a web-compatible detector in the browser.
- Draws bounding boxes and labels.
- Saves detection events to Firebase Firestore collection `detections`.
- Uses duplicate control so the same object is not stored every frame.
- Shows recent Firebase detections.

## Firebase

Project:

```text
airacare-animal-safety
```

Collection:

```text
detections
```

Web records include:

```text
source: ios_web
cameraSource: web_camera
deviceType: iPhone/Mac/Web
```

## Target Classes

The current Airacare target list is:

```text
person
dog
cat
horse
cow
```

Phase 1 is prepared for the best final model:

```text
frontend/web/models/airacare_yolo.onnx
```

When this ONNX file exists, the web app loads the custom Airacare model. If it is not present, the app falls back to a browser-compatible COCO detector so the camera/Firebase flow still works today.

Current exported web model:

```text
frontend/web/models/airacare_yolo.onnx
```

The deployed ONNX model was exported with these internal labels:

```text
person
dog
cat
horse
cow
deer
goat
```

The web app filters final displayed/saved detections to the current five target classes only.

## Local Test

From this folder:

```powershell
python -m http.server 5173
```

Open:

```text
http://localhost:5173
```

For iPhone camera testing, deploy to HTTPS hosting such as Firebase Hosting. Mobile Safari requires HTTPS for camera access, except on localhost.
