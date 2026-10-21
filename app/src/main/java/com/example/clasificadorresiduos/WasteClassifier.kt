package com.example.clasificadorresiduos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Prediction(
    val label: String,
    val confidence: Float,
    val ranked: List<Pair<String, Float>>
)

class WasteClassifier(context: Context) {

    private val inputSize = 224
    private val interpreter: Interpreter
    private val labels: List<String>

    // Memoria reutilizable: 1 imagen x 224 x 224 x 3 canales x 4 bytes (float32)
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(inputSize * inputSize)

    init {
        // Cargar el modelo desde assets
        val modelBytes = context.assets.open("modelo_residuos.tflite").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()
        interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(4))

        // Cargar los nombres de las clases
        labels = context.assets.open("labels.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        val outputSize = interpreter.getOutputTensor(0).shape()[1]
        require(outputSize == labels.size) {
            "El modelo tiene $outputSize salidas pero labels.txt tiene ${labels.size} líneas"
        }
    }

    fun classify(bitmap: Bitmap, rotationDegrees: Int): Prediction {
        // 1. Enderezar la imagen según la orientación de la cámara
        val upright = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // 2. Reducir a 224x224 sin recortar (igual que en el entrenamiento)
        val resized = Bitmap.createScaledBitmap(upright, inputSize, inputSize, true)

        // 3. Pasar los píxeles a float32 en orden RGB, valores de 0 a 255
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF).toFloat())
            inputBuffer.putFloat(((p shr 8) and 0xFF).toFloat())
            inputBuffer.putFloat((p and 0xFF).toFloat())
        }
        inputBuffer.rewind()

        // 4. Ejecutar el modelo
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(inputBuffer, output)

        // 5. Ordenar las clases de mayor a menor probabilidad
        val scores = output[0]
        val ranked = labels.indices
            .map { labels[it] to scores[it] }
            .sortedByDescending { it.second }
        return Prediction(ranked[0].first, ranked[0].second, ranked)
    }

    fun close() {
        interpreter.close()
    }
}