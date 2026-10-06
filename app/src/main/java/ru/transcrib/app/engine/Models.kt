package ru.transcrib.app.engine

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Owns the native sherpa-onnx objects. All models ship inside the APK (assets/models),
 * nothing is downloaded and no audio leaves the device.
 *
 *  - ASR: GigaAM v3 (Sber, MIT) RNN-T with punctuation and capitalization, int8.
 *  - VAD: Silero VAD.
 *  - Diarization: pyannote segmentation 3.0 + WeSpeaker ResNet34-LM embeddings.
 */
class Models(private val assets: AssetManager) {
    private var recognizer: OfflineRecognizer? = null
    private var diarizer: OfflineSpeakerDiarization? = null
    private var embedder: SpeakerEmbeddingExtractor? = null

    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    @Synchronized
    fun recognizer(): OfflineRecognizer = recognizer ?: OfflineRecognizer(
        assetManager = assets,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$ASR/encoder.int8.onnx",
                    decoder = "$ASR/decoder.onnx",
                    joiner = "$ASR/joiner.onnx",
                ),
                tokens = "$ASR/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = threads,
            ),
            decodingMethod = "greedy_search",
        ),
    ).also { recognizer = it }

    /** VAD keeps per-stream state, so every job gets a fresh instance. */
    fun newVad(): Vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$VAD/silero_vad.onnx",
                threshold = 0.45f,
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    @Synchronized
    fun diarizer(clustering: FastClusteringConfig): OfflineSpeakerDiarization {
        val cfg = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = "$DIAR/segmentation.onnx",
                    windowShiftRatio = 0.5f,
                ),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = "$DIAR/embedding.onnx",
                numThreads = threads,
            ),
            clustering = clustering,
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val d = diarizer ?: OfflineSpeakerDiarization(assetManager = assets, config = cfg).also { diarizer = it }
        d.setConfig(cfg)
        return d
    }

    @Synchronized
    fun embedder(): SpeakerEmbeddingExtractor = embedder ?: SpeakerEmbeddingExtractor(
        assetManager = assets,
        config = SpeakerEmbeddingExtractorConfig(model = "$DIAR/embedding.onnx", numThreads = threads),
    ).also { embedder = it }

    /** Frees native memory (~0.5 GB) when the app is idle. */
    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        diarizer?.release()
        diarizer = null
        embedder?.release()
        embedder = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512
        private const val ASR = "models/asr"
        private const val VAD = "models/vad"
        private const val DIAR = "models/diar"
    }
}
