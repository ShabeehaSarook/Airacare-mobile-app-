import { initializeApp } from "https://www.gstatic.com/firebasejs/10.13.2/firebase-app.js";
import {
  addDoc,
  collection,
  getFirestore,
  limit,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp
} from "https://www.gstatic.com/firebasejs/10.13.2/firebase-firestore.js";

const firebaseConfig = {
  apiKey: "AIzaSyDR_Bt2tltKxSfLSvmlW5mQ0uwxlmafy3w",
  authDomain: "airacare-animal-safety.firebaseapp.com",
  projectId: "airacare-animal-safety",
  storageBucket: "airacare-animal-safety.firebasestorage.app",
  messagingSenderId: "934286949654",
  appId: "1:934286949654:android:a0c98c97bd4568e95ee437"
};

const TARGET_CLASSES = [
  "person",
  "dog",
  "cat",
  "horse",
  "cow"
];

const TARGET_CLASS_SET = new Set(TARGET_CLASSES);
const CUSTOM_ONNX_MODEL_PATH = "./models/airacare_yolo.onnx";
const CUSTOM_LABELS_PATH = "./models/airacare_labels.txt";
const MODEL_INPUT_SIZE = 640;
const REQUESTED_INPUT_SIZE = readNumberParam("inputSize", MODEL_INPUT_SIZE);
const DEFAULT_MIN_CONFIDENCE = readNumberParam("personThreshold", 0.55);
const ANIMAL_MIN_CONFIDENCE = readNumberParam("animalThreshold", 0.68);
const IOU_THRESHOLD = 0.45;
const TRACK_IOU_THRESHOLD = 0.35;
const INFERENCE_INTERVAL_MS = 650;
const FIRESTORE_COOLDOWN_MS = 15000;
const POSITION_BUCKETS = 6;
const DEBUG_YOLO = true;
const DEBUG_CANDIDATE_THRESHOLD = 0.01;
const DEBUG_CANDIDATE_LOG_LIMIT = 120;
const DEBUG_TOP_CANDIDATE_COUNT = 20;
const TRACK_MAX_AGE_MS = 1800;
const PERSON_CONFIRMATION_FRAMES = 2;
const ANIMAL_CONFIRMATION_FRAMES = 3;
const ANIMAL_CONFIRMATION_MS = 700;
const ANIMAL_CLASSES = new Set(["dog", "cat", "horse", "cow"]);

const app = initializeApp(firebaseConfig);
const db = getFirestore(app);

const video = document.getElementById("cameraVideo");
const canvas = document.getElementById("overlayCanvas");
const ctx = canvas.getContext("2d");
const startButton = document.getElementById("startButton");
const stopButton = document.getElementById("stopButton");
const connectionStatus = document.getElementById("connectionStatus");
const modelStatus = document.getElementById("modelStatus");
const warningBanner = document.getElementById("warningBanner");
const totalCount = document.getElementById("totalCount");
const latestLabel = document.getElementById("latestLabel");
const latestRisk = document.getElementById("latestRisk");
const historyList = document.getElementById("historyList");

let detector = null;
let stream = null;
let running = false;
let loopTimer = null;
let detectionCount = 0;
let latestPosition = null;
let lastUploadBySignature = new Map();
let activeTracks = [];
let nextTrackId = 1;
let audioContext = null;

startButton.addEventListener("click", start);
stopButton.addEventListener("click", stop);
window.addEventListener("resize", resizeCanvas);

listenToRecentDetections();
setStatus("Ready");
logRuntimeConfiguration();

async function start() {
  try {
    setStatus("Loading model");
    startButton.disabled = true;
    detector = detector || await loadDetector();

    setStatus("Opening camera");
    await startLocationWatch();
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: "environment" },
        width: { ideal: 1280 },
        height: { ideal: 720 }
      },
      audio: false
    });
    video.srcObject = stream;
    await video.play();
    resizeCanvas();
    running = true;
    stopButton.disabled = false;
    setStatus("Detecting");
    detectLoop();
  } catch (error) {
    console.error(error);
    setStatus(error.message || "Start failed");
    startButton.disabled = false;
    stopButton.disabled = true;
  }
}

