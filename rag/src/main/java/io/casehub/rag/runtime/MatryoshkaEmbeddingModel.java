package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MatryoshkaEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final int targetDimension;

    public MatryoshkaEmbeddingModel(EmbeddingModel delegate, int targetDimension) {
        if (targetDimension <= 0) {
            throw new IllegalArgumentException(
                "targetDimension must be positive, got: " + targetDimension);
        }
        if (targetDimension > delegate.dimension()) {
            throw new IllegalArgumentException(
                "targetDimension (" + targetDimension + ") exceeds delegate dimension ("
                    + delegate.dimension() + ")");
        }
        this.delegate = delegate;
        this.targetDimension = targetDimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        Response<List<Embedding>> response = delegate.embedAll(textSegments);
        List<Embedding> truncated = new ArrayList<>(response.content().size());
        for (Embedding embedding : response.content()) {
            truncated.add(truncateAndNormalize(embedding));
        }
        return Response.from(truncated, response.tokenUsage(), response.finishReason());
    }

    @Override
    public int dimension() {
        return targetDimension;
    }

    @Override
    public String modelName() {
        return delegate.modelName() + "/matryoshka-" + targetDimension;
    }

    private Embedding truncateAndNormalize(Embedding embedding) {
        float[] truncated = Arrays.copyOf(embedding.vector(), targetDimension);
        double norm = 0;
        for (float f : truncated) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm > 1e-10) {
            for (int i = 0; i < truncated.length; i++) {
                truncated[i] /= (float) norm;
            }
        }
        return Embedding.from(truncated);
    }
}
