package expo.modules.facerecognition.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceDetector(private val context: Context) {
    private val highAccuracyOpts = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()
    private val detector = FaceDetection.getClient(highAccuracyOpts)

    fun detectFaceSync(bitmap: Bitmap): Pair<Bitmap, Rect>? {
        return calculateFaceDetection(bitmap)
    }

    suspend fun detectFace(bitmap: Bitmap): Pair<Bitmap, Rect>? = withContext(Dispatchers.IO) {
        return@withContext calculateFaceDetection(bitmap)
    }

    suspend fun detectFace(imageUri: Uri): Pair<Bitmap, Rect>? = withContext(Dispatchers.IO) {
        val bitmap = getBitmapFromUri(context, imageUri) ?: return@withContext null

        // Run ML Kit Detection
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))

        if (faces.isEmpty() || faces.size > 1) {
            // Return null if no face or multiple faces found (strict mode)
            return@withContext null
        }
        val face = faces[0]
        val rect = face.boundingBox
        if (validateRect(bitmap, rect)) {
            return@withContext Pair(bitmap, rect)
        }
        return@withContext null
    }

    private fun calculateFaceDetection(bitmap: Bitmap): Pair<Bitmap, Rect>? {
        // Run ML Kit Detection
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))

        if (faces.isEmpty() || faces.size > 1) {
            return null
        }
        val face = faces[0]
        val rect = face.boundingBox
        if (validateRect(bitmap, rect)) {
            return Pair(bitmap, rect)
        }
        return null
    }

    private fun validateRect(bitmap: Bitmap, rect: Rect): Boolean {
        return rect.left >= 0 && rect.top >= 0 &&
                (rect.left + rect.width()) <= bitmap.width &&
                (rect.top + rect.height()) <= bitmap.height
    }
    private fun getBitmapFromUri(context: Context, imageUri: Uri): Bitmap? {
        context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
            val exifInterface = ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            // Re-open stream for decoding
            context.contentResolver.openInputStream(imageUri)?.use { decodeStream ->
                var bitmap = BitmapFactory.decodeStream(decodeStream)

                // Handle rotation
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