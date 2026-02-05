package expo.modules.facerecognition

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import expo.modules.facerecognition.domain.FaceDetector
import expo.modules.facerecognition.domain.FaceNet
import expo.modules.facerecognition.domain.FaceSpoofDetector
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class ExpoFaceRecognitionModule : Module() {
    // Each module class must implement the definition function. The definition consists of components
    // that describes the module's functionality and behavior.
    // See https://docs.expo.dev/modules/module-api for more details about available components.

    private val context: Context
        get() = appContext.reactContext ?: throw IllegalStateException("React Context null")
    private val faceDetector by lazy { FaceDetector(context) }
    private val faceSpoof by lazy { FaceSpoofDetector(context) }
    private val faceNet by lazy { FaceNet(context) }
    private val scope = CoroutineScope(Dispatchers.IO)


    @OptIn(ExperimentalGetImage::class)
    override fun definition() = ModuleDefinition {
        // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
        // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
        // The module will be accessible from `requireNativeModule('ExpoFaceRecognition')` in JavaScript.
        Name("ExpoFaceRecognition")
        AsyncFunction("processFace") { imageUri: String, promise: Promise ->
            scope.launch {
                try {
                    val startTime = System.currentTimeMillis()
                    var detectionTime = 0L
                    var embeddingTime = 0L
                    
                    var detection: Pair<Bitmap, android.graphics.Rect>? = null
                    detectionTime = kotlin.system.measureTimeMillis {
                        detection = faceDetector.getCroppedFaceSync(imageUri.toUri())
                    }

                    if (detection == null) {
                        promise.resolve(mapOf("success" to false, "error" to "No face detected"))
                        return@launch
                    }
                    val (croppedFace, faceRect) = detection

                    val resultMap = mutableMapOf<String, Any>(
                        "success" to true,
                        "rect" to faceRect,
                    )

                        // 2. Get Embedding
                    var embedding: FloatArray
                    embeddingTime = kotlin.system.measureTimeMillis {
                        embedding = faceNet.getFaceEmbedding(croppedFace)
                    }
                    resultMap["isLive"] = true
                    resultMap["embedding"] = embedding.toList()

                    resultMap["duration"] = mapOf(
                        "detection" to detectionTime,
                        "embedding" to embeddingTime,
                        "total" to (System.currentTimeMillis() - startTime)
                    )
                    
                    promise.resolve(resultMap)
                } catch (e: Exception) {
                    promise.resolve(mapOf("success" to false, "error" to e.localizedMessage))
                }
            }
        }

        View(ExpoFaceRecognitionView::class) {
            Events("onFaceDetected", "onModelStatus")
            Prop("isGPUEnabled") { view: ExpoFaceRecognitionView, isEnabled: Boolean ->
                view.setIsGPUEnabled(isEnabled)
            }
        }
    }
}
