# Clasificador de Residuos

App Android que clasifica residuos con la cámara del celular usando una red neuronal convolucional (CNN). El modelo corre dentro del teléfono, así que **funciona sin conexión a internet**.

## Qué hace

- Abre la cámara trasera y clasifica continuamente lo que ve.
- Reconoce 6 categorías: **cartón, vidrio, metal, papel, plástico y basura**.
- Muestra el material detectado, el porcentaje de confianza y las 3 opciones más probables.
- Si la confianza es menor al 50 %, muestra "No estoy seguro" en lugar de adivinar.
- Promedia el resultado entre fotogramas para que no parpadee.
- Incluye un botón de linterna (aparece solo si el celular tiene flash).

<!--
## Capturas

Agrega aquí tus capturas de pantalla (guárdalas en una carpeta `docs/`) y quita las marcas de comentario:

![Pantalla principal](docs/captura_principal.png)
-->

## Cómo funciona

```
Cámara (CameraX) → imagen redimensionada a 224×224 → MobileNetV2 (LiteRT / TensorFlow Lite) → 6 probabilidades → pantalla
```

**Modelo.** Se usó *transfer learning*: una red MobileNetV2 preentrenada en ImageNet, a la que se le añadió una cabeza de clasificación nueva (GlobalAveragePooling, Dropout 0.3 y una capa Dense de 6 salidas con softmax). Esto permite obtener resultados razonables con un dataset pequeño.

**Entrenamiento.**

- División del dataset: 2,022 imágenes de entrenamiento, 249 de validación y 256 de prueba.
- Aumento de datos: volteo, rotación, zoom y contraste.
- Pesos por clase para compensar el desbalance (la clase `trash` tiene muchas menos fotos).
- Dos fases: primero se entrena solo la cabeza (Adam, tasa 1e-3) y luego se ajustan las últimas 30 capas de MobileNetV2 (Adam, tasa 1e-5), con parada temprana.
- Exportado a `.tflite` con cuantización dinámica: pesa unos 2.5 MB.

## Resultados

Medidos con las 256 imágenes de prueba, que el modelo nunca vio durante el entrenamiento:

| Clase | Precisión | Recall | F1 | Imágenes |
|---|---|---|---|---|
| cardboard (cartón) | 0.846 | 0.892 | 0.868 | 37 |
| glass (vidrio) | 0.817 | 0.875 | 0.845 | 56 |
| metal | 0.837 | 0.804 | 0.820 | 51 |
| paper (papel) | 0.898 | 0.815 | 0.854 | 54 |
| plastic (plástico) | 0.727 | 0.800 | 0.762 | 40 |
| trash (basura) | 0.667 | 0.556 | 0.606 | 18 |
| **Total** | **0.816** | | **0.816** (ponderado) | **256** |

- **Exactitud en prueba: 81.64 %** (209 de 256 imágenes). El modelo `.tflite` que usa la app dio exactamente el mismo resultado.
- En validación, durante el entrenamiento, la exactitud rondó el 88 %.
- La clase más débil es `trash` (137 imágenes en el dataset completo). Las confusiones más frecuentes son entre vidrio y plástico, y entre metal y vidrio, materiales que se ven parecidos en foto.

## Limitaciones

- **Margen de error.** Con solo 256 imágenes de prueba, la cifra de 81.64 % tiene un margen de error de unos 4 a 5 puntos.
- **Condiciones reales no medidas.** Las fotos del dataset se tomaron con el objeto sobre un fondo blanco y buena luz. Con la cámara del celular, fondos desordenados y poca luz, es probable que la precisión sea menor. Esa medición todavía no se ha hecho.
- **Solo 6 categorías.** No reconoce otros materiales (orgánicos, baterías, textiles, etc.).
- **Un objeto a la vez.** Funciona mejor con un solo objeto centrado en la imagen y no detecta varios objetos.

La mejora más efectiva sería ampliar el dataset con fotos propias, tomadas con la cámara del celular en condiciones reales, y reentrenar el modelo.

## Estructura del repositorio

```
.
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── modelo_residuos.tflite   # modelo entrenado
│       │   └── labels.txt               # nombres de las 6 clases
│       ├── java/com/example/clasificadorresiduos/
│       │   ├── MainActivity.kt          # cámara y pantalla
│       │   └── WasteClassifier.kt       # carga el modelo y clasifica
│       └── res/                         # diseño, colores e íconos
├── clasificador_residuos.ipynb          # cuaderno de entrenamiento (Google Colab)
└── ...                                  # archivos de configuración de Gradle
```

## Cómo ejecutar la app

**Requisitos:** Android Studio y un celular Android 7.0 o superior (API 24) con cámara trasera.

1. Clona el repositorio y ábrelo con Android Studio.
2. Espera a que termine la sincronización de Gradle (necesita internet la primera vez).
3. Activa la **depuración USB** en el celular y conéctalo a la PC.
4. Elige tu celular en la barra superior y pulsa **Run**.

Para generar un APK instalable: **Build > Generate App Bundles or APKs > Generate APKs**. El archivo queda en `app/build/outputs/apk/debug/`.

## Cómo reproducir el entrenamiento

1. Descarga el dataset desde Kaggle (ver la sección de créditos).
2. Sube el archivo ZIP a la raíz de tu Google Drive (`Mi unidad`).
3. Abre `clasificador_residuos.ipynb` en [Google Colab](https://colab.research.google.com) y activa la GPU: *Entorno de ejecución > Cambiar tipo de entorno de ejecución > T4 GPU*.
4. Ejecuta las celdas en orden. Al final se guardan `modelo_residuos.tflite` y `labels.txt` en la carpeta `residuos_modelo` de tu Drive.
5. Copia ambos archivos a `app/src/main/assets/` y vuelve a compilar la app.

**Formato del modelo:** entrada `float32` de forma `[1, 224, 224, 3]` con valores de 0 a 255 (la normalización va dentro del modelo), y salida de 6 probabilidades en el orden de `labels.txt` (alfabético: cardboard, glass, metal, paper, plastic, trash).

## Tecnologías

Kotlin · CameraX 1.6.2 · LiteRT 1.4.2 (TensorFlow Lite) · TensorFlow / Keras · MobileNetV2 · Google Colab · Android (minSdk 24, targetSdk 36)

## Dataset y créditos

El modelo se entrenó con el dataset **Garbage Classification**, publicado en Kaggle por el usuario `cchangcs` (https://www.kaggle.com/datasets/asdasdasasdas/garbage-classification). Sus 2,527 imágenes y su distribución por clase coinciden con las de **TrashNet**, de Gary Thung y Mindy Yang (Stanford): https://github.com/garythung/trashnet.

Los derechos de los datos pertenecen a sus autores originales. Este repositorio **no incluye** el dataset.

## Autor

Carlos Ticona · [@CarlosTicona](https://github.com/CarlosTicona)
