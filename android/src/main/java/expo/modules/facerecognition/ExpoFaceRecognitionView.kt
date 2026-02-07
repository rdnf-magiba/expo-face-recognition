package expo.modules.facerecognition

import android.view.View.MeasureSpec
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRectF
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import expo.modules.core.logging.localizedMessageWithCauseLocalizedMessage
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import expo.modules.facerecognition.domain.FaceDetector
import expo.modules.facerecognition.domain.FaceSpoofDetector
import expo.modules.facerecognition.domain.FaceNet
import expo.modules.facerecognition.domain.FaceAnalysisResult
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue
import java.util.ArrayDeque
import kotlin.math.sqrt

enum class ModelLoadingStatus {
    LOADING,
    LOADED,
    FAILED
}


@ExperimentalGetImage
class ExpoFaceRecognitionView(context: Context, appContext: AppContext) : ExpoView(context, appContext), DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ExpoFaceRecognition"
    }
    
    private val onFaceDetected by EventDispatcher()
    private val onModelStatus by EventDispatcher()

    private var previewView: PreviewView? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraStarted = false
    
    private val faceDetector = FaceDetector(context)
    private val faceSpoof = FaceSpoofDetector(context)
    private val faceNet = FaceNet(context)

    private var isProcessing = false
    private var imageTransform: Matrix = Matrix()
    private var boundingBoxTransform: Matrix = Matrix()
    private var overlayWidth: Int = 0
    private var overlayHeight: Int = 0

    // Stability tracking
    private val faceCenterHistory = ArrayDeque<Pair<Float, Float>>()
    private val STABILITY_WINDOW = 5
    // Threshold for standard deviation in normalized coordinates (0.0 - 1.0)
    // 0.005 means 0.5% variance allowed
    private val STABILITY_THRESHOLD = 0.005


//    NEW
    private var isImageTransformedInitialized = false
    private var isBoundingBoxTransformedInitialized = false
    private lateinit var frameBitmap: Bitmap
