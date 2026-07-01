package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class ZeroShotClassificationDemoTest {

    @Test
    void allTextsProduceRankedLabels() {
        var model = InMemoryInferenceModel.withFunction(3, input -> {
            String hypothesis = input.texts().get(1);
            if (hypothesis.contains("technology")) return new float[]{0.9f, 0.05f, 0.05f};
            if (hypothesis.contains("law")) return new float[]{0.7f, 0.1f, 0.2f};
            return new float[]{0.1f, 0.3f, 0.6f};
        });
        var nli = new NliClassifier(model, 0, 1, 2);

        List<ZeroShotClassificationDemo.Result> results = ZeroShotClassificationDemo.run(nli);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.text()).isNotBlank();
            assertThat(r.topLabel()).isNotBlank();
            assertThat(r.scores()).isNotEmpty();
            assertThat(r.scores().values()).allSatisfy(score ->
                assertThat(score).isBetween(0.0f, 1.0f));
        });
    }

    @Test
    void coversAllThreeDomains() {
        var model = InMemoryInferenceModel.returning(0.3f, 0.3f, 0.4f);
        var nli = new NliClassifier(model, 0, 1, 2);

        List<ZeroShotClassificationDemo.Result> results = ZeroShotClassificationDemo.run(nli);

        var domains = results.stream().map(ZeroShotClassificationDemo.Result::domain).distinct().toList();
        assertThat(domains).containsExactlyInAnyOrder("tech", "news", "legal");
    }
}
