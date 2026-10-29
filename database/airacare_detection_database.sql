-- Airacare Animal Safety Detection Database
-- Database name on Android device: airacare_detections.db
-- Main table: detection_history

CREATE TABLE IF NOT EXISTS detection_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    detected_at_epoch_millis INTEGER NOT NULL,
    detected_at_elapsed_millis INTEGER NOT NULL,
    pipeline_frame INTEGER NOT NULL,
    camera_source TEXT NOT NULL,

    track_id INTEGER,
    label TEXT NOT NULL,
    confidence REAL NOT NULL,

    risk_level TEXT NOT NULL,
    risk_reason TEXT NOT NULL,
    path_zone TEXT NOT NULL,
    movement_direction TEXT NOT NULL,
    predicted_path_crossing INTEGER NOT NULL,
    predicted_path_crossing_seconds REAL,
    predicted_path_reason TEXT NOT NULL,

    distance_meters REAL,
    distance_source TEXT NOT NULL,
    distance_quality REAL,
    closing_speed_mps REAL,
    ttc_seconds REAL,

    vehicle_speed_mps REAL,
    vehicle_speed_kmh REAL,
    vehicle_speed_source TEXT NOT NULL,

    latitude REAL,
    longitude REAL,
    bearing_degrees REAL,
    horizontal_accuracy_meters REAL,
    bearing_accuracy_degrees REAL,

    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    rotation_degrees INTEGER NOT NULL,
    box_left REAL NOT NULL,
    box_top REAL NOT NULL,
    box_right REAL NOT NULL,
    box_bottom REAL NOT NULL,

    visibility_state TEXT NOT NULL,
    visibility_quality REAL NOT NULL,
    enhancement_enabled INTEGER NOT NULL,

    warning_level TEXT NOT NULL,
    warning_triggered INTEGER NOT NULL,
    audio_triggered INTEGER NOT NULL,
    vibration_triggered INTEGER NOT NULL,
    warning_message TEXT NOT NULL,

    image_path TEXT,
    sync_status TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_detection_history_time
ON detection_history (detected_at_epoch_millis DESC);

CREATE INDEX IF NOT EXISTS idx_detection_history_risk
ON detection_history (risk_level);

CREATE INDEX IF NOT EXISTS idx_detection_history_location
ON detection_history (latitude, longitude);

CREATE INDEX IF NOT EXISTS idx_detection_history_sync
ON detection_history (sync_status);

-- Example record:
INSERT INTO detection_history (
    detected_at_epoch_millis,
    detected_at_elapsed_millis,
    pipeline_frame,
    camera_source,
    track_id,
    label,
    confidence,
    risk_level,
    risk_reason,
    path_zone,
    movement_direction,
    predicted_path_crossing,
    predicted_path_crossing_seconds,
    predicted_path_reason,
    distance_meters,
    distance_source,
    distance_quality,
    closing_speed_mps,
    ttc_seconds,
    vehicle_speed_mps,
    vehicle_speed_kmh,
    vehicle_speed_source,
    latitude,
    longitude,
    bearing_degrees,
    horizontal_accuracy_meters,
    bearing_accuracy_degrees,
    image_width,
    image_height,
    rotation_degrees,
    box_left,
    box_top,
    box_right,
    box_bottom,
    visibility_state,
    visibility_quality,
    enhancement_enabled,
    warning_level,
    warning_triggered,
    audio_triggered,
    vibration_triggered,
    warning_message,
    image_path,
    sync_status
) VALUES (
    1799999999000,
    450000,
    125,
    'phone_back_camera',
    7,
    'dog',
    0.91,
    'HIGH',
    'animal_in_vehicle_path',
    'IN_PATH',
    'TOWARD_LANE',
    1,
    2.4,
    'animal predicted to cross vehicle path',
    8.5,
    'CAMERA_METADATA',
    0.82,
    3.1,
    2.3,
    11.7,
    42.1,
    'gps',
    6.9271,
    79.8612,
    120.0,
    8.0,
    15.0,
    480,
    360,
    90,
    120.0,
    80.0,
    260.0,
    300.0,
    'NORMAL',
    0.95,
    0,
    'DANGER',
    1,
    1,
    1,
    'DOG IN VEHICLE PATH',
    NULL,
    'LOCAL'
);

-- Useful check query:
SELECT
    id,
    label,
    confidence,
    risk_level,
    distance_meters,
    vehicle_speed_kmh,
    latitude,
    longitude,
    warning_level,
    datetime(detected_at_epoch_millis / 1000, 'unixepoch') AS detected_time
FROM detection_history
ORDER BY detected_at_epoch_millis DESC;
