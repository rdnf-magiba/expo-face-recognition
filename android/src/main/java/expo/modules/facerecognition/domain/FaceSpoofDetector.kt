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
import kotlin.time.DurationUnit
import kotlin.time.measureTime

class FaceSpoofDetector(context: Context) {
    data class FaceSpoofResult(val isSpoof: Boolean, val score: Float, val timeMillis: Long)
    private val interpreter1: Interpreter
    private val interpreter2: Interpreter
    private val imageProcessor = ImageProcessor.Builder().add(CastOp(DataType.FLOAT32)).build()
    init {
        val options = Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
            useNNAPI = true
        }
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
        // Run Model 2
        val input2 = imageProcessor.process(TensorImage.fromBitmap(crop2)).buffer
        val output2 = arrayOf(FloatArray(3))
        val time = measureTime {
            val t1 = Thread { interpreter1.run(input1, output1) }
            val t2 = Thread { interpreter2.run(input2, output2) }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        }.toLong(DurationUnit.MILLISECONDS)
        // Average Softmax
        val score1 = softMax(output1[0])
        val score2 = softMax(output2[0])
        val combined = score1.zip(score2).map { (it.first + it.second) / 2 }
        // Class index 1 is "Real Face"
        val maxIndex = combined.indexOf(combined.maxOrNull() ?: -1f)
        val isSpoof = maxIndex != 1
        val confidence = combined[maxIndex]
        return FaceSpoofResult(isSpoof, confidence, time)
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
        
        // Optimized RGB to BGR conversion using bulk array operations
        val pixels = IntArray(80 * 80)
        scaled.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        
        // Swap R and B channels in-place (much faster than pixel-by-pixel)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF00FF00.toInt()) or  // Keep alpha and green
                       ((p and 0x00FF0000) shr 16) or  // Blue -> Red position
                       ((p and 0x000000FF) shl 16)     // Red -> Blue position
        }
        
        val bgrBitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bgrBitmap.setPixels(pixels, 0, 80, 0, 0, 80, 80)
        return bgrBitmap
    }
    private fun softMax(logits: FloatArray): FloatArray {
        val exp = logits.map { exp(it) }
        val sum = exp.sum()
        return exp.map { (it / sum).toFloat() }.toFloatArray()
    }
}