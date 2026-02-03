package expo.modules.facerecognition

import android.view.View.MeasureSpec
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import expo.modules.facerecognition.domain.FaceDetector
import expo.modules.facerecognition.domain.FaceSpoofDetector
import expo.modules.facerecognition.domain.FaceNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class ExpoFaceRecognitionView(context: Context, appContext: AppContext) : ExpoView(context, appContext), DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ExpoFaceRecognition"
    }
    
    private val onFaceDetected by EventDispatcher()
    private var previewView: PreviewView? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraStarted = false
    
    private val faceDetector = FaceDetector(context)
    private val faceSpoof = FaceSpoofDetector(context)
    private val faceNet = FaceNet(context)

    private var isProcessing = false

    private var isModelsInitialized = false

    init {
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
            faceNet.waitForInit()
            isModelsInitialized = true
            Log.d(TAG, "Models initialized.")
            
            // If we are already attached/resumed, start camera now
            withContext(Dispatchers.Main) {
                if (isAttachedToWindow && !isCameraStarted) {
                    startCameraIfReady()
                }
            }
        }
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

    private fun startCameraIfReady() {
        if (isCameraStarted) return
        if (!isModelsInitialized) {
            Log.d(TAG, "Camera start deferred - models not ready")
            return
        }
        
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

        Log.d(TAG, "Starting camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
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

        provider.unbindAll()

        // Preview Use Case
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(surfaceProvider)

        // Image Analysis Use Case
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

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

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val rotatedBitmap = rotateBitmap(bitmap, rotation)
            
            val startTime = System.currentTimeMillis()
            
            var result: Pair<Bitmap, android.graphics.Rect>? = null
            val detectionTime = kotlin.system.measureTimeMillis {
                result = faceDetector.detectFace(rotatedBitmap)
            }

            if (result != null) {
                isProcessing = true
                val (croppedBitmap, faceRect) = result!!
                scope.launch {
                    try {
                        var spoof: expo.modules.facerecognition.domain.FaceSpoofDetector.FaceSpoofResult? = null
                        val spoofTime = kotlin.system.measureTimeMillis {
                            spoof = faceSpoof.detectSpoof(rotatedBitmap, faceRect)
                        }

                        val normalizedRect = getNormalizedFaceRect(
                            faceRect,
                            rotatedBitmap.width.toFloat(),
                            rotatedBitmap.height.toFloat(),
                            width.toFloat(),
                            height.toFloat(),
                            mirrorX = true // Front camera
                        )

                        val resultMap = mutableMapOf<String, Any>(
                            "success" to true,
                            "rect" to normalizedRect,
                            "spoofScore" to spoof!!.score
                        )

                        var embeddingTime = 0L
                        if (spoof!!.isSpoof) {
                            resultMap["isLive"] = false
                        } else {
                            var embedding: FloatArray
                            embeddingTime = kotlin.system.measureTimeMillis {
                                embedding = faceNet.getFaceEmbedding(croppedBitmap)
                            }
                            resultMap["isLive"] = true
                            resultMap["embedding"] = embedding.toList()
                        }
                        
                        // Add timings
                        resultMap["duration"] = mapOf(
                            "detection" to detectionTime,
                            "spoof" to spoofTime,
                            "embedding" to embeddingTime,
                            "total" to (System.currentTimeMillis() - startTime)
                        )
                        
                        onFaceDetected(resultMap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing", e)
                        onFaceDetected(mapOf("success" to false, "error" to e.localizedMessage))
                    } finally {
                        isProcessing = false
                    }
                }
            } else {
                onFaceDetected(mapOf("success" to false, "error" to "No face detected"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image proxy", e)
            onFaceDetected(mapOf("success" to false, "error" to e.localizedMessage))
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
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
