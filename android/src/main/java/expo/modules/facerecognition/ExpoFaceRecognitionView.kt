package expo.modules.facerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
    
    companion object {
        private const val TAG = "ExpoFaceRecognition"
    }
    
    private val onFaceDetected by EventDispatcher()
    private var textureView: TextureView? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraStarted = false
    private var surfaceTexture: SurfaceTexture? = null

    private val faceDetector by lazy { FaceDetector(context) }
    private val faceSpoof by lazy { FaceSpoofDetector(context) }
    private val faceNet by lazy { FaceNet(context) }

    private var isProcessing = false

    init {
        // Use TextureView for preview - this proved to work (avoids timeouts)
        textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "Surface texture available: ${width}x${height}")
                    this@ExpoFaceRecognitionView.surfaceTexture = surface
                    startCameraIfReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "Surface texture size changed: ${width}x${height}")
                    configureTransform(this@apply, width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.d(TAG, "Surface texture destroyed")
                    this@ExpoFaceRecognitionView.surfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // Called every frame
                }
            }
        }
        addView(textureView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "View attached to window")
        startCameraIfReady()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "View detached from window")
        stopCamera()
    }

    private fun startCameraIfReady() {
        if (isCameraStarted) return
        if (surfaceTexture == null) {
            Log.d(TAG, "Waiting for surface texture...")
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
        val surface = surfaceTexture ?: return
        val texture = textureView ?: return

        provider.unbindAll()

        // Preview Use Case
        val preview = Preview.Builder().build()
        
        preview.setSurfaceProvider { request ->
            val resolution = request.resolution
            Log.d(TAG, "Preview resolution coming from CameraX: ${resolution.width}x${resolution.height}")
            
            // NOTE: The resolution given here is usually unrotated (e.g. 640x480)
            // But the camera sensor is rotated 90 or 270 on phones.
            // We need to configure the transform based on THIS generic resolution vs the view size.
            
            texture.post {
                configureTransform(texture, resolution.width, resolution.height)
            }

            surface.setDefaultBufferSize(resolution.width, resolution.height)
            val previewSurface = android.view.Surface(surface)
            
            request.provideSurface(previewSurface, cameraExecutor) { result ->
                Log.d(TAG, "Surface result: ${result.resultCode}")
                previewSurface.release()
            }
        }

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

    private fun configureTransform(view: TextureView, previewWidth: Int, previewHeight: Int) {
        if (view.width == 0 || view.height == 0 || previewWidth == 0 || previewHeight == 0) return
        
        val matrix = Matrix()
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // For front camera in portrait mode, the preview buffer is usually landscape (e.g. 640x480).
        // But it's rotated 90deg by the system window manager usually? No, for TextureView we have to handle it.
        // Actually CameraX handles the display orientation for PreviewView, but for raw SurfaceProvider
        // we might get raw buffers. However, CameraX *attempts* to give upright buffers if possible? 
        // No, typically we need to swap dimensions if we want to fill the screen correctly.

        // Let's assume standard portrait phone usage:
        // View is tall (e.g. 1080x1920).
        // Preview buffer is wide (e.g. 640x480) effectively 480x640 when rotated.
        
        // 1. Swap preview dimensions because of the 90 degree natural rotation of camera sensor
        val bufferWidth = previewHeight.toFloat()
        val bufferHeight = previewWidth.toFloat()

        val viewRatio = viewWidth / viewHeight
        val bufferRatio = bufferWidth / bufferHeight

        val scaleX: Float
        val scaleY: Float
        
        // This is "Center Crop" logic
        if (viewRatio > bufferRatio) {
            scaleX = 1f
            scaleY = (viewWidth / bufferRatio) / viewHeight
        } else {
            scaleX = (viewHeight * bufferRatio) / viewWidth
            scaleY = 1f
        }

        // Apply scale
        matrix.setScale(scaleX, scaleY, centerX, centerY)
        
        // Removed mirroring as per user request
        
        view.setTransform(matrix)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true
        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = rotateBitmap(bitmap, rotation.toFloat())

            scope.launch {
                try {
                    val detection = faceDetector.detectFace(rotatedBitmap)
                    if (detection != null) {
                        val (fullImage, faceRect) = detection
                        val spoof = faceSpoof.detectSpoof(fullImage, faceRect)
                        if (spoof.isSpoof) {
                            onFaceDetected(mapOf("success" to true, "isLive" to false, "spoofScore" to spoof.score))
                        } else {
                            val croppedFace = Bitmap.createBitmap(fullImage, faceRect.left, faceRect.top, faceRect.width(), faceRect.height())
                            val embedding = faceNet.getFaceEmbedding(croppedFace)
                            onFaceDetected(mapOf(
                                "success" to true,
                                "isLive" to true,
                                "embedding" to embedding.toList(),
                                "spoofScore" to spoof.score))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing", e)
                } finally {
                    isProcessing = false
                }
            }
        } catch (e: Exception) {
            isProcessing = false
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
}
