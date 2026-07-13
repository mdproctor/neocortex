package io.casehub.neocortex.memory.cbr.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import io.casehub.neocortex.memory.cbr.LocalSimilarityFunction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EmbeddingTextSimilarity implements LocalSimilarityFunction {

    private final EmbeddingModel model;
    private final Map<String, Embedding> cache = new HashMap<>();

    public EmbeddingTextSimilarity(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public void precompute(List<String> texts) {
        List<TextSegment> uncached = texts.stream()
            .filter(t -> !cache.containsKey(t))
            .distinct()
            .map(TextSegment::from).toList();
        if (!uncached.isEmpty()) {
            List<Embedding> embeddings = model.embedAll(uncached).content();
            for (int i = 0; i < uncached.size(); i++) {
                cache.put(uncached.get(i).text(), embeddings.get(i));
            }
        }
    }

    @Override
    public double compute(io.casehub.neocortex.memory.cbr.FeatureValue queryValue, io.casehub.neocortex.memory.cbr.FeatureValue caseValue) {
        if (queryValue instanceof io.casehub.neocortex.memory.cbr.FeatureValue.StringVal qs
            && caseValue instanceof io.casehub.neocortex.memory.cbr.FeatureValue.StringVal cs) {
            Embedding queryEmb = embed(qs.value());
            Embedding caseEmb  = embed(cs.value());
            return Math.max(0.0, CosineSimilarity.between(queryEmb, caseEmb));
        }
        return 0.0;
    }

    private Embedding embed(String text) {
        return cache.computeIfAbsent(text, t -> model.embed(TextSegment.from(t)).content());
    }
}
