package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;
import io.casehub.neocortex.inference.tasks.NliResult;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CrossDomainConsistencyIT {

    private static InferenceModel model;
    private static NliClassifier nli;

    @BeforeAll
    static void setUp() {
        Path dir = Path.of("target/models/nli-deberta-v3-xsmall");
        model = new OnnxInferenceModel(new ModelConfig(dir.resolve("model.onnx"), dir.resolve("tokenizer.json")));
        nli = new NliClassifier(model, 0, 1, 2);
    }

    @AfterAll
    static void tearDown() {
        if (model != null) model.close();
    }

    @Test
    void nliWorksAcrossAllDomains() {
        record Pair(String domain, String premise, String hypothesis) {}
        var pairs = List.of(
            new Pair("tech", "Quarkus supports reactive programming.", "Quarkus is a Java framework."),
            new Pair("news", "Global temperatures rose in 2025.", "Climate change is occurring."),
            new Pair("legal", "The processor shall implement security measures.", "Data protection requires safeguards.")
        );
        for (var pair : pairs) {
            NliResult result = nli.classify(pair.premise(), pair.hypothesis());
            assertThat(result.entailment())
                .as("Entailment for %s domain", pair.domain())
                .isGreaterThan(0.3f);
        }
    }

    @Test
    void zeroShotProducesDistinctLabelsPerDomain() {
        List<ZeroShotClassificationDemo.Result> results = ZeroShotClassificationDemo.run(nli);
        assertThat(results).hasSize(3);
        var labels = results.stream().map(ZeroShotClassificationDemo.Result::topLabel).distinct().toList();
        assertThat(labels.size()).isGreaterThanOrEqualTo(2);
    }
}
