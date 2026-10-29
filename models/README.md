# Model inventory

Model binaries remain beside their runtime consumers to preserve loader paths.
There is no shared universal model across all three runtimes.

| Consumer | Model path from repository root | Labels |
| --- | --- | --- |
| Active Android | app/src/main/assets/yolo26n_w8a32.tflite | coco_labels.txt in same folder |
| Retained Android custom | app/src/main/assets/airacare_custom.tflite | airacare_custom_labels.txt |
| Firebase browser client | frontend/web/models/airacare_yolo.onnx | airacare_labels.txt |
| Imported backend | backend/models/airacare_custom.tflite | airacare_custom_labels.txt |
| Backend alternate assets | backend/models/*.pt | Read model metadata |

Check model input/output tensors and exact label ordering before changing assets.
Android filters person/dog/cat/horse/cow. Backend configuration may expose a different
class list. No models were trained or replaced during folder organization.
Model-export work remains in ../model-export/.

