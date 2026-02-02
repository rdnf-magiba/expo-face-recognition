package expo.modules.facerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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

class ExpoFaceRecognitionView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
    private val onFaceDetected by EventDispatcher()
    private val previewView = PreviewView(context)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val faceDetector by lazy { FaceDetector(context) }
    private val faceSpoof by lazy { FaceSpoofDetector(context) }
    private val faceNet by lazy { FaceNet(context) }

    private var isProcessing = false

    init {
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        previewView.layoutParams = layoutParams
        addView(previewView)
        setupCamera()
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val activity = appContext.activityProvider?.currentActivity as? LifecycleOwner ?: return

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImage(imageProxy)
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                activity,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (exc: Exception) {
            Log.e("ExpoFaceRecognition", "Use case binding failed", exc)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        val bitmap = imageProxy.toBitmap() // Requires camera-core typically or custom conversion
        val rotation = imageProxy.imageInfo.rotationDegrees

        // Handle rotation if needed (toBitmap usually handles it if implicit, but let's ensure upright)
        // Actually toBitmap() creates a bitmap. We might need to rotate it if it's not upright.
        // For front camera, it might be mirrored/rotated.
        // Let's assume toBitmap gives us the raw data. FaceDetector can handle it if we passed rotation, 
        // but our detectFace takes a Bitmap. We should rotate the bitmap to be upright.
        val rotatedBitmap = rotateBitmap(bitmap, rotation.toFloat())

        scope.launch {
            try {
                val detection = faceDetector.detectFace(rotatedBitmap)
                if (detection != null) {
                    val (fullImage, faceRect) = detection
                    // 1. Spoof
                    val spoof = faceSpoof.detectSpoof(fullImage, faceRect)
                    
                    if (spoof.isSpoof) {
                        onFaceDetected(mapOf(
                            "success" to true,
                            "isLive" to false,
                            "spoofScore" to spoof.score
                        ))
                    } else {
                        // 2. Embedding
                        // Crop exactly
                        val croppedFace = Bitmap.createBitmap(
                            fullImage,
                            faceRect.left, faceRect.top,
                            faceRect.width(), faceRect.height()
                        )
                        val embedding = faceNet.getFaceEmbedding(croppedFace)
                        onFaceDetected(mapOf(
                            "success" to true,
                            "isLive" to true,
                            "embedding" to embedding.toList()
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e("ExpoFaceRecognition", "Error processing face", e)
            } finally {
                isProcessing = false
                imageProxy.close()
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val matrix = Matrix()
        matrix.postRotate(degrees)
        // Flip front camera? Usually front camera is mirrored.
        // For detection it might not matter, but for recognition it might.
        // Let's stick to simple rotation for now.
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }
}
