package com.airacare.animalsafety

object AiracareModelInfo {
    const val MODEL_ASSET = "yolo26n_w8a32.tflite"
    const val LABELS_ASSET = "coco_labels.txt"
    const val RETAINED_CUSTOM_MODEL_ASSET = "airacare_custom.tflite"
    const val RETAINED_CUSTOM_LABELS_ASSET = "airacare_custom_labels.txt"
    const val TRAINING_DATASET = "airacare_v4_road_safety_final"
    const val TRAINING_RUN = "airacare_yolo_v4_15epoch_finetune"
    const val MODEL_FREEZE_STATUS = "pretrained_coco_active_five_class_requirement"
    const val NEXT_MODEL_GATE = "v5_only_from_human_reviewed_real_world_failures"
    const val REPLACEMENT_RULE = "replace_v4_only_if_v5_clearly_better"
    const val SENSOR_FUSION_RULE = "sensor_fusion_extension_no_yolo_retrain_required"
    const val SENSOR_EVENT_DATASET = "separate_sensor_event_dataset"
    const val INPUT_SHAPE = "resolved_from_tflite_runtime"
    const val OUTPUT_SHAPE = "resolved_from_tflite_runtime"
    const val CLASSES = "person,dog,cat,horse,cow"

    fun summary(): String {
        return "$MODEL_ASSET labels=$LABELS_ASSET status=$MODEL_FREEZE_STATUS retainedCustom=$RETAINED_CUSTOM_MODEL_ASSET $NEXT_MODEL_GATE $SENSOR_FUSION_RULE input=$INPUT_SHAPE output=$OUTPUT_SHAPE classes=$CLASSES"
    }
}
