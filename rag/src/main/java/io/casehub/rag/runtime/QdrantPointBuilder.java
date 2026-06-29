package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Document;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class QdrantPointBuilder {

    static final Set<String> RESERVED_PAYLOAD_KEYS =
        Set.of("content", "sourceDocumentId", "tenantId");

    static final String BM25_MODEL = "qdrant/bm25";

    private static final Set<String> QDRANT_RESERVED_KEYS = Set.of("tenantId");

    private QdrantPointBuilder() {}

    static int[] computeChunkIndices(List<ChunkInput> chunks) {
        int[] indices = new int[chunks.size()];
        Map<String, Integer> counters = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            String docId = chunks.get(i).sourceDocumentId();
            int idx = counters.getOrDefault(docId, 0);
            indices[i] = idx;
            counters.put(docId, idx + 1);
        }
        return indices;
    }

    static PointStruct buildPoint(
            ChunkInput chunk, CorpusRef corpus,
            Embedding denseEmbedding, Map<Integer, Float> sparseMap,
            int chunkIndex, String denseVectorName, String sparseVectorName,
            boolean bm25Enabled, String bm25VectorName) {

        String idInput = chunk.sourceDocumentId() + "#" + chunkIndex;
        UUID pointId = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));

        Vector denseVector = VectorFactory.vector(denseEmbedding.vectorAsList());

        Map<String, Vector> namedVectors = new HashMap<>();
        namedVectors.put(denseVectorName, denseVector);
        if (sparseMap != null) {
            List<Float> sparseValues = new ArrayList<>(sparseMap.size());
            List<Integer> sparseIndices = new ArrayList<>(sparseMap.size());
            for (Map.Entry<Integer, Float> entry : sparseMap.entrySet()) {
                sparseIndices.add(entry.getKey());
                sparseValues.add(entry.getValue());
            }
            Vector sparseVector = VectorFactory.vector(sparseValues, sparseIndices);
            namedVectors.put(sparseVectorName, sparseVector);
        }
        if (bm25Enabled) {
            String expanded = CamelCaseExpander.expand(chunk.content());
            Vector bm25Vector = VectorFactory.vector(
                Document.newBuilder()
                    .setText(expanded)
                    .setModel(BM25_MODEL)
                    .build());
            namedVectors.put(bm25VectorName, bm25Vector);
        }

        Map<String, Value> payload = new HashMap<>();
        payload.put("content", ValueFactory.value(chunk.content()));
        payload.put("sourceDocumentId", ValueFactory.value(chunk.sourceDocumentId()));
        payload.put("tenantId", ValueFactory.value(corpus.tenantId()));

        for (String key : chunk.metadata().keySet()) {
            if (QDRANT_RESERVED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "metadata key '" + key + "' conflicts with reserved Qdrant payload field");
            }
        }
        for (String key : chunk.listMetadata().keySet()) {
            if (QDRANT_RESERVED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "listMetadata key '" + key + "' conflicts with reserved Qdrant payload field");
            }
        }

        for (Map.Entry<String, String> meta : chunk.metadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.value(meta.getValue()));
        }
        for (Map.Entry<String, List<String>> meta : chunk.listMetadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.list(
                meta.getValue().stream().map(ValueFactory::value).toList()));
        }

        return PointStruct.newBuilder()
            .setId(PointIdFactory.id(pointId))
            .setVectors(VectorsFactory.namedVectors(namedVectors))
            .putAllPayload(payload)
            .build();
    }
}
