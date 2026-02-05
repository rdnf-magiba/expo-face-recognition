package expo.modules.facerecognition.domain

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import expo.modules.core.logging.localizedMessageWithCauseLocalizedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat

import org.tensorflow.lite.gpu.GpuDelegate

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.gpu.CompatibilityList

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class FaceNet(val context: Context) {

    private val model = "facenet_512.tflite"
    // Dedicated thread for TFLite GPU Delegate (Must run on same thread as init)
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)
    
    private var interpreter: Interpreter? = null
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(160, 160, ResizeOp.ResizeMethod.BILINEAR)) // Input is 160x160
        .add(NormalizeOp())
        .build()
        
    init {
        scope.launch {
            initializeInterpreter()
        }
    }
    
    suspend fun waitForInit() = withContext(dispatcher) {
        if (interpreter == null) {
            initializeInterpreter()
        }
    }
        
    suspend fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray = withContext(dispatcher) {
        if (interpreter == null) {
            // Should have been initialized by init block, but if failed or slow, retry here.
            // Since this runs on the same single-threaded dispatcher, it waits for init to finish naturally.
            initializeInterpreter() 
        }
        return@withContext calculateFaceEmbedding(faceBitmap)
    }

    private var useGpu = false

    fun setGpuEnabled(enabled: Boolean) {
        if (useGpu != enabled) {
            useGpu = enabled
            scope.launch {
                initializeInterpreter()
            }
        }
    }

    private fun initializeInterpreter() {
        interpreter?.close()

        val options = Interpreter.Options().apply {
            numThreads = 4          // default CPU fallback
            useXNNPACK = true
            useNNAPI = true
        }
        try {
            if (useGpu) {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    options.addDelegate(
                        GpuDelegate(compat.bestOptionsForThisDevice)
                    )
                }
            }
        } catch (e: Exception) {
            // GPU failed → keep CPU defaults
            Log.e("FaceNet", e.localizedMessageWithCauseLocalizedMessage())
        }

        try {
            interpreter = Interpreter(FileUtil.loadMappedFile(context, model), options)
        } catch (e: Exception) {
            // If GPU/NNAPI failed, use fresh CPU options
            val cpuOptions = Interpreter.Options()
            cpuOptions.numThreads = 4
            cpuOptions.useXNNPACK = true
            cpuOptions.useNNAPI = false
            interpreter = Interpreter(FileUtil.loadMappedFile(context, model), cpuOptions)
        }
    }

    private fun calculateFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(faceBitmap))
        val outputBuffer = TensorBufferFloat.createFixedSize(intArrayOf(1, 512), DataType.FLOAT32)
        interpreter?.run(tensorImage.buffer, outputBuffer.buffer.rewind())
        return outputBuffer.floatArray
    }

    class NormalizeOp : TensorOperator {
        override fun apply(buffer: TensorBuffer?): TensorBuffer {
            val pixels = buffer!!.floatArray.map { it / 255f }.toFloatArray()
            val output = TensorBufferFloat.createFixedSize(buffer.shape, DataType.FLOAT32)
            output.loadArray(pixels)
            return output
        }
    }
}
