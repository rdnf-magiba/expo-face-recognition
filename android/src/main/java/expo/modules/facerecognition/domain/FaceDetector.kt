package expo.modules.facerecognition.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.core.graphics.toRect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector as MPFaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceDetector(private val context: Context) {
    // The model is stored in the assets folder
    private val modelName = "blaze_face_short_range.tflite"
    private val baseOptions = BaseOptions.builder().setModelAssetPath(modelName).build()
    private val faceDetectorOptions =
        MPFaceDetector.FaceDetectorOptions
            .builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .build()
    private val faceDetector = MPFaceDetector.createFromOptions(context, faceDetectorOptions)

    fun detectFace(bitmap: Bitmap): Pair<Bitmap, Rect>? {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val detectionResult = faceDetector.detect(mpImage)
        val faces = detectionResult.detections()
        
        // Strict single face detection to match previous/requested behavior
        if (faces.isEmpty() || faces.size > 1) {
            return null
        }
        
        val face = faces[0]
        val rect = face.boundingBox().toRect()
        
        if (validateRect(bitmap.width, bitmap.height, rect)) {
            val croppedBitmap =
                Bitmap.createBitmap(
                    bitmap,
                    rect.left,
                    rect.top,
                    rect.width(),
                    rect.height(),
                )
            return Pair(bitmap, rect)
        }
        return null
    }

    suspend fun detectFace(imageUri: Uri): Pair<Bitmap, Rect>? = withContext(Dispatchers.IO) {
        val bitmap = getBitmapFromUri(context, imageUri) ?: return@withContext null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val faces = faceDetector.detect(mpImage).detections()

        if (faces.isEmpty() || faces.size > 1) {
            return@withContext null
        }
        val rect = faces[0].boundingBox().toRect()
        if (validateRect(bitmap.width, bitmap.height, rect)) {
            return@withContext Pair(bitmap, rect)
        }
        return@withContext null
    }

    fun getCroppedFaceSync(imageUri: Uri): Pair<Bitmap, Rect>? {
        val imageBitmap = getBitmapFromUri(context, imageUri) ?: return null
        val faces = faceDetector.detect(BitmapImageBuilder(imageBitmap).build()).detections()
        if (faces.size > 1) {
            return null
        } else if (faces.isEmpty()) {
            return null
        } else {
            // Validate the bounding box and
            // return the cropped face
            val rect = faces[0].boundingBox().toRect()
            if (validateRect(imageBitmap, rect)) {
                val croppedBitmap =
                    Bitmap.createBitmap(
                        imageBitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                return Pair(croppedBitmap, rect)
            } else {
                return null
            }
        }
    }

    suspend fun getAllCroppedFaces(frameBitmap: Bitmap): List<Pair<Bitmap, Rect>> =
        withContext(Dispatchers.IO) {
            return@withContext faceDetector
                .detect(BitmapImageBuilder(frameBitmap).build())
                .detections()
                .filter { validateRect(frameBitmap.width, frameBitmap.height, it.boundingBox().toRect()) }
                .map { detection -> detection.boundingBox().toRect() }
                .map { rect ->
                    val croppedBitmap =
                        Bitmap.createBitmap(
                            frameBitmap,
                            rect.left,
                            rect.top,
                            rect.width(),
                            rect.height(),
                        )
                    Pair(croppedBitmap, rect)
                }
        }

    private fun validateRect(width: Int, height: Int, rect: Rect): Boolean {
        return rect.left >= 0 && rect.top >= 0 &&
                (rect.left + rect.width()) <= width &&
                (rect.top + rect.height()) <= height
    }

    private fun validateRect(
        cameraFrameBitmap: Bitmap,
        boundingBox: Rect,
    ): Boolean =
        boundingBox.left >= 0 &&
                boundingBox.top >= 0 &&
                (boundingBox.left + boundingBox.width()) < cameraFrameBitmap.width &&
                (boundingBox.top + boundingBox.height()) < cameraFrameBitmap.height

    private fun getBitmapFromUri(context: Context, imageUri: Uri): Bitmap? {
        context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
            val exifInterface = ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            context.contentResolver.openInputStream(imageUri)?.use { decodeStream ->
                val bitmap = BitmapFactory.decodeStream(decodeStream) ?: return null
                return when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                    else -> bitmap
                }
            }
        }
        return null
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }
}