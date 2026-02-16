package expo.modules.facerecognition.domain

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat
import java.util.concurrent.Executors

class SpecsDetector(val context: Context) {
    private val model = "specs_classifier.tflite"
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var interpreter: Interpreter? = null
    
    // Model input is [1, 3, 224, 224] (NCHW)
    private val inputHeight = 224
    private val inputWidth = 224
    
    // ImageNet stats scaled to [0, 255] since TensorImage acts on float converted from uint8
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
        .add(CastOp(DataType.FLOAT32))
        .add(NormalizeOp(
            floatArrayOf(0.485f * 255, 0.456f * 255, 0.406f * 255),
            floatArrayOf(0.229f * 255, 0.224f * 255, 0.225f * 255)
        ))
        .build()

    fun initialize() {
        interpreter?.close()

        val options = Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
            useNNAPI = false
        }
        interpreter = Interpreter(FileUtil.loadMappedFile(context, model), options)
    }

    suspend fun detectSpecs(faceBitmap: Bitmap): FloatArray = withContext(dispatcher) {
        if (interpreter == null) {
            initialize()
        }
        return@withContext calculateSpecsProbability(faceBitmap)
    }

    private fun calculateSpecsProbability(faceBitmap: Bitmap): FloatArray {
        var tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(faceBitmap)
        tensorImage = imageProcessor.process(tensorImage)
        val hwcArray = tensorImage.tensorBuffer.floatArray
        val nchwArray = FloatArray(hwcArray.size)
        val pixelCount = inputHeight * inputWidth
        for (i in 0 until pixelCount) {
            val hwcIndex = i * 3
            nchwArray[i] = hwcArray[hwcIndex]              // R
            nchwArray[pixelCount + i] = hwcArray[hwcIndex + 1] // G
            nchwArray[pixelCount * 2 + i] = hwcArray[hwcIndex + 2] // B
        }

        // 3. Create input buffer with NCHW shape
        val inputBuffer = TensorBufferFloat.createFixedSize(
            intArrayOf(1, 3, inputHeight, inputWidth), 
            DataType.FLOAT32
        )
        inputBuffer.loadArray(nchwArray)

        // 4. Run inference
        val outputBuffer = TensorBufferFloat.createFixedSize(intArrayOf(1, 1), DataType.FLOAT32)
        interpreter?.run(inputBuffer.buffer, outputBuffer.buffer.rewind())
        
        val output = outputBuffer.floatArray
        for (i in output.indices) {
            output[i] = sigmoid(output[i])
        }
        return output
    }

    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + kotlin.math.exp(-x))).toFloat()
    }
}