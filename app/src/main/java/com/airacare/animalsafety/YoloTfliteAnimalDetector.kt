package com.airacare.animalsafety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class YoloTfliteAnimalDetector(
    context: Context,
    private val modelPreference: ModelPreference = ModelPreference.BEST_AVAILABLE,
    private val onUprightFrame: (Bitmap) -> Unit = {}
) : AnimalDetector {
    private val interpreter: Interpreter
    private val interpreterBackend: String
    private val labels: List<String>
    private val personClassIndex: Int
    private val modelAssetName: String
    private val labelsAssetName: String
    private val modelSource: String
    private val inputTensor: Tensor
    private val outputTensors: List<Tensor>
    private val airacareTargetLabels: Set<String>
    private val inputShape: IntArray
    private val inputType: DataType
    private val inputUsesNchw: Boolean
    private val inputWidth: Int
    private val inputHeight: Int
    private var frameIndex = 0L
    private val visibilityEvaluator = SceneVisibilityEvaluator()
    private var currentFrameVisibility: SceneVisibility = SceneVisibility.DEFAULT
    private var cachedVisibility: SceneVisibility = SceneVisibility.DEFAULT
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * CHANNELS * bytesPerChannel()).order(ByteOrder.nativeOrder())
    }
    private val outputBuffers: Map<Int, Any> by lazy { allocateOutputBuffers() }
    private val letterboxBitmap: Bitmap by lazy { Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888) }
    private val letterboxCanvas: Canvas by lazy { Canvas(letterboxBitmap) }
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var reusablePixels = IntArray(0)

    init {
        val selectedAssets = selectModelAssets(context)
        modelAssetName = selectedAssets.modelAsset
        labelsAssetName = selectedAssets.labelsAsset
        modelSource = selectedAssets.source
        labels = context.assets.open(labelsAssetName).bufferedReader().useLines { lines -> lines.map { it.removePrefix("\uFEFF").trim() }.filter { it.isNotEmpty() }.toList() }
        personClassIndex = labels.indexOfFirst { it.equals("person", ignoreCase = true) }
        Log.i(TAG, "Model selection: source=$modelSource model=$modelAssetName labels=$labelsAssetName")
        Log.i(TAG, "Labels loaded: count=${labels.size}, class0=${labels.getOrNull(0)}")
        airacareTargetLabels = availableAiracareTargetLabels()
        logAiracareTargetLabelAvailability()
        val model = loadModel(context, modelAssetName)
        val interpreterSelection = createInterpreter(model)
        interpreter = interpreterSelection.interpreter
        interpreterBackend = interpreterSelection.backend

        inputTensor = interpreter.getInputTensor(0)
        inputShape = inputTensor.shape()
        inputType = inputTensor.dataType()
        if (inputShape.size != 4) {
            throw IllegalStateException("Unsupported input shape: ${inputShape.contentToString()}")
        }
        inputUsesNchw = inputShape[1] == 3 && inputShape[3] != 3
        inputHeight = if (inputUsesNchw) inputShape[2] else inputShape[1]
        inputWidth = if (inputUsesNchw) inputShape[3] else inputShape[2]

        outputTensors = List(interpreter.outputTensorCount) { interpreter.getOutputTensor(it) }

        Log.i(TAG, "Model loaded: $modelAssetName source=$modelSource backend=$interpreterBackend")
        Log.i(TAG, "Input tensor[0]: shape=${inputShape.contentToString()} dtype=$inputType quant=${inputTensor.quantizationParams()} layout=${if (inputUsesNchw) "NCHW" else "NHWC"} resolved=${inputWidth}x$inputHeight")
        outputTensors.forEachIndexed { index, tensor ->
            Log.i(TAG, "Output tensor[$index]: shape=${tensor.shape().contentToString()} dtype=${tensor.dataType()} quant=${tensor.quantizationParams()} elements=${tensor.numElements()} bytes=${tensor.numBytes()}")
        }
    }

    private fun createInterpreter(model: MappedByteBuffer): InterpreterSelection {
        val options = Interpreter.Options()
            .setNumThreads(INTERPRETER_THREADS)
            .setUseXNNPACK(true)
        Log.i(TAG, "Runtime backend selected: CPU_XNNPACK. NNAPI disabled for phone stability; enable only after device-specific benchmark.")
        return InterpreterSelection(Interpreter(model, options), "CPU_XNNPACK")
    }

    override fun analyze(image: ImageProxy): List<AnimalDetection> {
        val conversionStarted = System.nanoTime()
        val rotationDegrees = image.imageInfo.rotationDegrees
        val sourceBitmap = ImageProxyBitmapConverter.toBitmap(image)
        val conversionMs = elapsedMsSince(conversionStarted)
        return analyzeBitmapFrame(sourceBitmap, rotationDegrees, conversionMs)
    }

    override fun analyze(bitmap: Bitmap): List<AnimalDetection> {
        return analyzeBitmapFrame(bitmap, 0, null)
    }

    private fun analyzeBitmapFrame(sourceBitmap: Bitmap, rotationDegrees: Int, conversionMs: Float?): List<AnimalDetection> {
        val frameNumber = ++frameIndex
        val logDiagnostics = shouldLogDiagnostics(frameNumber)
        val pipelineStarted = System.nanoTime()
        val rotateStarted = System.nanoTime()
        val uprightBitmap = rotateBitmap(sourceBitmap, rotationDegrees)
        val rotateMs = elapsedMsSince(rotateStarted)
        val visibilityStarted = System.nanoTime()
        val initialVisibility = if (shouldEvaluateVisibility(frameNumber)) {
            visibilityEvaluator.evaluate(uprightBitmap, enhancementEnabled = false).also { cachedVisibility = it }
        } else {
            cachedVisibility
        }
        val shouldEnhance = shouldEnhanceFrame(initialVisibility)
        val modelBitmap = uprightBitmap
        val visibility = if (shouldEnhance) initialVisibility.copy(enhancementEnabled = true) else initialVisibility
        val visibilityMs = elapsedMsSince(visibilityStarted)
        currentFrameVisibility = visibility
        SceneVisibilityMonitor.update(visibility)
        if (logDiagnostics) {
            SceneVisibilityEvaluator.log(frameNumber, visibility, effectivePersonThreshold(), effectiveOtherThreshold())
        }
        val letterboxStarted = System.nanoTime()
        val inputBitmap = letterbox(modelBitmap, inputWidth, inputHeight)
        val letterboxMs = elapsedMsSince(letterboxStarted)
        val preprocessStarted = System.nanoTime()
        val input = bitmapToInputBuffer(inputBitmap)
        val preprocessMs = elapsedMsSince(preprocessStarted)
        rewindOutputBuffers()
        val outputs = outputBuffers
        val started = System.nanoTime()

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        } catch (exception: Exception) {
            Log.e(TAG, "TFLite inference failed frame=$frameNumber bitmap=${sourceBitmap.width}x${sourceBitmap.height} rotation=$rotationDegrees input=${inputWidth}x$inputHeight layout=${if (inputUsesNchw) "NCHW" else "NHWC"}", exception)
            return emptyList()
        }

        val elapsedMs = elapsedMsSince(started)
        val postprocessStarted = System.nanoTime()
        val parsed = parseBestOutput(
            outputs = outputs,
            sourceWidth = uprightBitmap.width,
            sourceHeight = uprightBitmap.height
        )
        val rawDetections = parsed.detections
        val detections = filterAiracareDetections(rawDetections, frameNumber)
        val postprocessMs = elapsedMsSince(postprocessStarted)
        val totalMs = elapsedMsSince(pipelineStarted)
        DetectorTimingMonitor.update(
            DetectorTimingSnapshot(
                conversionMs = conversionMs,
                rotateMs = rotateMs,
                visibilityMs = visibilityMs,
                letterboxMs = letterboxMs,
                preprocessMs = preprocessMs,
                inferenceMs = elapsedMs,
                postprocessMs = postprocessMs,
                detectorTotalMs = totalMs,
                backend = interpreterBackend,
                visibilityState = visibility.state,
                enhancementEnabled = visibility.enhancementEnabled
            )
        )

        val maxConfidenceText = String.format("%.3f", parsed.maxConfidence)
        val inferenceMsText = String.format("%.1f", elapsedMs)
        if (logDiagnostics) {
            Log.d(
                TAG,
                "frame=$frameNumber src=${sourceBitmap.width}x${sourceBitmap.height} upright=${uprightBitmap.width}x${uprightBitmap.height} rotation=$rotationDegrees input=${inputWidth}x${inputHeight} rawCandidates=${parsed.rawCandidates} rawYoloDetections=${rawDetections.size} finalDetections=${detections.size} maxConfidence=$maxConfidenceText inferenceMs=$inferenceMsText"
            )
            Log.d(TAG, "frame=$frameNumber Raw YOLO detections: ${parsed.rawPredictions} Above confidence threshold: ${parsed.rawCandidates} After Airacare class filter: ${detections.size} After NMS: ${detections.size}")
            Log.i(TAG, "TIMING frame=$frameNumber backend=$interpreterBackend convertMs=${conversionMs?.let { String.format("%.1f", it) } ?: "N/A"} rotateMs=${String.format("%.1f", rotateMs)} visibilityMs=${String.format("%.1f", visibilityMs)} letterboxMs=${String.format("%.1f", letterboxMs)} preprocessMs=${String.format("%.1f", preprocessMs)} inferenceMs=${String.format("%.1f", elapsedMs)} postprocessMs=${String.format("%.1f", postprocessMs)} detectorTotalMs=${String.format("%.1f", totalMs)}")
            if (detections.isNotEmpty()) {
                val summary = detections.joinToString { detection ->
                    "${detection.label}:${String.format("%.2f", detection.confidence)}"
                }
                Log.d(TAG, "frame=$frameNumber detections=$summary")
            }
        }
        if (detections.any { detection -> detection.label.equals("dog", ignoreCase = true) || detection.label.equals("cat", ignoreCase = true) }) {
            onUprightFrame(uprightBitmap.copy(Bitmap.Config.ARGB_8888, false))
        }
        return detections
    }
    private fun filterAiracareDetections(detections: List<AnimalDetection>, frameNumber: Long): List<AnimalDetection> {
        val logDiagnostics = shouldLogDiagnostics(frameNumber)
        val filtered = detections.filter { detection ->
            detection.label.lowercase() in airacareTargetLabels
        }
        val ignored = detections
            .filterNot { detection -> detection.label.lowercase() in airacareTargetLabels }
            .groupingBy { detection -> detection.label.lowercase() }
            .eachCount()
        if (logDiagnostics) {
            Log.d(TAG, "frame=$frameNumber Airacare detections after filter: ${filtered.size}")
            if (ignored.isNotEmpty()) {
                Log.d(TAG, "frame=$frameNumber ignoredClasses=$ignored")
            }
        }
        return filtered
    }

    private fun shouldLogDiagnostics(frameNumber: Long): Boolean {
        val lowLightDiagnostic = currentFrameVisibility.state == VisibilityState.LOW_LIGHT || currentFrameVisibility.state == VisibilityState.NIGHT || currentFrameVisibility.state == VisibilityState.POOR_VISIBILITY
        return lowLightDiagnostic || frameNumber <= DEBUG_CANDIDATE_FRAMES || frameNumber % DIAGNOSTIC_LOG_INTERVAL_FRAMES == 0L
    }

    private fun shouldEvaluateVisibility(frameNumber: Long): Boolean {
        return frameNumber <= DEBUG_CANDIDATE_FRAMES || frameNumber % VISIBILITY_EVAL_INTERVAL_FRAMES == 0L
    }

    private fun logAiracareTargetLabelAvailability() {
        val available = airacareTargetLabels.toList()
        val missing = AIRACARE_REQUESTED_LABELS - available.toSet()
        Log.i(TAG, "Airacare target labels available=$available missing=$missing active=$airacareTargetLabels")
    }
    override fun close() {
        interpreter.close()
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = inputBuffer
        buffer.clear()
        val pixelCount = inputWidth * inputHeight
        if (reusablePixels.size != pixelCount) {
            reusablePixels = IntArray(pixelCount)
        }
        bitmap.getPixels(reusablePixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val quant = inputTensor.quantizationParams()

        if (inputUsesNchw) {
            writeChannel(buffer, reusablePixels, 0, quant.scale, quant.zeroPoint)
            writeChannel(buffer, reusablePixels, 1, quant.scale, quant.zeroPoint)
            writeChannel(buffer, reusablePixels, 2, quant.scale, quant.zeroPoint)
        } else {
            reusablePixels.forEach { pixel ->
                putInputValue(buffer, Color.red(pixel) / 255f, quant.scale, quant.zeroPoint)
                putInputValue(buffer, Color.green(pixel) / 255f, quant.scale, quant.zeroPoint)
                putInputValue(buffer, Color.blue(pixel) / 255f, quant.scale, quant.zeroPoint)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun elapsedMsSince(startedNanos: Long): Float {
        return (System.nanoTime() - startedNanos) / 1_000_000f
    }

    private fun bytesPerChannel(): Int {
        return if (inputType == DataType.FLOAT32) FLOAT_BYTES else 1
    }

    private fun rewindOutputBuffers() {
        outputBuffers.values.forEach { output ->
            if (output is ByteBuffer) output.clear()
        }
    }

    private fun writeChannel(buffer: ByteBuffer, pixels: IntArray, channel: Int, scale: Float, zeroPoint: Int) {
        pixels.forEach { pixel ->
            val value = when (channel) {
                0 -> Color.red(pixel) / 255f
                1 -> Color.green(pixel) / 255f
                else -> Color.blue(pixel) / 255f
            }
            putInputValue(buffer, value, scale, zeroPoint)
        }
    }

    private fun putInputValue(buffer: ByteBuffer, value: Float, scale: Float, zeroPoint: Int) {
        val preparedValue = prepareInputValue(value)
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat(preparedValue)
            DataType.UINT8 -> buffer.put(((preparedValue / scale) + zeroPoint).roundToInt().coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put(((preparedValue / scale) + zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
            else -> throw IllegalStateException("Unsupported input type: $inputType")
        }
    }

    private fun prepareInputValue(value: Float): Float {
        if (!currentFrameVisibility.enhancementEnabled) return value
        val boosted = when (currentFrameVisibility.state) {
            VisibilityState.LOW_LIGHT -> value * LOW_LIGHT_INPUT_GAIN + LOW_LIGHT_INPUT_LIFT
            VisibilityState.NIGHT, VisibilityState.POOR_VISIBILITY -> value * NIGHT_INPUT_GAIN + NIGHT_INPUT_LIFT
            else -> value
        }
        return boosted.coerceIn(0f, 1f)
    }

    private fun allocateOutputBuffers(): Map<Int, Any> {
        return outputTensors.mapIndexed { index, tensor ->
            index to ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }.toMap()
    }

    private fun parseBestOutput(outputs: Map<Int, Any>, sourceWidth: Int, sourceHeight: Int): ParseResult {
        var best = ParseResult(emptyList(), rawPredictions = 0, rawCandidates = 0, maxConfidence = 0f)
        outputs.forEach { (index, value) ->
            val tensor = outputTensors[index]
            val flat = readOutputAsFloats(value as ByteBuffer, tensor)
            val parsed = parseOutputTensor(index, tensor.shape(), flat, sourceWidth, sourceHeight)
            if (parsed.detections.size > best.detections.size || (parsed.detections.size == best.detections.size && parsed.rawCandidates > best.rawCandidates)) {
                best = parsed
            }
        }
        return best
    }

    private fun readOutputAsFloats(output: ByteBuffer, tensor: Tensor): FloatArray {
        output.rewind()
        val flat = FloatArray(tensor.numElements())
        val quant = tensor.quantizationParams()
        repeat(flat.size) { index ->
            flat[index] = when (tensor.dataType()) {
                DataType.FLOAT32 -> output.getFloat()
                DataType.UINT8 -> ((output.get().toInt() and 0xFF) - quant.zeroPoint) * quant.scale
                DataType.INT8 -> (output.get().toInt() - quant.zeroPoint) * quant.scale
                DataType.INT32 -> output.int.toFloat()
                else -> throw IllegalStateException("Unsupported output type: ${tensor.dataType()}")
            }
        }
        return flat
    }

    private fun parseOutputTensor(
        outputIndex: Int,
        shape: IntArray,
        flat: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int
    ): ParseResult {
        if (shape.size != 3) {
            Log.w(TAG, "Output tensor[$outputIndex] unsupported rank: shape=${shape.contentToString()}")
            return ParseResult(emptyList(), 0, 0, 0f)
        }

        val parsed = when {
            shape[1] >= 6 && shape[2] > shape[1] -> parseYoloChannelsFirst(flat, shape[1], shape[2], sourceWidth, sourceHeight)
            shape[2] >= 6 -> parseYoloBoxesFirst(flat, shape[1], shape[2], sourceWidth, sourceHeight)
            else -> ParseResult(emptyList(), 0, 0, 0f)
        }
        if (shouldLogDiagnostics(frameIndex)) {
            Log.d(TAG, "output[$outputIndex] shape=${shape.contentToString()} rawPredictions=${parsed.rawPredictions} aboveThreshold=${parsed.rawCandidates} final=${parsed.detections.size} maxConfidence=${String.format("%.3f", parsed.maxConfidence)}")
        }
        return parsed
    }

    private fun parseYoloChannelsFirst(
        flat: FloatArray,
        channels: Int,
        boxes: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): ParseResult {
        val candidates = ArrayList<Candidate>()
        val layout = outputLayout(channels)
        if (layout == null) {
            Log.w(TAG, "channels-first output unsupported channels=$channels labels=${labels.size}")
            return ParseResult(emptyList(), 0, 0, 0f)
        }
        if (shouldLogDiagnostics(frameIndex)) {
            Log.d(TAG, "channels-first output boxes=$boxes channels=$channels classStart=${layout.classStart} objectnessIndex=${layout.objectnessIndex} confidenceFormula=${layout.confidenceFormula}")
        }

        var rawCandidates = 0
        var maxConfidence = 0f
        var debugLogged = 0
        var topClassIndex = -1
        var topClassConfidence = 0f
        var topClassBoxIndex = -1
        var topPersonConfidence = 0f
        var topPersonBoxIndex = -1
        var topPersonObjectness = 1f
        var topPersonClassScore = 0f
        for (boxIndex in 0 until boxes) {
            val cx = flat[boxIndex]
            val cy = flat[boxes + boxIndex]
            val w = flat[boxes * 2 + boxIndex]
            val h = flat[boxes * 3 + boxIndex]
            val objectness = layout.objectnessIndex?.let { normalizeScore(flat[boxes * it + boxIndex]) } ?: 1f
            var bestClass = -1
            var bestClassScore = 0f
            for (classChannel in layout.classStart until channels) {
                val classScore = normalizeScore(flat[boxes * classChannel + boxIndex])
                if (classScore > bestClassScore) {
                    bestClassScore = classScore
                    bestClass = classChannel - layout.classStart
                }
            }
            val finalConfidence = objectness * bestClassScore
            if (finalConfidence > topClassConfidence) {
                topClassConfidence = finalConfidence
                topClassIndex = bestClass
                topClassBoxIndex = boxIndex
            }
            if (personClassIndex >= 0) {
                val personScore = normalizeScore(flat[boxes * (layout.classStart + personClassIndex) + boxIndex])
                val personConfidence = objectness * personScore
                if (personConfidence > topPersonConfidence) {
                    topPersonConfidence = personConfidence
                    topPersonBoxIndex = boxIndex
                    topPersonObjectness = objectness
                    topPersonClassScore = personScore
                }
            }
            maxConfidence = max(maxConfidence, finalConfidence)
            if (bestClass in labels.indices && finalConfidence >= confidenceThresholdForClass(bestClass)) {
                rawCandidates++
                if (frameIndex <= DEBUG_CANDIDATE_FRAMES && debugLogged < MAX_DEBUG_CANDIDATES_PER_FRAME) {
                    logCandidate("channels", boxIndex, cx, cy, w, h, bestClass, objectness, bestClassScore, finalConfidence)
                    debugLogged++
                }
                if (isAiracareClassIndex(bestClass)) {
                    candidates.add(toCenterCandidate(cx, cy, w, h, bestClass, finalConfidence, objectness, bestClassScore, sourceWidth, sourceHeight))
                } else if (frameIndex <= DEBUG_CANDIDATE_FRAMES) {
                    logRejected(labels[bestClass], finalConfidence, "class_not_airacare_target")
                }
            }
        }
        if (frameIndex % DIAGNOSTIC_LOG_INTERVAL_FRAMES == 0L || frameIndex <= DEBUG_CANDIDATE_FRAMES) {
            Log.i(TAG, "RAW YOLO DIAGNOSTIC frame=$frameIndex topClassIndex=$topClassIndex topClass=${labels.getOrNull(topClassIndex)} topConfidence=${String.format("%.3f", topClassConfidence)} topBox=$topClassBoxIndex topPersonConfidence=${String.format("%.3f", topPersonConfidence)} topPersonBox=$topPersonBoxIndex topPersonObjectness=${String.format("%.3f", topPersonObjectness)} topPersonClassScore=${String.format("%.3f", topPersonClassScore)} personThreshold=${String.format("%.3f", PERSON_MIN_DETECTION_CONFIDENCE)} normalThreshold=${String.format("%.3f", MIN_DETECTION_CONFIDENCE)}")
        }
        return ParseResult(nonMaxSuppression(candidates).map { it.toDetection() }, boxes, rawCandidates, maxConfidence)
    }

    private fun parseYoloBoxesFirst(
        flat: FloatArray,
        rows: Int,
        rowLen: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): ParseResult {
        val candidates = ArrayList<Candidate>()
        val layout = outputLayout(rowLen)
        var rawCandidates = 0
        var maxConfidence = 0f
        var debugLogged = 0
        if (shouldLogDiagnostics(frameIndex)) {
            Log.d(TAG, "boxes-first output rows=$rows rowLen=$rowLen classStart=${layout?.classStart} objectnessIndex=${layout?.objectnessIndex} confidenceFormula=${layout?.confidenceFormula ?: "postprocessed-or-unsupported"}")
        }

        for (row in 0 until rows) {
            val offset = row * rowLen
            if (rowLen == 6) {
                val confidence = normalizeScore(flat[offset + 4])
                val classIndex = flat[offset + 5].toInt()
                maxConfidence = max(maxConfidence, confidence)
                if (classIndex in labels.indices && confidence >= confidenceThresholdForClass(classIndex)) {
                    rawCandidates++
                    if (frameIndex <= DEBUG_CANDIDATE_FRAMES && debugLogged < MAX_DEBUG_CANDIDATES_PER_FRAME) {
                        logCandidate("postprocessed", row, flat[offset], flat[offset + 1], flat[offset + 2], flat[offset + 3], classIndex, null, confidence, confidence)
                        debugLogged++
                    }
                    if (isAiracareClassIndex(classIndex)) {
                        candidates.add(toCornerCandidate(flat[offset], flat[offset + 1], flat[offset + 2], flat[offset + 3], classIndex, confidence, null, confidence, sourceWidth, sourceHeight))
                    } else if (frameIndex <= DEBUG_CANDIDATE_FRAMES) {
                        logRejected(labels[classIndex], confidence, "class_not_airacare_target")
                    }
                }
            } else if (layout != null) {
                val objectness = layout.objectnessIndex?.let { normalizeScore(flat[offset + it]) } ?: 1f
                var bestClass = -1
                var bestClassScore = 0f
                for (classOffset in layout.classStart until rowLen) {
                    val classScore = normalizeScore(flat[offset + classOffset])
                    if (classScore > bestClassScore) {
                        bestClassScore = classScore
                        bestClass = classOffset - layout.classStart
                    }
                }
                val finalConfidence = objectness * bestClassScore
                maxConfidence = max(maxConfidence, finalConfidence)
                if (bestClass in labels.indices && finalConfidence >= confidenceThresholdForClass(bestClass)) {
                    rawCandidates++
                    if (frameIndex <= DEBUG_CANDIDATE_FRAMES && debugLogged < MAX_DEBUG_CANDIDATES_PER_FRAME) {
                        logCandidate("boxes", row, flat[offset], flat[offset + 1], flat[offset + 2], flat[offset + 3], bestClass, objectness, bestClassScore, finalConfidence)
                        debugLogged++
                    }
                    if (isAiracareClassIndex(bestClass)) {
                        candidates.add(toCenterCandidate(flat[offset], flat[offset + 1], flat[offset + 2], flat[offset + 3], bestClass, finalConfidence, objectness, bestClassScore, sourceWidth, sourceHeight))
                    } else if (frameIndex <= DEBUG_CANDIDATE_FRAMES) {
                        logRejected(labels[bestClass], finalConfidence, "class_not_airacare_target")
                    }
                }
            }
        }
        return ParseResult(nonMaxSuppression(candidates).map { it.toDetection() }, rows, rawCandidates, maxConfidence)
    }


    private fun outputLayout(valueCount: Int): OutputLayout? {
        val classStart = valueCount - labels.size
        if (classStart < 4 || classStart >= valueCount) return null
        val objectnessIndex = if (classStart > 4) 4 else null
        val formula = if (objectnessIndex == null) "class_score_direct" else "objectness_x_class_score"
        return OutputLayout(classStart, objectnessIndex, formula)
    }


    private fun normalizeScore(value: Float): Float {
        if (!value.isFinite()) return 0f
        return when {
            value in 0f..1f -> value
            value > 1f -> 1f / (1f + kotlin.math.exp(-value))
            value < 0f -> 1f / (1f + kotlin.math.exp(-value))
            else -> 0f
        }
    }
    private fun logCandidate(
        source: String,
        index: Int,
        x: Float,
        y: Float,
        wOrX2: Float,
        hOrY2: Float,
        classIndex: Int,
        objectness: Float?,
        rawClassScore: Float,
        finalConfidence: Float
    ) {
        Log.d(
            TAG,
            "candidate source=$source index=$index bboxRaw=[${String.format("%.1f", x)},${String.format("%.1f", y)},${String.format("%.1f", wOrX2)},${String.format("%.1f", hOrY2)}] classIndex=$classIndex className=${labels.getOrNull(classIndex)} objectness=${objectness?.let { String.format("%.3f", it) } ?: "N/A"} rawClassScore=${String.format("%.3f", rawClassScore)} finalConfidence=${String.format("%.3f", finalConfidence)}"
        )
    }

    private fun isAiracareClassIndex(classIndex: Int): Boolean {
        return classIndex in labels.indices && labels[classIndex].lowercase() in airacareTargetLabels
    }

    private fun availableAiracareTargetLabels(): Set<String> {
        val modelLabels = labels.map { label -> label.lowercase() }.toSet()
        return AIRACARE_REQUESTED_LABELS
            .filter { requestedLabel -> requestedLabel in modelLabels }
            .toSet()
    }

    private fun logRejected(label: String, confidence: Float, reason: String) {
        Log.d(TAG, "Rejected detection class=$label confidence=${String.format("%.3f", confidence)} reason=$reason")
    }

    private fun confidenceThresholdForClass(classIndex: Int): Float {
        val label = labels.getOrNull(classIndex)?.lowercase() ?: return effectiveOtherThreshold()
        return when (label) {
            "person" -> effectivePersonThreshold()
            "dog", "cat" -> effectiveSmallAnimalThreshold()
            "horse", "cow", "deer", "goat", "elephant" -> effectiveLargeAnimalThreshold()
            else -> effectiveOtherThreshold()
        }
    }


    private fun effectivePersonThreshold(): Float {
        return when (currentFrameVisibility.state) {
            VisibilityState.LOW_LIGHT, VisibilityState.NIGHT, VisibilityState.POOR_VISIBILITY -> PERSON_LOW_LIGHT_CONFIDENCE
            VisibilityState.BLURRY -> PERSON_BLURRY_CONFIDENCE
            else -> PERSON_MIN_DETECTION_CONFIDENCE
        }
    }

    private fun effectiveSmallAnimalThreshold(): Float {
        return when (currentFrameVisibility.state) {
            VisibilityState.LOW_LIGHT -> SMALL_ANIMAL_LOW_LIGHT_CONFIDENCE
            VisibilityState.NIGHT, VisibilityState.POOR_VISIBILITY, VisibilityState.BLURRY -> SMALL_ANIMAL_POOR_VISIBILITY_CONFIDENCE
            else -> SMALL_ANIMAL_CONFIDENCE
        }
    }

    private fun effectiveLargeAnimalThreshold(): Float {
        return when (currentFrameVisibility.state) {
            VisibilityState.LOW_LIGHT -> LARGE_ANIMAL_LOW_LIGHT_CONFIDENCE
            VisibilityState.NIGHT, VisibilityState.POOR_VISIBILITY, VisibilityState.BLURRY -> LARGE_ANIMAL_POOR_VISIBILITY_CONFIDENCE
            else -> LARGE_ANIMAL_CONFIDENCE
        }
    }

    private fun effectiveOtherThreshold(): Float {
        return when (currentFrameVisibility.state) {
            VisibilityState.LOW_LIGHT -> LOW_LIGHT_ANIMAL_CONFIDENCE
            VisibilityState.NIGHT, VisibilityState.POOR_VISIBILITY, VisibilityState.BLURRY -> POOR_VISIBILITY_ANIMAL_CONFIDENCE
            else -> MIN_DETECTION_CONFIDENCE
        }
    }

    private fun shouldEnhanceFrame(visibility: SceneVisibility): Boolean {
        return ENABLE_LOW_LIGHT_ENHANCEMENT &&
            (visibility.state == VisibilityState.LOW_LIGHT || visibility.state == VisibilityState.NIGHT || visibility.state == VisibilityState.POOR_VISIBILITY)
    }

    private fun enhanceForLowLight(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val gamma = 0.72f
        val contrast = 1.12f
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val a = Color.alpha(pixel)
            val r = enhanceChannel(Color.red(pixel), gamma, contrast)
            val g = enhanceChannel(Color.green(pixel), gamma, contrast)
            val b = enhanceChannel(Color.blue(pixel), gamma, contrast)
            pixels[index] = Color.argb(a, r, g, b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun enhanceChannel(value: Int, gamma: Float, contrast: Float): Int {
        val normalized = (value / 255f).coerceIn(0f, 1f)
        val corrected = Math.pow(normalized.toDouble(), gamma.toDouble()).toFloat()
        val contrasted = ((corrected - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
        return (contrasted * 255f).roundToInt().coerceIn(0, 255)
    }
    private fun toCornerCandidate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        classIndex: Int,
        confidence: Float,
        objectness: Float?,
        rawClassScore: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): Candidate {
        val normalized = max(max(x1, y1), max(x2, y2)) <= 1.5f
        val scaleX = if (normalized) inputWidth.toFloat() else 1f
        val scaleY = if (normalized) inputHeight.toFloat() else 1f
        val gain = min(inputWidth / sourceWidth.toFloat(), inputHeight / sourceHeight.toFloat())
        val padX = (inputWidth - sourceWidth * gain) / 2f
        val padY = (inputHeight - sourceHeight * gain) / 2f
        val left = (x1 * scaleX - padX) / gain
        val top = (y1 * scaleY - padY) / gain
        val right = (x2 * scaleX - padX) / gain
        val bottom = (y2 * scaleY - padY) / gain
        return candidateFromBox(left, top, right, bottom, classIndex, confidence, objectness, rawClassScore, sourceWidth, sourceHeight)
    }

    private fun toCenterCandidate(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        classIndex: Int,
        confidence: Float,
        objectness: Float?,
        rawClassScore: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): Candidate {
        val normalized = max(max(cx, cy), max(w, h)) <= 1.5f
        val scaleX = if (normalized) inputWidth.toFloat() else 1f
        val scaleY = if (normalized) inputHeight.toFloat() else 1f
        val scaledCx = cx * scaleX
        val scaledCy = cy * scaleY
        val scaledW = w * scaleX
        val scaledH = h * scaleY
        val gain = min(inputWidth / sourceWidth.toFloat(), inputHeight / sourceHeight.toFloat())
        val padX = (inputWidth - sourceWidth * gain) / 2f
        val padY = (inputHeight - sourceHeight * gain) / 2f
        val left = ((scaledCx - scaledW / 2f) - padX) / gain
        val top = ((scaledCy - scaledH / 2f) - padY) / gain
        val right = ((scaledCx + scaledW / 2f) - padX) / gain
        val bottom = ((scaledCy + scaledH / 2f) - padY) / gain
        return candidateFromBox(left, top, right, bottom, classIndex, confidence, objectness, rawClassScore, sourceWidth, sourceHeight)
    }

    private fun candidateFromBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        classIndex: Int,
        confidence: Float,
        objectness: Float?,
        rawClassScore: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): Candidate {
        return Candidate(
            label = labels[classIndex],
            classIndex = classIndex,
            confidence = confidence,
            objectness = objectness,
            rawClassScore = rawClassScore,
            box = DetectionBox(
                min(left, right).coerceIn(0f, sourceWidth.toFloat()),
                min(top, bottom).coerceIn(0f, sourceHeight.toFloat()),
                max(left, right).coerceIn(0f, sourceWidth.toFloat()),
                max(top, bottom).coerceIn(0f, sourceHeight.toFloat())
            ),
            imageWidth = sourceWidth,
            imageHeight = sourceHeight,
            visibility = currentFrameVisibility
        )
    }

    private fun nonMaxSuppression(candidates: List<Candidate>): List<Candidate> {
        val selected = ArrayList<Candidate>()
        candidates
            .filter { isUsableCandidateBox(it) }
            .sortedByDescending { it.confidence }
            .forEach { candidate ->
                if (selected.size >= MAX_DETECTIONS) return@forEach
                val suppressor = selected.firstOrNull { existing ->
                    existing.classIndex == candidate.classIndex && iou(existing.box, candidate.box) > IOU_THRESHOLD
                }
                if (suppressor == null) {
                    selected.add(candidate)
                    if (frameIndex <= DEBUG_CANDIDATE_FRAMES) {
                        Log.d(TAG, "NMS kept class=${candidate.label} classIndex=${candidate.classIndex} conf=${String.format("%.3f", candidate.confidence)} objectness=${candidate.objectness?.let { String.format("%.3f", it) } ?: "N/A"} rawClassScore=${String.format("%.3f", candidate.rawClassScore)} box=${candidate.box}")
                    }
                } else if (frameIndex <= DEBUG_CANDIDATE_FRAMES) {
                    Log.d(TAG, "NMS suppressed class=${candidate.label} classIndex=${candidate.classIndex} conf=${String.format("%.3f", candidate.confidence)} by class=${suppressor.label} classIndex=${suppressor.classIndex} conf=${String.format("%.3f", suppressor.confidence)} iou=${String.format("%.2f", iou(suppressor.box, candidate.box))}")
                }
            }
        return selected
    }

    private fun isUsableCandidateBox(candidate: Candidate): Boolean {
        val box = candidate.box
        if (box.width <= MIN_BOX_SIZE_PIXELS || box.height <= MIN_BOX_SIZE_PIXELS) return false
        val frameArea = (candidate.imageWidth * candidate.imageHeight).toFloat().coerceAtLeast(1f)
        val areaRatio = box.area / frameArea
        val heightRatio = box.height / candidate.imageHeight.coerceAtLeast(1).toFloat()
        val widthRatio = box.width / candidate.imageWidth.coerceAtLeast(1).toFloat()
        if (areaRatio > MAX_WEAK_BOX_AREA_RATIO && candidate.confidence < HUGE_BOX_CONFIDENCE) return false
        if ((heightRatio > MAX_WEAK_BOX_DIMENSION_RATIO || widthRatio > MAX_WEAK_BOX_DIMENSION_RATIO) && candidate.confidence < HUGE_BOX_CONFIDENCE) return false
        return true
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        return intersection / max(1f, areaA + areaB - intersection)
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun letterbox(source: Bitmap, width: Int, height: Int): Bitmap {
        val output = letterboxBitmap
        val canvas = letterboxCanvas
        canvas.drawColor(Color.rgb(114, 114, 114))
        val gain = min(width / source.width.toFloat(), height / source.height.toFloat())
        val scaledWidth = (source.width * gain).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * gain).roundToInt().coerceAtLeast(1)
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(left, top, left + scaledWidth, top + scaledHeight),
            letterboxPaint
        )
        return output
    }

    private fun loadModel(context: Context, assetName: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(assetName)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
    }

    private fun selectModelAssets(context: Context): ModelAssets {
        val hasCustomModel = assetExists(context, CUSTOM_MODEL_ASSET)
        val hasCustomLabels = assetExists(context, CUSTOM_LABELS_ASSET)
        val hasPretrainedModel = assetExists(context, PRETRAINED_MODEL_ASSET)
        val hasPretrainedLabels = assetExists(context, PRETRAINED_LABELS_ASSET)
        val shouldUsePretrained = when (modelPreference) {
            ModelPreference.PRETRAINED_COCO -> true
            ModelPreference.CUSTOM_AIRACARE -> false
            ModelPreference.BEST_AVAILABLE -> PREFER_PRETRAINED_COCO_MODEL
        }
        return if (shouldUsePretrained && hasPretrainedModel && hasPretrainedLabels) {
            Log.w(TAG, "Model override active: using pretrained COCO because custom Airacare model is not detecting classes reliably. Deer/goat are not available in COCO.")
            ModelAssets(PRETRAINED_MODEL_ASSET, PRETRAINED_LABELS_ASSET, "PRETRAINED_COCO_OVERRIDE")
        } else if (modelPreference != ModelPreference.PRETRAINED_COCO && hasCustomModel && hasCustomLabels) {
            ModelAssets(CUSTOM_MODEL_ASSET, CUSTOM_LABELS_ASSET, "CUSTOM_AIRACARE")
        } else {
            if (hasCustomModel != hasCustomLabels) {
                Log.w(TAG, "Custom model assets incomplete: model=$hasCustomModel labels=$hasCustomLabels. Falling back to pretrained COCO.")
            }
            ModelAssets(PRETRAINED_MODEL_ASSET, PRETRAINED_LABELS_ASSET, "PRETRAINED_COCO")
        }
    }

    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private data class ParseResult(
        val detections: List<AnimalDetection>,
        val rawPredictions: Int,
        val rawCandidates: Int,
        val maxConfidence: Float
    )

    private data class OutputLayout(
        val classStart: Int,
        val objectnessIndex: Int?,
        val confidenceFormula: String
    )

    private data class Candidate(
        val label: String,
        val classIndex: Int,
        val confidence: Float,
        val objectness: Float?,
        val rawClassScore: Float,
        val box: DetectionBox,
        val imageWidth: Int,
        val imageHeight: Int,
        val visibility: SceneVisibility
    ) {
        fun toDetection(): AnimalDetection {
            return AnimalDetection(label, confidence, box, imageWidth, imageHeight, rotationDegrees = 0, visibilityState = visibility.state, visibilityQuality = visibility.quality, enhancementEnabled = visibility.enhancementEnabled)
        }
    }

    private data class InterpreterSelection(
        val interpreter: Interpreter,
        val backend: String
    )

    private data class ModelAssets(
        val modelAsset: String,
        val labelsAsset: String,
        val source: String
    )

    enum class ModelPreference {
        BEST_AVAILABLE,
        CUSTOM_AIRACARE,
        PRETRAINED_COCO
    }

    companion object {
        private const val TAG = "YoloTfliteDetector"
        private const val CUSTOM_MODEL_ASSET = "airacare_custom.tflite"
        private const val CUSTOM_LABELS_ASSET = "airacare_custom_labels.txt"
        private const val PRETRAINED_MODEL_ASSET = "yolo26n_w8a32.tflite"
        private const val PRETRAINED_LABELS_ASSET = "coco_labels.txt"
        private const val PREFER_PRETRAINED_COCO_MODEL = true
        private const val CHANNELS = 3
        private const val FLOAT_BYTES = 4
        private const val INTERPRETER_THREADS = 4
        private const val MIN_DETECTION_CONFIDENCE = 0.50f
        private const val LOW_LIGHT_ANIMAL_CONFIDENCE = 0.55f
        private const val POOR_VISIBILITY_ANIMAL_CONFIDENCE = 0.60f
        private const val PERSON_MIN_DETECTION_CONFIDENCE = 0.25f
        private const val PERSON_LOW_LIGHT_CONFIDENCE = 0.22f
        private const val PERSON_BLURRY_CONFIDENCE = 0.28f
        private const val SMALL_ANIMAL_CONFIDENCE = 0.45f
        private const val SMALL_ANIMAL_LOW_LIGHT_CONFIDENCE = 0.52f
        private const val SMALL_ANIMAL_POOR_VISIBILITY_CONFIDENCE = 0.58f
        private const val LARGE_ANIMAL_CONFIDENCE = 0.55f
        private const val LARGE_ANIMAL_LOW_LIGHT_CONFIDENCE = 0.65f
        private const val LARGE_ANIMAL_POOR_VISIBILITY_CONFIDENCE = 0.70f
        private const val ENABLE_LOW_LIGHT_ENHANCEMENT = true
        private const val LOW_LIGHT_INPUT_GAIN = 1.12f
        private const val LOW_LIGHT_INPUT_LIFT = 0.04f
        private const val NIGHT_INPUT_GAIN = 1.18f
        private const val NIGHT_INPUT_LIFT = 0.06f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 30
        private const val MIN_BOX_SIZE_PIXELS = 2f
        private const val MAX_WEAK_BOX_AREA_RATIO = 0.70f
        private const val MAX_WEAK_BOX_DIMENSION_RATIO = 0.92f
        private const val HUGE_BOX_CONFIDENCE = 0.80f
        private const val DEBUG_CANDIDATE_FRAMES = 1L
        private const val MAX_DEBUG_CANDIDATES_PER_FRAME = 12
        private const val DIAGNOSTIC_LOG_INTERVAL_FRAMES = 300L
        private const val VISIBILITY_EVAL_INTERVAL_FRAMES = 30L
        private val AIRACARE_REQUESTED_LABELS = listOf("person", "dog", "cat", "horse", "cow")
    }
}























