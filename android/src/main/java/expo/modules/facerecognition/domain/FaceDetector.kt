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
import java.util.Optional
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.PI

data class FaceAnalysisResult(
    val bitmap: Bitmap,
    val rect: Rect,
    val yaw: Float,
    val roll: Float
)

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

    fun getCroppedFaceSync(imageUri: Uri): FaceAnalysisResult? {
        val imageBitmap = getBitmapFromUri(context, imageUri) ?: return null
        val faces = faceDetector.detect(BitmapImageBuilder(imageBitmap).build()).detections()
        if (faces.size > 1) {
            return null
        } else if (faces.isEmpty()) {
            return null
        } else {
            // Validate the bounding box and
            // return the cropped face
            val detection = faces[0]
            val rect = detection.boundingBox().toRect()
            if (validateRect(imageBitmap, rect)) {
                val croppedBitmap =
                    Bitmap.createBitmap(
                        imageBitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                val (yaw, roll) = calculateOrientation(detection.keypoints(), imageBitmap.width, imageBitmap.height)
                return FaceAnalysisResult(croppedBitmap, rect, yaw, roll)
            } else {
                return null
            }
        }
    }

    suspend fun getAllCroppedFaces(frameBitmap: Bitmap): List<FaceAnalysisResult> =
        withContext(Dispatchers.IO) {
            return@withContext faceDetector
                .detect(BitmapImageBuilder(frameBitmap).build())
                .detections()
                .filter { validateRect(frameBitmap.width, frameBitmap.height, it.boundingBox().toRect()) }
                .map { detection -> 
                    val rect = detection.boundingBox().toRect()
                    val croppedBitmap =
                        Bitmap.createBitmap(
                            frameBitmap,
                            rect.left,
                            rect.top,
                            rect.width(),
                            rect.height(),
                        )
                    val (yaw, roll) = calculateOrientation(detection.keypoints(), frameBitmap.width, frameBitmap.height)
                    FaceAnalysisResult(croppedBitmap, rect, yaw, roll)
                }
        }

    // Returns Yaw, Roll in degrees
    private fun calculateOrientation(keypointsOpt: Optional<MutableList<com.google.mediapipe.tasks.components.containers.NormalizedKeypoint>>, imgW: Int, imgH: Int): Pair<Float, Float> {
        if (!keypointsOpt.isPresent) return Pair(0f, 0f)
        val keypoints = keypointsOpt.get()
        if (keypoints.size < 2) return Pair(0f, 0f)

        // 0: Right Eye, 1: Left Eye
        val rightEye = keypoints[0]
        val leftEye = keypoints[1]
        
        // Roll: Angle of line between eyes
        val dy = (leftEye.y() - rightEye.y()) * imgH
        val dx = (leftEye.x() - rightEye.x()) * imgW
        val rollRad = atan2(dy, dx)
        val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

        // Yaw: Nose deviation from center of eyes
        // Using approximate geometric heuristics
        var yawDeg = 0f
        if (keypoints.size > 2) {
             val nose = keypoints[2]
             val midEyeX = (leftEye.x() + rightEye.x()) / 2f
             // Distance between eyes
             val eyeDist = abs(leftEye.x() - rightEye.x())
             if (eyeDist > 0) {
                 val deviation = (nose.x() - midEyeX) / eyeDist
                 // Emperical multiplier to map ratio to degrees roughly
                 yawDeg = deviation * 90f 
             }
        }
        
        return Pair(yawDeg, rollDeg)
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