function stop() {
  running = false;
  if (loopTimer) clearTimeout(loopTimer);
  loopTimer = null;
  if (stream) stream.getTracks().forEach((track) => track.stop());
  stream = null;
  video.srcObject = null;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  warningBanner.hidden = true;
  startButton.disabled = false;
  stopButton.disabled = true;
  setStatus("Stopped");
}

async function loadDetector() {
  if (await assetExists(CUSTOM_ONNX_MODEL_PATH)) {
    const labels = await loadLabels();
    const yolo = await YoloOnnxDetector.create(CUSTOM_ONNX_MODEL_PATH, labels);
    modelStatus.textContent = "Airacare YOLO web model loaded";
    return yolo;
  }

  const fallback = await CocoSsdDetector.create();
  const missing = TARGET_CLASSES.filter((label) => !fallback.supportedClasses.has(label));
  modelStatus.textContent = `Fallback model loaded; add ONNX model for ${missing.join(", ")}`;
  return fallback;
}

async function detectLoop() {
  if (!running || !detector) return;
  const started = performance.now();
  try {
    const rawDetections = (await detector.detect(video, canvas))
      .filter((detection) => TARGET_CLASS_SET.has(detection.label))
      .filter((detection) => detection.confidence >= confidenceThresholdForLabel(detection.label));
    const detections = confirmStableDetections(rawDetections).map(toDetectionEvent);

    drawDetections(detections);
    for (const detection of detections) {
      await maybeSaveDetection(detection);
    }
    updateLatest(detections);
  } catch (error) {
    console.error(error);
    setStatus(error.message || "Detection failed");
  } finally {
    const elapsed = performance.now() - started;
    const delay = Math.max(80, INFERENCE_INTERVAL_MS - elapsed);
    loopTimer = window.setTimeout(detectLoop, delay);
  }
}

function toDetectionEvent(detection) {
  const box = detection.boundingBox;
  const width = box.right - box.left;
  const height = box.bottom - box.top;
  const pathZone = estimatePathZone(box.left, box.top, width, height);
  const riskLevel = estimateRiskLevel(pathZone, detection.confidence);
  const warningTriggered = riskLevel === "HIGH";
  const position = freshPosition();
  return {
    label: detection.label,
    confidence: detection.confidence,
    riskLevel,
    riskReason: `web estimate; path zone ${pathZone}`,
    distanceMeters: null,
    vehicleSpeedKmh: position?.speedKmh ?? null,
    latitude: position?.latitude ?? null,
    longitude: position?.longitude ?? null,
    bearingDegrees: position?.bearingDegrees ?? null,
    bearingAccuracyDegrees: null,
    movementDirection: "UNKNOWN",
    pathZone,
    predictedPathCrossing: false,
    ttcSeconds: null,
    warningTriggered,
    warningLevel: warningTriggered ? "DANGER" : "NONE",
    audioTriggered: false,
    cameraSource: "web_camera",
    source: "ios_web",
    deviceType: detectDeviceType(),
    trackId: detection.trackId ?? null,
    detectedAtEpochMillis: Date.now(),
    boundingBox: box
  };
}

async function maybeSaveDetection(detection) {
  const signature = detectionSignature(detection);
  const lastSavedAt = lastUploadBySignature.get(signature) || 0;
  const now = Date.now();
  if (now - lastSavedAt < FIRESTORE_COOLDOWN_MS) {
    console.debug("FirestoreWebDetection skip duplicate", signature, detection.label);
    return;
  }

  if (detection.warningTriggered) {
    detection.audioTriggered = playWarningTone();
  }

  lastUploadBySignature.set(signature, now);
  console.info(
    `FirestoreWebDetection label=${detection.label} confidence=${detection.confidence.toFixed(2)} risk=${detection.riskLevel} lat=${detection.latitude ?? "N/A"} lng=${detection.longitude ?? "N/A"}`
  );
  await addDoc(collection(db, "detections"), {
    ...detection,
    confidence: Number(detection.confidence),
    createdAt: serverTimestamp()
  });
  detectionCount += 1;
  totalCount.textContent = String(detectionCount);
}

