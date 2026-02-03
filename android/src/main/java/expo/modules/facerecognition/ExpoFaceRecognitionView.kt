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
    
    private val faceDetector by lazy { FaceDetector(context) }
    private val faceSpoof by lazy { FaceSpoofDetector(context) }
    private val faceNet by lazy { FaceNet(context) }

    private var isProcessing = false

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
            
            val faceRect = faceDetector.detectFace(rotatedBitmap)

            if (faceRect != null) {
                isProcessing = true
                scope.launch {
                    try {
                        val spoof = faceSpoof.detectSpoof(rotatedBitmap, faceRect)
                        if (spoof.isSpoof) {
                            onFaceDetected(mapOf("success" to true, "isLive" to false, "spoofScore" to spoof.score))
                        } else {
                            val croppedFace = Bitmap.createBitmap(rotatedBitmap, faceRect.left, faceRect.top, faceRect.width(), faceRect.height())
                            val embedding = faceNet.getFaceEmbedding(croppedFace)
                            onFaceDetected(mapOf(
                                "success" to true,
                                "isLive" to true,
                                "embedding" to embedding.toList(),
                                "spoofScore" to spoof.score))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing", e)
                    } finally {
                        isProcessing = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image proxy", e)
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
