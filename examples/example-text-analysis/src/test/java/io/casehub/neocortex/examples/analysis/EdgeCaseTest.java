package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.ModelLoadException;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;
import io.casehub.neocortex.inference.tasks.TextClassifier;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("smoke")
class EdgeCaseTest {

    @Test
    void nliHandlesEmptyStrings() {
        var model = InMemoryInferenceModel.returning(0.3f, 0.3f, 0.4f);
        var nli = new NliClassifier(model);
        var result = nli.classify("", "");
        assertThat(result.predicted()).isNotNull();
    }

    @Test
    void classifierHandlesEmptyString() {
        var model = InMemoryInferenceModel.returning(0.5f, 0.5f);
        var classifier = new TextClassifier(model, List.of("a", "b"));
        var result = classifier.classify("");
        assertThat(result.label()).isNotNull();
    }

    @Test
    void rerankerHandlesSingleCandidate() {
        var model = InMemoryInferenceModel.returning(0.5f);
        var reranker = new CrossEncoderReranker(model);
        var results = reranker.rerank("query", List.of("only one"));
        assertThat(results).hasSize(1);
    }

    @Test
    void missingModelFileThrowsModelLoadException() {
        assertThatThrownBy(() -> new OnnxInferenceModel(
                new ModelConfig(Path.of("nonexistent/model.onnx"), Path.of("nonexistent/tokenizer.json"))))
            .isInstanceOf(ModelLoadException.class);
    }
}