function detectionSignature(detection) {
  const centerX = ((detection.boundingBox.left + detection.boundingBox.right) / 2) / canvas.width;
  const centerY = ((detection.boundingBox.top + detection.boundingBox.bottom) / 2) / canvas.height;
  const width = (detection.boundingBox.right - detection.boundingBox.left) / canvas.width;
  const height = (detection.boundingBox.bottom - detection.boundingBox.top) / canvas.height;
  const bucketX = Math.floor(Math.max(0, Math.min(0.999, centerX)) * POSITION_BUCKETS);
  const bucketY = Math.floor(Math.max(0, Math.min(0.999, centerY)) * POSITION_BUCKETS);
  const bucketW = Math.floor(Math.max(0, Math.min(0.999, width)) * POSITION_BUCKETS);
  const bucketH = Math.floor(Math.max(0, Math.min(0.999, height)) * POSITION_BUCKETS);
  return `box:${bucketX}:${bucketY}:${bucketW}:${bucketH}`;
}

function confidenceThresholdForLabel(label) {
  return ANIMAL_CLASSES.has(label) ? ANIMAL_MIN_CONFIDENCE : DEFAULT_MIN_CONFIDENCE;
}

function readNumberParam(name, fallback) {
  const value = new URLSearchParams(window.location.search).get(name);
  if (value === null) return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function logRuntimeConfiguration() {
  console.info("AIRACARE WEB CONFIG", {
    modelInputSize: MODEL_INPUT_SIZE,
    requestedInputSize: REQUESTED_INPUT_SIZE,
    requestedInputSizeSupported: REQUESTED_INPUT_SIZE === MODEL_INPUT_SIZE,
    personThreshold: DEFAULT_MIN_CONFIDENCE,
    animalThreshold: ANIMAL_MIN_CONFIDENCE,
    nmsIouThreshold: IOU_THRESHOLD,
    trackIouThreshold: TRACK_IOU_THRESHOLD,
    preprocessing: "letterbox RGB normalized NCHW",
    targetClasses: TARGET_CLASSES
  });
  if (REQUESTED_INPUT_SIZE !== MODEL_INPUT_SIZE) {
    console.warn(
      `Requested inputSize=${REQUESTED_INPUT_SIZE}, but the deployed ONNX model is static ${MODEL_INPUT_SIZE}x${MODEL_INPUT_SIZE}. Re-export the model for 960/1280 before using a larger web input.`
    );
  }
}

function confirmStableDetections(detections) {
  const now = Date.now();
  activeTracks = activeTracks.filter((track) => now - track.lastSeenAt <= TRACK_MAX_AGE_MS);
  const matchedTrackIds = new Set();

  for (const detection of detections) {
    const track = findBestTrack(detection, matchedTrackIds);
    const targetTrack = track || createTrack();
    matchedTrackIds.add(targetTrack.id);
    updateTrack(targetTrack, detection, now);
  }

  const confirmed = [];
  for (const track of activeTracks) {
    if (matchedTrackIds.has(track.id) && track.confirmedLabel) {
      confirmed.push({
        ...track.latestDetection,
        label: track.confirmedLabel,
        trackId: track.id
      });
    }
  }
  return confirmed;
}

function findBestTrack(detection, excludedTrackIds) {
  let bestTrack = null;
  let bestIou = 0;
  for (const track of activeTracks) {
    if (excludedTrackIds.has(track.id)) continue;
    const overlap = iou(track.boundingBox, detection.boundingBox);
    if (overlap > bestIou) {
      bestTrack = track;
      bestIou = overlap;
    }
  }
  return bestIou >= TRACK_IOU_THRESHOLD ? bestTrack : null;
}

function createTrack() {
  const track = {
    id: nextTrackId,
    boundingBox: null,
    latestDetection: null,
    firstSeenAt: Date.now(),
    lastSeenAt: Date.now(),
    currentLabel: null,
    currentLabelStartedAt: Date.now(),
    currentLabelFrames: 0,
    confirmedLabel: null
  };
  nextTrackId += 1;
  activeTracks.push(track);
  return track;
}

function updateTrack(track, detection, now) {
  if (track.currentLabel === detection.label) {
    track.currentLabelFrames += 1;
  } else {
    console.debug(
      `YoloTemporalCandidate track=${track.id} previous=${track.currentLabel ?? "none"} next=${detection.label} confidence=${detection.confidence.toFixed(3)}`
    );
    track.currentLabel = detection.label;
    track.currentLabelStartedAt = now;
    track.currentLabelFrames = 1;
    track.confirmedLabel = null;
  }

  track.boundingBox = detection.boundingBox;
  track.latestDetection = detection;
  track.lastSeenAt = now;

  const requiredFrames = ANIMAL_CLASSES.has(detection.label)
    ? ANIMAL_CONFIRMATION_FRAMES
    : PERSON_CONFIRMATION_FRAMES;
  const stableDuration = now - track.currentLabelStartedAt;
  const isConfirmed = track.currentLabelFrames >= requiredFrames
    || (ANIMAL_CLASSES.has(detection.label) && stableDuration >= ANIMAL_CONFIRMATION_MS);

  if (isConfirmed) {
    if (track.confirmedLabel !== detection.label) {
      console.info(
        `YoloTemporalConfirmed track=${track.id} label=${detection.label} frames=${track.currentLabelFrames} stableMs=${stableDuration} confidence=${detection.confidence.toFixed(3)}`
      );
    }
    track.confirmedLabel = detection.label;
  } else {
    console.debug(
      `YoloTemporalPending track=${track.id} label=${detection.label} frames=${track.currentLabelFrames}/${requiredFrames} stableMs=${stableDuration} confidence=${detection.confidence.toFixed(3)}`
    );
  }
}

function drawDetections(detections) {
  resizeCanvas();
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.lineWidth = 3;
  ctx.font = "16px system-ui";

  for (const detection of detections) {
    const box = detection.boundingBox;
    const color = detection.riskLevel === "HIGH" ? "#f97373" : "#42d392";
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.strokeRect(box.left, box.top, box.right - box.left, box.bottom - box.top);
    const label = `${detection.label} ${(detection.confidence * 100).toFixed(0)}%`;
    const textWidth = ctx.measureText(label).width + 12;
    const textY = Math.max(24, box.top);
    ctx.fillRect(box.left, textY - 22, textWidth, 22);
    ctx.fillStyle = "#061014";
    ctx.fillText(label, box.left + 6, textY - 6);
  }
}

function updateLatest(detections) {
  if (detections.length === 0) {
    warningBanner.hidden = true;
    return;
  }
  const primary = detections[0];
  latestLabel.textContent = primary.label;
  latestRisk.textContent = primary.riskLevel;
  if (primary.warningTriggered) {
    warningBanner.textContent = `${primary.label.toUpperCase()} AHEAD`;
    warningBanner.hidden = false;
  } else {
    warningBanner.hidden = true;
  }
}

function estimatePathZone(left, top, width, height) {
  const centerX = (left + width / 2) / canvas.width;
  const centerY = (top + height / 2) / canvas.height;
  if (centerY >= 0.45 && centerX >= 0.35 && centerX <= 0.65) return "IN_PATH";
  if (centerY >= 0.35 && centerX >= 0.18 && centerX <= 0.82) return "NEAR_PATH";
  return "OUTSIDE_PATH";
}

function estimateRiskLevel(pathZone, confidence) {
  if (pathZone === "IN_PATH" && confidence >= 0.7) return "HIGH";
  if (pathZone === "NEAR_PATH") return "MEDIUM";
  return "LOW";
}

function resizeCanvas() {
  const rect = video.getBoundingClientRect();
  const width = Math.max(1, Math.round(rect.width));
  const height = Math.max(1, Math.round(rect.height));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
}

function listenToRecentDetections() {
  const detectionsQuery = query(
    collection(db, "detections"),
    orderBy("createdAt", "desc"),
    limit(8)
  );
  onSnapshot(detectionsQuery, (snapshot) => {
    historyList.innerHTML = "";
    snapshot.docs.forEach((doc) => {
      const data = doc.data();
      const card = document.createElement("article");
      card.className = "history-card";
      const confidence = typeof data.confidence === "number" ? `${(data.confidence * 100).toFixed(0)}%` : "N/A";
      const source = data.source || "android_app";
      card.innerHTML = `
        <strong>${data.label || "unknown"} - ${confidence}</strong>
        <p>Risk: ${data.riskLevel || "N/A"} - Source: ${source}</p>
        <p>${formatTime(data.detectedAtEpochMillis)}</p>
      `;
      historyList.appendChild(card);
    });
  }, (error) => {
    console.error(error);
    setStatus("Firestore read failed");
  });
}

async function startLocationWatch() {
  if (!("geolocation" in navigator)) {
    console.info("Web location unavailable: geolocation API missing");
    return;
  }
  navigator.geolocation.watchPosition(
    (position) => {
      latestPosition = {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        speedKmh: typeof position.coords.speed === "number" ? position.coords.speed * 3.6 : null,
        bearingDegrees: typeof position.coords.heading === "number" ? position.coords.heading : null,
        accuracyMeters: position.coords.accuracy,
        timestamp: position.timestamp
      };
    },
    (error) => {
      console.info(`Web location unavailable: ${error.message}`);
    },
    {
      enableHighAccuracy: true,
      maximumAge: 3000,
      timeout: 10000
    }
  );
}

function freshPosition() {
  if (!latestPosition) return null;
  return Date.now() - latestPosition.timestamp <= 8000 ? latestPosition : null;
}

function playWarningTone() {
  try {
    audioContext = audioContext || new AudioContext();
    const oscillator = audioContext.createOscillator();
    const gain = audioContext.createGain();
    oscillator.frequency.value = 880;
    gain.gain.value = 0.08;
    oscillator.connect(gain);
    gain.connect(audioContext.destination);
    oscillator.start();
    oscillator.stop(audioContext.currentTime + 0.22);
    return true;
  } catch (error) {
    console.info("Audio warning unavailable", error);
    return false;
  }
}

function formatTime(epochMillis) {
  if (!epochMillis) return "time unavailable";
  return new Date(epochMillis).toLocaleString();
}

function setStatus(message) {
  connectionStatus.textContent = message;
}

function detectDeviceType() {
  const ua = navigator.userAgent || "";
  if (/iPhone/i.test(ua)) return "iPhone";
  if (/iPad/i.test(ua)) return "iPad";
  if (/Macintosh/i.test(ua)) return "Mac";
  if (/Android/i.test(ua)) return "Android";
  return "Web";
}

async function assetExists(path) {
  try {
    const response = await fetch(path, { method: "HEAD", cache: "no-store" });
    return response.ok;
  } catch {
    return false;
  }
}

async function loadLabels() {
  if (!(await assetExists(CUSTOM_LABELS_PATH))) return TARGET_CLASSES;
  const response = await fetch(CUSTOM_LABELS_PATH, { cache: "no-store" });
  const text = await response.text();
  return text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

class CocoSsdDetector {
  constructor(model) {
    this.model = model;
    this.supportedClasses = new Set(["person", "dog", "cat", "horse", "cow", "elephant"]);
  }

  static async create() {
    const model = await cocoSsd.load({ base: "lite_mobilenet_v2" });
    return new CocoSsdDetector(model);
  }

  async detect(sourceVideo) {
    const predictions = await this.model.detect(sourceVideo);
    return predictions.map((prediction) => {
      const [left, top, width, height] = prediction.bbox;
      return {
        label: prediction.class,
        confidence: prediction.score,
        boundingBox: {
          left,
          top,
          right: left + width,
          bottom: top + height
        }
      };
    });
  }
}

class YoloOnnxDetector {
  constructor(session, labels, inputName, outputName) {
    this.session = session;
    this.labels = labels;
    this.inputName = inputName;
    this.outputName = outputName;
    this.supportedClasses = new Set(labels);
    this.inputCanvas = document.createElement("canvas");
    this.inputCanvas.width = MODEL_INPUT_SIZE;
    this.inputCanvas.height = MODEL_INPUT_SIZE;
    this.inputContext = this.inputCanvas.getContext("2d", { willReadFrequently: true });
    this.hasLoggedOutputShape = false;
  }

  static async create(modelPath, labels) {
    const session = await ort.InferenceSession.create(modelPath, {
      executionProviders: ["wasm"]
    });
    console.info("YOLO DEBUG MODEL FILE PATH", modelPath);
    console.info("YOLO DEBUG MODEL INPUT SHAPE", [1, 3, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE]);
    console.info("YOLO DEBUG NUMBER OF CLASSES", labels.length);
    console.info("YOLO DEBUG LABEL ARRAY", labels);
    console.info("YOLO DEBUG INPUT NAME", session.inputNames[0]);
    console.info("YOLO DEBUG OUTPUT NAME", session.outputNames[0]);
    return new YoloOnnxDetector(session, labels, session.inputNames[0], session.outputNames[0]);
  }

  async detect(sourceVideo, displayCanvas) {
    const { tensor, meta } = this.preprocess(sourceVideo);
    const output = await this.session.run({ [this.inputName]: tensor });
    const data = output[this.outputName].data;
    const dims = output[this.outputName].dims;
    if (!this.hasLoggedOutputShape) {
      console.info("YOLO DEBUG MODEL OUTPUT SHAPE", dims);
      this.hasLoggedOutputShape = true;
    }
    return this.postprocess(data, dims, displayCanvas.width, displayCanvas.height, meta);
  }

  preprocess(sourceVideo) {
    const sourceWidth = sourceVideo.videoWidth || MODEL_INPUT_SIZE;
    const sourceHeight = sourceVideo.videoHeight || MODEL_INPUT_SIZE;
    const scale = Math.min(MODEL_INPUT_SIZE / sourceWidth, MODEL_INPUT_SIZE / sourceHeight);
    const resizedWidth = Math.round(sourceWidth * scale);
    const resizedHeight = Math.round(sourceHeight * scale);
    const padLeft = Math.floor((MODEL_INPUT_SIZE - resizedWidth) / 2);
    const padTop = Math.floor((MODEL_INPUT_SIZE - resizedHeight) / 2);

    this.inputContext.fillStyle = "rgb(114, 114, 114)";
    this.inputContext.fillRect(0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE);
    this.inputContext.drawImage(
      sourceVideo,
      0,
      0,
      sourceWidth,
      sourceHeight,
      padLeft,
      padTop,
      resizedWidth,
      resizedHeight
    );
    const image = this.inputContext.getImageData(0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE).data;
    const input = new Float32Array(3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE);
    for (let i = 0, pixel = 0; i < image.length; i += 4, pixel += 1) {
      input[pixel] = image[i] / 255;
      input[pixel + MODEL_INPUT_SIZE * MODEL_INPUT_SIZE] = image[i + 1] / 255;
      input[pixel + MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 2] = image[i + 2] / 255;
    }
    return {
      tensor: new ort.Tensor("float32", input, [1, 3, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE]),
      meta: {
        sourceWidth,
        sourceHeight,
        scale,
        padLeft,
        padTop
      }
    };
  }

  postprocess(data, dims, displayWidth, displayHeight, meta) {
    const rows = this.toRows(data, dims);
    const detections = [];
    const rawCandidates = [];
    const afterConfidence = [];
    let rawCandidateLogs = 0;
    for (const row of rows) {
      const classScores = row.slice(4, 4 + this.labels.length);
      let classIndex = 0;
      let confidence = classScores[0] || 0;
      for (let i = 1; i < classScores.length; i += 1) {
        if (classScores[i] > confidence) {
          confidence = classScores[i];
          classIndex = i;
        }
      }
      const label = this.labels[classIndex] || `class_${classIndex}`;
      const [rawCenterX, rawCenterY, rawWidth, rawHeight] = row;
      const rawBox = {
        centerX: rawCenterX,
        centerY: rawCenterY,
        width: rawWidth,
        height: rawHeight
      };
      const finalBox = this.modelBoxToDisplayBox(rawBox, displayWidth, displayHeight, meta);
      const rawCandidate = {
        classId: classIndex,
        label,
        confidence,
        rawBox,
        finalBox
      };
      rawCandidates.push(rawCandidate);

      if (DEBUG_YOLO && confidence >= DEBUG_CANDIDATE_THRESHOLD && rawCandidateLogs < DEBUG_CANDIDATE_LOG_LIMIT) {
        console.debug("YOLO CANDIDATE", {
          rawClassId: classIndex,
          classLabel: label,
          rawClassScore: classScores[classIndex],
          objectness: null,
          finalConfidence: confidence,
          mappedClassLabel: label,
          rawBoundingBox: rawBox,
          finalBoundingBox: finalBox
        });
        rawCandidateLogs += 1;
      }
      if (confidence < confidenceThresholdForLabel(label)) continue;
      afterConfidence.push(rawCandidate);
      detections.push({
        label,
        confidence,
        classId: classIndex,
        boundingBox: finalBox
      });
    }
    if (DEBUG_YOLO && rawCandidateLogs >= DEBUG_CANDIDATE_LOG_LIMIT) {
      console.debug(
        `YOLO CANDIDATE log limit reached; logged first ${DEBUG_CANDIDATE_LOG_LIMIT} candidates above ${DEBUG_CANDIDATE_THRESHOLD}`
      );
    }
    const afterNms = nonMaxSuppression(detections);
    logDetectionStages(rawCandidates, afterConfidence, afterNms);
    return afterNms;
  }

  modelBoxToDisplayBox(rawBox, displayWidth, displayHeight, meta) {
    const modelLeft = rawBox.centerX - rawBox.width / 2;
    const modelTop = rawBox.centerY - rawBox.height / 2;
    const modelRight = rawBox.centerX + rawBox.width / 2;
    const modelBottom = rawBox.centerY + rawBox.height / 2;

    const sourceLeft = (modelLeft - meta.padLeft) / meta.scale;
    const sourceTop = (modelTop - meta.padTop) / meta.scale;
    const sourceRight = (modelRight - meta.padLeft) / meta.scale;
    const sourceBottom = (modelBottom - meta.padTop) / meta.scale;

    const coverScale = Math.max(displayWidth / meta.sourceWidth, displayHeight / meta.sourceHeight);
    const renderedWidth = meta.sourceWidth * coverScale;
    const renderedHeight = meta.sourceHeight * coverScale;
    const offsetX = (displayWidth - renderedWidth) / 2;
    const offsetY = (displayHeight - renderedHeight) / 2;

    return {
      left: clamp(sourceLeft * coverScale + offsetX, 0, displayWidth),
      top: clamp(sourceTop * coverScale + offsetY, 0, displayHeight),
      right: clamp(sourceRight * coverScale + offsetX, 0, displayWidth),
      bottom: clamp(sourceBottom * coverScale + offsetY, 0, displayHeight)
    };
  }

  toRows(data, dims) {
    if (dims.length !== 3) return [];
    const a = dims[1];
    const b = dims[2];
    const channels = 4 + this.labels.length;
    const rows = [];
    if (a === channels) {
      for (let col = 0; col < b; col += 1) {
        const row = [];
        for (let ch = 0; ch < channels; ch += 1) row.push(data[ch * b + col]);
        rows.push(row);
      }
      return rows;
    }
    if (b >= channels) {
      for (let rowIndex = 0; rowIndex < a; rowIndex += 1) {
        rows.push(Array.from(data.slice(rowIndex * b, rowIndex * b + channels)));
      }
    }
    return rows;
  }
}

function nonMaxSuppression(detections) {
  const sorted = [...detections].sort((a, b) => b.confidence - a.confidence);
  const kept = [];
  for (const candidate of sorted) {
    if (kept.some((item) => iou(item.boundingBox, candidate.boundingBox) > IOU_THRESHOLD)) {
      console.debug(
        `YoloNms suppress label=${candidate.label} confidence=${candidate.confidence.toFixed(3)}`
      );
      continue;
    }
    kept.push(candidate);
  }
  return kept;
}

function logDetectionStages(rawCandidates, afterConfidence, afterNms) {
  if (!DEBUG_YOLO) return;
  const rawAboveDebug = rawCandidates
    .filter((candidate) => candidate.confidence >= DEBUG_CANDIDATE_THRESHOLD)
    .sort((a, b) => b.confidence - a.confidence);
  const rawPeople = rawCandidates
    .filter((candidate) => candidate.label === "person")
    .sort((a, b) => b.confidence - a.confidence);
  const rawPeopleAtTestThresholds = {
    "0.30": rawPeople.filter((candidate) => candidate.confidence >= 0.3).length,
    "0.40": rawPeople.filter((candidate) => candidate.confidence >= 0.4).length,
    "0.50": rawPeople.filter((candidate) => candidate.confidence >= 0.5).length,
    current: rawPeople.filter((candidate) => candidate.confidence >= DEFAULT_MIN_CONFIDENCE).length
  };

  console.debug("YOLO STAGE SUMMARY", {
    modelInputSize: MODEL_INPUT_SIZE,
    requestedInputSize: REQUESTED_INPUT_SIZE,
    requestedInputSizeSupported: REQUESTED_INPUT_SIZE === MODEL_INPUT_SIZE,
    personThreshold: DEFAULT_MIN_CONFIDENCE,
    animalThreshold: ANIMAL_MIN_CONFIDENCE,
    nmsIouThreshold: IOU_THRESHOLD,
    rawCandidateCountAboveDebug: rawAboveDebug.length,
    rawPersonCandidatesAtThresholds: rawPeopleAtTestThresholds,
    afterConfidenceCount: afterConfidence.length,
    afterNmsCount: afterNms.length,
    topRawCandidates: rawAboveDebug.slice(0, DEBUG_TOP_CANDIDATE_COUNT).map(formatCandidateForLog),
    topRawPeople: rawPeople.slice(0, DEBUG_TOP_CANDIDATE_COUNT).map(formatCandidateForLog),
    afterConfidence: afterConfidence.slice(0, DEBUG_TOP_CANDIDATE_COUNT).map(formatCandidateForLog),
    afterNms: afterNms.slice(0, DEBUG_TOP_CANDIDATE_COUNT).map((detection) => ({
      classId: detection.classId,
      label: detection.label,
      confidence: Number(detection.confidence.toFixed(4)),
      box: roundBox(detection.boundingBox)
    }))
  });
}

function formatCandidateForLog(candidate) {
  return {
    classId: candidate.classId,
    label: candidate.label,
    confidence: Number(candidate.confidence.toFixed(4)),
    rawBox: roundBox(candidate.rawBox),
    finalBox: roundBox(candidate.finalBox)
  };
}

function roundBox(box) {
  return Object.fromEntries(
    Object.entries(box).map(([key, value]) => [key, Number(value.toFixed(2))])
  );
}

function iou(a, b) {
  const left = Math.max(a.left, b.left);
  const top = Math.max(a.top, b.top);
  const right = Math.min(a.right, b.right);
  const bottom = Math.min(a.bottom, b.bottom);
  const intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
  const areaA = Math.max(0, a.right - a.left) * Math.max(0, a.bottom - a.top);
  const areaB = Math.max(0, b.right - b.left) * Math.max(0, b.bottom - b.top);
  const union = areaA + areaB - intersection;
  return union <= 0 ? 0 : intersection / union;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
