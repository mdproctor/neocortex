package io.casehub.neocortex.inference.runtime;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.tasks.ClassificationResult;
import io.casehub.neocortex.inference.tasks.TensorClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StrategyClassifierOnnxTest {

    private static final int MAX_WINDOWS = 10;
    private static final int F_TEMPORAL = 269;
    private static final int F_MAP = 6;

    private static final List<String> VS_TERRAN_LABELS = List.of(
        "RUSH", "BANSHEE_HARASS", "AIR_SUPERIORITY",
        "MECH_PUSH", "BIO_TIMING"
    );

    private static final List<String> VS_ZERG_LABELS = List.of(
        "RUSH", "ROACH_RUSH", "LING_BANE", "MUTA_HARASS",
        "HYDRA_PUSH", "MACRO_ECONOMY"
    );

    private static final List<String> VS_PROTOSS_LABELS = List.of(
        "RUSH", "PROXY", "CANNON_RUSH", "DT_RUSH",
        "BLINK_STALKER", "COLOSSUS_PUSH", "AIR_SUPERIORITY"
    );

    @Test
    void vsTerranModelLoadsAndClassifies(@TempDir Path tmpDir) throws Exception {
        assertClassifies("strategy_vs_terran.onnx", VS_TERRAN_LABELS, tmpDir);
    }

    @Test
    void vsZergModelLoadsAndClassifies(@TempDir Path tmpDir) throws Exception {
        assertClassifies("strategy_vs_zerg.onnx", VS_ZERG_LABELS, tmpDir);
    }

    @Test
    void vsProtossModelLoadsAndClassifies(@TempDir Path tmpDir) throws Exception {
        assertClassifies("strategy_vs_protoss.onnx", VS_PROTOSS_LABELS, tmpDir);
    }

    @Test
    void latencyUnderThreshold(@TempDir Path tmpDir) throws Exception {
        Path modelPath = extractResource("/models/strategy/strategy_vs_terran.onnx", tmpDir);

        try (OnnxInferenceModel model = new OnnxInferenceModel(new ModelConfig(modelPath))) {
            float[][] temporal = new float[1][MAX_WINDOWS * F_TEMPORAL];
            float[][] map = new float[1][F_MAP];

            for (int i = 0; i < 100; i++) {
                model.run(InferenceInput.tensor(Map.of("temporal", temporal, "map", map)));
            }

            long[] nanos = new long[1000];
            for (int i = 0; i < 1000; i++) {
                long start = System.nanoTime();
                model.run(InferenceInput.tensor(Map.of("temporal", temporal, "map", map)));
                nanos[i] = System.nanoTime() - start;
            }

            java.util.Arrays.sort(nanos);
            double p99ms = nanos[989] / 1_000_000.0;
            assertThat(p99ms).as("p99 latency must be < 10ms").isLessThan(10.0);
        }
    }

    private void assertClassifies(String modelFile, List<String> labels, Path tmpDir) throws Exception {
        Path modelPath = extractResource("/models/strategy/" + modelFile, tmpDir);

        try (OnnxInferenceModel model = new OnnxInferenceModel(new ModelConfig(modelPath))) {
            TensorClassifier classifier = new TensorClassifier(model, labels);

            float[][] temporal = new float[1][MAX_WINDOWS * F_TEMPORAL];
            float[][] map = new float[1][F_MAP];
            for (int i = 0; i < temporal[0].length; i++) temporal[0][i] = (float) Math.random();
            for (int i = 0; i < F_MAP; i++) map[0][i] = 0.5f;

            ClassificationResult result = classifier.classify(
                Map.of("temporal", temporal, "map", map)
            );

            assertThat(result.label()).isIn(labels);
            assertThat(result.confidence()).isBetween(0.0f, 1.0f);

            float probSum = 0;
            for (float v : result.scores().values()) probSum += v;
            assertThat(probSum).isCloseTo(1.0f, within(1e-4f));
        }
    }

    private Path extractResource(String resource, Path tmpDir) throws Exception {
        InputStream is = getClass().getResourceAsStream(resource);
        if (is == null) throw new IllegalStateException("Resource not found: " + resource);
        Path dest = tmpDir.resolve(Path.of(resource).getFileName());
        Files.copy(is, dest);
        return dest;
    }
}