//    NEW

    init {
        init()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "View attached to window")
        (appContext.activityProvider?.currentActivity as? LifecycleOwner)?.lifecycle?.addObserver(this)
        startCameraIfReady()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "View detached from window")
        (appContext.activityProvider?.currentActivity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
        stopCamera()
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        startCameraIfReady()
    }

    private fun init() {
        // Use PreviewView - handles lifecycle and surface scaling automatically
        previewView = PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Use COMPATIBLE to use TextureView under the hood, compatible with complex UI hierarchies
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        addView(previewView)

        // HACK: React Native sometimes doesn't layout child Android Views correctly immediately
        // Force a layout pass after attachment
        previewView?.post {
            previewView?.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            previewView?.layout(0, 0, width, height)

            // Also trigger requestLayout to bubble up to RN
            requestLayout()
        }

        // Wait for models to load before allowing camera start
        scope.launch {
            Log.d(TAG, "Waiting for models to initialize...")

            // If we are already attached/resumed, start camera now
            withContext(Dispatchers.Main) {
                if (isAttachedToWindow && !isCameraStarted) {
                    startCameraIfReady()
                }
            }
        }
    }

    fun setIsGPUEnabled(enabled: Boolean) {
        scope.launch {
            onModelStatus(mapOf("status" to ModelLoadingStatus.LOADING))
            try {
                faceNet.setGpuEnabled(enabled)
                onModelStatus(mapOf("status" to ModelLoadingStatus.LOADED))
                Log.d(TAG, "Models initialized.")
            }
            catch (e: Exception) {
                onModelStatus(mapOf("status" to ModelLoadingStatus.FAILED, "error" to e.localizedMessage))
                Log.d(TAG, "Models failed to load.")
            }
        }
    }

    private fun startCameraIfReady() {
        if (isCameraStarted) return

        val activity = appContext.activityProvider?.currentActivity
        if (activity == null) {
            Log.e(TAG, "Activity is null")
            return
        }
        
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e(TAG, "Activity is not a LifecycleOwner")
            return
        }

        isImageTransformedInitialized = false
        isBoundingBoxTransformedInitialized = false
        Log.d(TAG, "Starting camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
            }
        }, executor)
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            isCameraStarted = false
            Log.d(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val surfaceProvider = previewView?.surfaceProvider ?: return
        // Preview Use Case
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(surfaceProvider)

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        // Image Analysis Use Case
        val imageAnalysis = ImageAnalysis
            .Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), imageAnalyser)
        provider.unbindAll()
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            isCameraStarted = true
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    private val imageAnalyser = ImageAnalysis.Analyzer { image ->
        processFeed(image)
    }

    private fun processFeed(image: ImageProxy) {
        if (isProcessing) {
            image.close()
            return
        }
        isProcessing = true
        frameBitmap = createBitmap(image.image!!.width, image.image!!.height)
        frameBitmap.copyPixelsFromBuffer(image.planes[0].buffer)
        if (!isImageTransformedInitialized) {
            imageTransform = Matrix()
            imageTransform.apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            isImageTransformedInitialized = true
        }
        frameBitmap =
            Bitmap.createBitmap(
                frameBitmap,
                0,
                0,
                frameBitmap.width,
                frameBitmap.height,
                imageTransform,
                false,
            )
        image.close()

        if (!isBoundingBoxTransformedInitialized) {
            boundingBoxTransform = Matrix()
            boundingBoxTransform.apply {
                setScale(
                    overlayWidth / frameBitmap.width.toFloat(),
                    overlayHeight / frameBitmap.height.toFloat(),
                )
                // Mirror the bounding box coordinates
                // for front-facing camera
                postScale(
                    -1f,
                    1f,
                    overlayWidth.toFloat() / 2.0f,
                    overlayHeight.toFloat() / 2.0f,
                )
            }
            isBoundingBoxTransformedInitialized = true
        }
        CoroutineScope(Dispatchers.Default).launch {
            val (faceDetectionResult, t1) = measureTimedValue { faceDetector.getAllCroppedFaces(frameBitmap) }

            if (faceDetectionResult.isEmpty()) {
                onFaceDetected(mapOf("success" to false, "error" to "No face detected"))
                isProcessing = false
                return@launch
            }

            for (result in faceDetectionResult) {
                val croppedBitmap = result.bitmap
                val boundingBox = result.rect
                val yaw = result.yaw
                val roll = result.roll

                val spoofResult = faceSpoof.detectSpoof(frameBitmap, boundingBox)

                val normRect = getNormalizedFaceRect(boundingBox, image.height.toFloat(), image.width.toFloat(), width.toFloat(), height.toFloat())
                
                val resultMap = mutableMapOf<String, Any>(
                    "success" to true,
                    "isLive" to true,
                    "rect" to normRect
                )

                val isStraight = abs(yaw) < 12 && abs(roll) < 12
                resultMap["isStraight"] = isStraight
                resultMap["yaw"] = yaw
                resultMap["roll"] = roll

                // Stability Check
                var isStable = false
                if (faceDetectionResult.size == 1) {
                    val cx = (normRect["x"] ?: 0f) + (normRect["width"] ?: 0f) / 2f
                    val cy = (normRect["y"] ?: 0f) + (normRect["height"] ?: 0f) / 2f
                    
                    if (faceCenterHistory.size >= STABILITY_WINDOW) {
                        faceCenterHistory.removeFirst()
                    }
                    faceCenterHistory.add(cx to cy)

                    if (faceCenterHistory.size >= STABILITY_WINDOW) {
                        val avgX = faceCenterHistory.map { it.first }.average()
                        val avgY = faceCenterHistory.map { it.second }.average()
                        val devX = sqrt(faceCenterHistory.map { (it.first - avgX).let { d -> d * d } }.average())
                        val devY = sqrt(faceCenterHistory.map { (it.second - avgY).let { d -> d * d } }.average())
                        
                        if (devX < STABILITY_THRESHOLD && devY < STABILITY_THRESHOLD) {
                            isStable = true
                        }
                    }
                } else {
                    faceCenterHistory.clear()
                }
                resultMap["isStable"] = isStable

                if (spoofResult.isSpoof) {
                    resultMap["isLive"] = false
                    resultMap["spoofScore"] = spoofResult.score
                    onFaceDetected(resultMap)
                    isProcessing = false
                    return@launch
                }

                val (embedding, t3) = measureTimedValue { faceNet.getFaceEmbedding(croppedBitmap) }

                resultMap["embedding"] = embedding.toList()

                resultMap["duration"] = mapOf(
                    "detection" to t1.toLong(DurationUnit.MILLISECONDS),
                    "spoof" to spoofResult.timeMillis,
                    "embedding" to t3.toLong(DurationUnit.MILLISECONDS)
                )
                onFaceDetected(resultMap)
                isProcessing = false

            }
            image.close()
        }
    }

    private fun getNormalizedFaceRect(
        faceRect: android.graphics.Rect,
        imageWidth: Float,
        imageHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        mirrorX: Boolean = true
    ): Map<String, Float> {
        val scale: Float
        val dx: Float
        val dy: Float
        
        val viewRatio = viewWidth / viewHeight
        val imageRatio = imageWidth / imageHeight
        
        if (viewRatio > imageRatio) {
            // View is wider than image: Image is scaled to match view width, cropped vertically
            scale = viewWidth / imageWidth
            dx = 0f
            dy = (viewHeight - imageHeight * scale) / 2f
        } else {
            // View is taller than image: Image is scaled to match view height, cropped horizontally
            scale = viewHeight / imageHeight
            dx = (viewWidth - imageWidth * scale) / 2f
            dy = 0f
        }
        
        // Map coordinates to View pixels
        val xPixel = faceRect.left * scale + dx
        val yPixel = faceRect.top * scale + dy
        val wPixel = faceRect.width() * scale
        val hPixel = faceRect.height() * scale

        // Normalize
        val normX = if (mirrorX) {
            1.0f - (xPixel / viewWidth) - (wPixel / viewWidth)
        } else {
            xPixel / viewWidth
        }

        return mapOf(
            "x" to normX,
            "y" to (yPixel / viewHeight),
            "width" to (wPixel / viewWidth),
            "height" to (hPixel / viewHeight)
        )
    }

    // React Native specific hack for layout updates
    override fun requestLayout() {
        super.requestLayout()
        // This is needed for the camera preview to update its bounds when RN layout changes
        post(measureAndLayout)
    }

    private val measureAndLayout = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }
}