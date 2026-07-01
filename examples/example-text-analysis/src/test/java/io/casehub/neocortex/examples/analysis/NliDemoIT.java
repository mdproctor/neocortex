package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;
import io.casehub.neocortex.inference.tasks.NliLabel;
import io.casehub.neocortex.inference.tasks.NliResult;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class NliDemoIT {

    private static final Path MODEL_DIR = Path.of("target/models/nli-deberta-v3-xsmall");

    private static InferenceModel model;
    private static NliClassifier nli;

    @BeforeAll
    static void setUp() {
        model = new OnnxInferenceModel(new ModelConfig(
            MODEL_DIR.resolve("model.onnx"), MODEL_DIR.resolve("tokenizer.json")));
        nli = new NliClassifier(model, 0, 1, 2);
    }

    @AfterAll
    static void tearDown() {
        if (model != null) model.close();
    }

    @Test
    void entailmentScoredHighForSemanticImplication() {
        NliResult result = nli.classify("A dog runs in the park.", "An animal is moving.");
        assertThat(result.entailment()).isGreaterThan(0.7f);
        assertThat(result.predicted()).isEqualTo(NliLabel.ENTAILMENT);
    }

    @Test
    void contradictionScoredHighForOpposites() {
        NliResult result = nli.classify(
            "The central bank raised interest rates.",
            "Interest rates were held steady.");
        assertThat(result.contradiction()).isGreaterThan(0.5f);
    }

    @Test
    void allDomainsProducePlausibleScores() {
        List<NliDemo.Result> results = NliDemo.run(nli);
        assertThat(results).allSatisfy(r -> {
            float sum = r.result().entailment() + r.result().neutral() + r.result().contradiction();
            assertThat(sum).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-4f));
        });
    }

    @Test
    void zeroShotClassificationTopLabelIsPlausible() {
        List<ZeroShotClassificationDemo.Result> results = ZeroShotClassificationDemo.run(nli);
        for (var r : results) {
            assertThat(r.topLabel()).isNotBlank();
            assertThat(r.scores().get(r.topLabel())).isGreaterThan(0.0f);
        }
    }
}
