package com.example.clasificadorresiduos

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private data class Categoria(val nombre: String, val color: Int)

    private companion object {
        const val UMBRAL = 0.5f      // por debajo de esto dice "No estoy seguro"
        const val SUAVIZADO = 0.4f   // más bajo = resultado más estable, más lento en reaccionar
    }

    private val categorias = mapOf(
        "cardboard" to Categoria("Cartón", 0xFFC8A27A.toInt()),
        "glass" to Categoria("Vidrio", 0xFF4FC3F7.toInt()),
        "metal" to Categoria("Metal", 0xFFB0BEC5.toInt()),
        "paper" to Categoria("Papel", 0xFFFFD54F.toInt()),
        "plastic" to Categoria("Plástico", 0xFFFF8A65.toInt()),
        "trash" to Categoria("Basura", 0xFFBA68C8.toInt())
    )

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var detailText: TextView
    private lateinit var barFill: View
    private lateinit var torchButton: ImageButton
    private lateinit var classifier: WasteClassifier
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    // Promedio de probabilidades entre fotogramas (solo se usa en el hilo de análisis)
    private val suavizado = HashMap<String, Float>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                resultText.text = "Se necesita el permiso de cámara para funcionar"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewView)
        resultText = findViewById(R.id.resultText)
        detailText = findViewById(R.id.detailText)
        barFill = findViewById(R.id.barFill)
        torchButton = findViewById(R.id.torchButton)

        torchButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val encendida = cam.cameraInfo.torchState.value == TorchState.ON
            cam.cameraControl.enableTorch(!encendida)
        }

        classifier = WasteClassifier(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(imageProxy) }

            cameraProvider.unbindAll()
            val cam = cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            camera = cam

            // El botón de linterna solo aparece si el celular tiene flash
            torchButton.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
            cam.cameraInfo.torchState.observe(this) { state ->
                updateTorchUi(state == TorchState.ON)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val prediction = classifier.classify(bitmap, imageProxy.imageInfo.rotationDegrees)
            val ranked = suavizar(prediction.ranked)
            runOnUiThread { showResult(ranked) }
        } catch (e: Exception) {
            runOnUiThread { resultText.text = "Error: ${e.message}" }
        } finally {
            imageProxy.close()
        }
    }

    private fun suavizar(nuevos: List<Pair<String, Float>>): List<Pair<String, Float>> {
        for ((label, score) in nuevos) {
            val anterior = suavizado[label] ?: score
            suavizado[label] = SUAVIZADO * score + (1 - SUAVIZADO) * anterior
        }
        return suavizado.entries
            .map { it.key to it.value }
            .sortedByDescending { it.second }
    }

    private fun showResult(ranked: List<Pair<String, Float>>) {
        val (label, score) = ranked[0]
        val percent = (score * 100).toInt()
        val categoria = categorias[label]

        if (score >= UMBRAL) {
            resultText.text = "${categoria?.nombre ?: label}  $percent%"
            setBar(percent, categoria?.color ?: Color.WHITE)
        } else {
            resultText.text = "No estoy seguro"
            setBar(percent, Color.GRAY)
        }

        detailText.text = ranked.take(3).joinToString("   ") {
            "${categorias[it.first]?.nombre ?: it.first} ${(it.second * 100).toInt()}%"
        }
    }

    private fun setBar(percent: Int, color: Int) {
        val params = barFill.layoutParams as LinearLayout.LayoutParams
        params.weight = percent.toFloat()
        barFill.layoutParams = params
        barFill.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun updateTorchUi(encendida: Boolean) {
        if (encendida) {
            torchButton.setColorFilter(0xFFFFC107.toInt())
        } else {
            torchButton.clearColorFilter()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraExecutor.awaitTermination(1, TimeUnit.SECONDS)
        classifier.close()
    }
}