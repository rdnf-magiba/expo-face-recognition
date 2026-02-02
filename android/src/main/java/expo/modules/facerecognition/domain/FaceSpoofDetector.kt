package expo.modules.facerecognition.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import kotlin.math.exp

class FaceSpoofDetector(context: Context) {
    data class FaceSpoofResult(val isSpoof: Boolean, val score: Float)
    private val interpreter1: Interpreter
    private val interpreter2: Interpreter
    private val imageProcessor = ImageProcessor.Builder().add(CastOp(DataType.FLOAT32)).build()
    init {
        val options = Interpreter.Options().apply { numThreads = 4 }
        // Ensure these files are in your assets folder!
        interpreter1 = Interpreter(FileUtil.loadMappedFile(context, "spoof_model_scale_2_7.tflite"), options)
        interpreter2 = Interpreter(FileUtil.loadMappedFile(context, "spoof_model_scale_4_0.tflite"), options)
    }

    fun detectSpoofSync(fullFrame: Bitmap, faceRect: Rect): FaceSpoofResult {
        return calculateSpoof(fullFrame, faceRect)
    }

    suspend fun detectSpoof(fullFrame: Bitmap, faceRect: Rect): FaceSpoofResult = withContext(Dispatchers.Default) {
        return@withContext calculateSpoof(fullFrame, faceRect)
    }

    private fun calculateSpoof(fullFrame: Bitmap, faceRect: Rect): FaceSpoofResult {
        val crop1 = processCrop(fullFrame, faceRect, 2.7f)
        val crop2 = processCrop(fullFrame, faceRect, 4.0f)
        // Run Model 1
        val input1 = imageProcessor.process(TensorImage.fromBitmap(crop1)).buffer
        val output1 = arrayOf(FloatArray(3)) // 3 classes
        interpreter1.run(input1, output1)
        // Run Model 2
        val input2 = imageProcessor.process(TensorImage.fromBitmap(crop2)).buffer
        val output2 = arrayOf(FloatArray(3))
        interpreter2.run(input2, output2)
        // Average Softmax
        val score1 = softMax(output1[0])
        val score2 = softMax(output2[0])
        val combined = score1.zip(score2).map { (it.first + it.second) / 2 }
        // Class index 1 is "Real Face"
        val maxIndex = combined.indexOf(combined.maxOrNull() ?: -1f)
        val isSpoof = maxIndex != 1
        val confidence = combined[maxIndex]
        return FaceSpoofResult(isSpoof, confidence)
    }

    private fun processCrop(image: Bitmap, rect: Rect, scale: Float): Bitmap {
        // Crop logic based on scale
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = (rect.width() * scale).toInt()
        val h = (rect.height() * scale).toInt()
        val left = (cx - w / 2).coerceAtLeast(0)
        val top = (cy - h / 2).coerceAtLeast(0)
        val right = (cx + w / 2).coerceAtMost(image.width)
        val bottom = (cy + h / 2).coerceAtMost(image.height)
        val cropped = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(cropped, 80, 80, true) // Model input is 80x80
        // Convert RGB to BGR (Important for this specific model)
        val bgrBitmap = scaled.copy(Bitmap.Config.ARGB_8888, true)
        for (i in 0 until 80) {
            for (j in 0 until 80) {
                val p = scaled.getPixel(i, j)
                bgrBitmap.setPixel(i, j, Color.rgb(Color.blue(p), Color.green(p), Color.red(p)))
            }
        }
        return bgrBitmap
    }
    private fun softMax(logits: FloatArray): FloatArray {
        val exp = logits.map { exp(it) }
        val sum = exp.sum()
        return exp.map { (it / sum).toFloat() }.toFloatArray()
    }
}