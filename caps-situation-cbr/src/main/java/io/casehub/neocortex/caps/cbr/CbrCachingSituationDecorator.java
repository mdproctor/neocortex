package io.casehub.neocortex.caps.cbr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecordSchema;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Decorator
@Priority(60)
@Unremovable
@IfBuildProperty(name = "casehub.caps.classification.cbr.enabled",
                 stringValue = "true", enableIfMissing = true)
public class CbrCachingSituationDecorator implements SituationClassifier {

    private static final Logger LOG = Logger.getLogger(
        CbrCachingSituationDecorator.class.getName());
    static final String CASE_TYPE = "situation-classification";
    static final String ENTITY_ID = "caps-classifier";
    static final MemoryDomain DOMAIN = new MemoryDomain("situation-classification");

    private static final String[] DISPOSITION_KEYS = {
        "disposition.autonomy", "disposition.conflictMode",
        "disposition.riskAppetite", "disposition.ruleFollowing",
        "disposition.socialOrient"
    };

    private static final CbrRecordSchema SCHEMA = CbrRecordSchema.of(CASE_TYPE,
        FeatureField.semanticText("description"),
        FeatureField.categorical("event-type"),
        FeatureField.categorical("cognitiveKind"),
        FeatureField.categorical("model-version"),
        FeatureField.categorical("disposition-socialOrient"),
        FeatureField.categorical("disposition-ruleFollowing"),
        FeatureField.categorical("disposition-riskAppetite"),
        FeatureField.categorical("disposition-autonomy"),
        FeatureField.categorical("disposition-conflictMode"));

    private final SituationClassifier delegate;
    private final CbrRecordStore cbrStore;
    private final double minSimilarity;
    private final String modelVersion;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    CbrCachingSituationDecorator(@Delegate @Any SituationClassifier delegate,
                                  Instance<CbrRecordStore> cbrStoreInstance,
                                  ClassificationCbrConfig config) {
        this.delegate = delegate;
        this.cbrStore = cbrStoreInstance.isResolvable()
                        ? cbrStoreInstance.get() : null;
        this.minSimilarity = config.minSimilarity();
        this.modelVersion = config.modelVersion();
    }

    CbrCachingSituationDecorator(SituationClassifier delegate,
                                 CbrRecordStore cbrStore,
                                 double minSimilarity,
                                 String modelVersion) {
        this.delegate = delegate;
        this.cbrStore = cbrStore;
        this.minSimilarity = minSimilarity;
        this.modelVersion = modelVersion;
    }

    @PostConstruct
    void registerSchema() {
        if (cbrStore != null) {
            cbrStore.registerSchema(SCHEMA);
        }
    }

    @Override
    public ClassificationResult classify(String description,
                                          Map<String, String> metadata,
                                          ClassificationContext context) {
        if (cbrStore == null || !context.isResolvable()) {
            return delegate.classify(description, metadata, context);
        }

        List<CbrMatch<CbrFeatureRecord>> hits = lookupCbr(
            description, metadata, context.tenantId());
        if (!hits.isEmpty()) {
            CbrFeatureRecord cached = hits.getFirst().cbrRecord();
            List<SituationActivation> parsed = parseActivations(cached.solution());
            if (!parsed.isEmpty()) {
                String eventType = metadata != null ? metadata.getOrDefault("event-type", "") : "";
                String cognitiveKind = metadata != null ? metadata.getOrDefault("cognitiveKind", "") : "";
                String caseId = computeCaseId(description, eventType, cognitiveKind, metadata);
                LOG.fine(() -> "CBR hit for: " + excerpt(description));
                return new ClassificationResult(parsed, caseId);
            }
        }

        ClassificationResult result = delegate.classify(
            description, metadata, context);

        String caseId = storeCbr(description, metadata, result.activations(), context.tenantId());
        return new ClassificationResult(result.activations(), caseId);
    }

    private List<CbrMatch<CbrFeatureRecord>> lookupCbr(
            String description, Map<String, String> metadata, String tenantId) {
        try {
            var query = CbrQuery.of(tenantId, DOMAIN,
                    io.casehub.platform.api.path.Path.root(),
                    CASE_TYPE, Map.of(), 1)
                .withProblem(description)
                .withMinSimilarity(minSimilarity)
                .withFilter("model-version",
                    CbrFilter.contains(modelVersion));
            return cbrStore.retrieveSimilar(query, CbrFeatureRecord.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CBR lookup failed, falling back to LLM", e);
            return List.of();
        }
    }

    private String storeCbr(String description, Map<String, String> metadata,
                             List<SituationActivation> activations,
                             String tenantId) {
        if (activations.isEmpty()) return null;
        try {
            String solution = serializeActivations(activations);
            String eventType = metadata != null
                               ? metadata.getOrDefault("event-type", "") : "";
            String cognitiveKind = metadata != null
                                   ? metadata.getOrDefault("cognitiveKind", "") : "";
            var features = new java.util.HashMap<String, FeatureValue>(Map.of(
                "description", FeatureValue.string(description),
                "event-type", FeatureValue.string(eventType),
                "cognitiveKind", FeatureValue.string(cognitiveKind),
                "model-version", FeatureValue.string(modelVersion)));
            addDispositionFeatures(features, metadata);

            var record = new CbrFeatureRecord(description, solution,
                null, null, features, null, null);

            String caseId = computeCaseId(description, eventType, cognitiveKind, metadata);

            cbrStore.store(record, CASE_TYPE, ENTITY_ID, DOMAIN, tenantId,
                caseId, io.casehub.platform.api.path.Path.root());
            return caseId;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CBR store failed for: " + excerpt(description), e);
            return null;
        }
    }

    private static void addDispositionFeatures(Map<String, FeatureValue> features,
                                                Map<String, String> metadata) {
        if (metadata == null) return;
        for (String key : DISPOSITION_KEYS) {
            String val = metadata.getOrDefault(key, "");
            features.put(key.replace('.', '-'), FeatureValue.string(val));
        }
    }

    String serializeActivations(List<SituationActivation> activations) {
        try {
            var nodes = mapper.createArrayNode();
            for (var a : activations) {
                nodes.addObject()
                     .put("node", a.nodeId())
                     .put("confidence", a.confidence());
            }
            return mapper.writeValueAsString(
                mapper.createObjectNode().set("activations", nodes));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize activations", e);
        }
    }

    List<SituationActivation> parseActivations(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr = root.path("activations");
            if (!arr.isArray()) return List.of();
            var result = new ArrayList<SituationActivation>();
            for (JsonNode entry : arr) {
                String nodeId = entry.path("node").asText(null);
                double confidence = entry.path("confidence").asDouble(0.0);
                if (nodeId != null && confidence > 0) {
                    result.add(new SituationActivation(nodeId, confidence));
                }
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to parse cached activations: " + e.getMessage());
            return List.of();
        }
    }

    static String computeCaseId(String description, String eventType,
                                 String cognitiveKind) {
        return computeCaseId(description, eventType, cognitiveKind, Map.of());
    }

    static String computeCaseId(String description, String eventType,
                                 String cognitiveKind, Map<String, String> metadata) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(description.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(eventType.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(cognitiveKind.getBytes(StandardCharsets.UTF_8));
            for (String key : DISPOSITION_KEYS) {
                String val = metadata != null ? metadata.getOrDefault(key, "") : "";
                digest.update((byte) 0);
                digest.update(val.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String excerpt(String text) {
        return text == null ? "null"
             : text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
