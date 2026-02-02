package expo.modules.facerecognition

import android.content.Context
import android.graphics.Bitmap
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


    override fun definition() = ModuleDefinition {
        // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
        // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
        // The module will be accessible from `requireNativeModule('ExpoFaceRecognition')` in JavaScript.
        Name("ExpoFaceRecognition")
        AsyncFunction("processFace") { imageUri: String, promise: Promise ->
            scope.launch {
                try {
                    val detection = faceDetector.detectFace(imageUri.toUri())

                    if (detection == null) {
                        promise.resolve(mapOf("success" to false, "error" to "No face detected"))
                        return@launch
                    }
                    val (fullImage, faceRect) = detection

                    // 1. Check Spoof
                    val spoofResult = faceSpoof.detectSpoof(fullImage, faceRect)
                    if (spoofResult.isSpoof) {
                        promise.resolve(mapOf(
                            "success" to true,
                            "isLive" to false,
                            "spoofScore" to spoofResult.score
                        ))
                        return@launch
                    }
                    // 2. Get Embedding (Crop face exactly for FaceNet)
                    val croppedFace = Bitmap.createBitmap(
                        fullImage,
                        faceRect.left, faceRect.top,
                        faceRect.width(), faceRect.height()
                    )
                    val embedding = faceNet.getFaceEmbedding(croppedFace)
                    promise.resolve(mapOf(
                        "success" to true,
                        "isLive" to true,
                        "embedding" to embedding.toList() // Send as Array<Double> to JS
                    ))
                } catch (e: Exception) {
                    promise.reject("ERR_PROCESSING", e.localizedMessage, e)
                }
            }
        }

    }
}
