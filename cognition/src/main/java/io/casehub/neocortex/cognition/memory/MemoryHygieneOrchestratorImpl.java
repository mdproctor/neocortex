package io.casehub.neocortex.cognition.memory;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.KnowledgeGapSummary;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScopeDecay;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import io.casehub.platform.api.path.Path;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MemoryHygieneOrchestratorImpl implements MemoryHygieneOrchestrator {

    private static final Logger LOG = Logger.getLogger(MemoryHygieneOrchestratorImpl.class.getName());
    static final double LOW_RETENTION_THRESHOLD = 0.3;

    private final CbrRecordStore store;
    private final ConfidenceScorer confidenceScorer;
    private final TemporalDecay temporalDecay;
    private final ScopeDecay scopeDecay;
    private final ConsolidationSummariser summariser;
    private final MemoryDomain domain;
    private final List<String> caseTypes;
    private final RetentionConfigSupplier configSupplier;
    private final int consolidationBatchSize;
    private final double crossLinkSimilarityThreshold;
    private final Consumer<HygieneEvent> eventSink;

    private static final int LOCK_STRIPES = 16;
    private final ReentrantLock[] tickLocks;
    {
        tickLocks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) tickLocks[i] = new ReentrantLock();
    }
    private final ConcurrentHashMap<String, KnowledgeGapSummary> gapStates = new ConcurrentHashMap<>();

    public MemoryHygieneOrchestratorImpl(
            CbrRecordStore store,
            ConfidenceScorer confidenceScorer,
            TemporalDecay temporalDecay,
            ScopeDecay scopeDecay,
            ConsolidationSummariser summariser,
            MemoryDomain domain,
            List<String> caseTypes,
            RetentionConfigSupplier configSupplier,
            int consolidationBatchSize,
            double crossLinkSimilarityThreshold,
            Consumer<HygieneEvent> eventSink) {
        this.store = store;
        this.confidenceScorer = confidenceScorer;
        this.temporalDecay = temporalDecay;
        this.scopeDecay = scopeDecay;
        this.summariser = summariser;
        this.domain = domain;
        this.caseTypes = List.copyOf(caseTypes);
        this.configSupplier = configSupplier;
        this.consolidationBatchSize = consolidationBatchSize;
        this.crossLinkSimilarityThreshold = crossLinkSimilarityThreshold;
        this.eventSink = eventSink;
    }

    @Override
    public void tick(String agentId, String tenantId) {
        doTick(agentId, tenantId);
    }

    @Override
    public KnowledgeGapSummary knowledgeGaps(String agentId, String tenantId) {
        var summary = gapStates.get(agentId + ":" + tenantId);
        return summary != null ? summary : KnowledgeGapSummary.empty();
    }

    @Override
    public KnowledgeGapSummary tenantGapSummary(String tenantId) {
        String suffix   = ":" + tenantId;
        int    totalLow = 0, totalGroups = 0, totalScored = 0;
        for (var entry : gapStates.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                var s = entry.getValue();
                totalLow += s.lowRetentionCount();
                totalGroups += s.consolidationGroups();
                totalScored += s.totalScored();
            }
        }
        if (totalScored == 0) {return KnowledgeGapSummary.empty();}
        return new KnowledgeGapSummary(totalLow, totalGroups, totalScored);
    }


    HygieneTick doTick(String agentId, String tenantId) {
        var key = agentId + ":" + tenantId;
        var lock = tickLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return doTickInternal(agentId, tenantId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Memory hygiene tick failed for " + agentId, e);
            return new HygieneTick.Failed(e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private HygieneTick doTickInternal(String agentId, String tenantId) {
        var retentionConfig = configSupplier.current(tenantId);
        var now = Instant.now();
        var allScores = new ArrayList<RetentionScore>();
        int totalEvicted = 0;
        int totalConsolidated = 0;

        for (var caseType : caseTypes) {
            var query = CbrQuery.of(tenantId, domain, Path.root(), caseType,
                            Map.of(), consolidationBatchSize)
                    .withMinSimilarity(0.0);
            List<CbrMatch<CbrRecord>> memories = store.retrieveSimilar(query, CbrRecord.class);

            var agentMemories = memories.stream()
                    .filter(m -> agentId.equals(m.cbrRecord().producerAgentId()))
                    .toList();

            if (agentMemories.isEmpty()) continue;

            var scored = agentMemories.stream()
                    .map(m -> RetentionScore.compute(
                            m.caseId(),
                            entityId(m),
                            confidenceScorer.score(m, now),
                            temporalDecay.factor(m.storedAt(), now),
                            scopeDecay.factor(0),
                            m.cbrRecord().trustScore() != null ? m.cbrRecord().trustScore() : 1.0,
                            retentionConfig))
                    .toList();

            allScores.addAll(scored);

            var toEvict = scored.stream()
                    .filter(s -> s.composite() < retentionConfig.retentionThreshold())
                    .toList();

            for (var eviction : toEvict) {
                store.erase(new EraseRequest(Subject.of("memory", eviction.entityId()), domain, tenantId, eviction.caseId()));
                eventSink.accept(new HygieneEvent.MemoryEvicted(eviction.caseId(), eviction));
            }
            totalEvicted += toEvict.size();

            var survivorIds = scored.stream()
                    .filter(s -> s.composite() >= retentionConfig.retentionThreshold())
                    .map(RetentionScore::caseId)
                    .collect(java.util.stream.Collectors.toSet());
            var survivors = agentMemories.stream()
                    .filter(m -> survivorIds.contains(m.caseId()))
                    .toList();

            totalConsolidated += consolidate(survivors, tenantId, caseType);
        }

        if (allScores.isEmpty()) {
            gapStates.put(agentId + ":" + tenantId, KnowledgeGapSummary.empty());
            return new HygieneTick.Idle("no memories for agent");
        }

        int lowRetention = (int) allScores.stream()
                .filter(s -> s.composite() < LOW_RETENTION_THRESHOLD).count();
        gapStates.put(agentId + ":" + tenantId,
                new KnowledgeGapSummary(lowRetention, totalConsolidated, allScores.size()));

        return new HygieneTick.Completed(totalConsolidated, totalEvicted, allScores.size(), allScores);
    }

    private int consolidate(List<CbrMatch<CbrRecord>> survivors, String tenantId, String caseType) {
        if (survivors.size() < 2) return 0;
        var groups = findSimilarGroups(survivors);
        int consolidated = 0;

        for (var group : groups) {
            if (group.size() < 2) continue;
            try {
                var texts = group.stream()
                        .map(m -> m.cbrRecord().problem() != null ? m.cbrRecord().problem() : "")
                        .toList();
                var summaryText = summariser.summarise(texts);
                if (summaryText == null || summaryText.isBlank()) continue;

                var mergedFeatures = new HashMap<String, FeatureValue>();
                var sourceCaseIds = new ArrayList<String>();
                for (var m : group) {
                    var features = m.cbrRecord().features();
                    if (features != null) mergedFeatures.putAll(features);
                    sourceCaseIds.add(m.caseId());
                }
                mergedFeatures.put("source_cases", FeatureValue.stringList(sourceCaseIds));

                var mergedCase = new CbrFeatureRecord(
                        summaryText, "consolidated from " + sourceCaseIds.size() + " memories",
                        null, null, mergedFeatures, null,
                        group.getFirst().cbrRecord().producerAgentId());

                var mergedId = store.store(mergedCase, caseType,
                        group.getFirst().cbrRecord().producerAgentId(),
                        domain, tenantId, null, Path.root());

                for (var sourceId : sourceCaseIds) {
                    store.supersede(sourceId, tenantId, mergedId, "hygiene-consolidation");
                }

                eventSink.accept(new HygieneEvent.MemoryConsolidated(mergedId, sourceCaseIds));
                consolidated++;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Consolidation failed for group", e);
            }
        }
        return consolidated;
    }

    private List<List<CbrMatch<CbrRecord>>> findSimilarGroups(List<CbrMatch<CbrRecord>> memories) {
        var groups = new ArrayList<List<CbrMatch<CbrRecord>>>();
        var used = new boolean[memories.size()];
        for (int i = 0; i < memories.size(); i++) {
            if (used[i]) continue;
            var group = new ArrayList<CbrMatch<CbrRecord>>();
            group.add(memories.get(i));
            used[i] = true;
            for (int j = i + 1; j < memories.size(); j++) {
                if (used[j]) continue;
                if (memories.get(j).score() >= crossLinkSimilarityThreshold) {
                    group.add(memories.get(j));
                    used[j] = true;
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static String entityId(CbrMatch<CbrRecord> m) {
        var features = m.cbrRecord().features();
        if (features != null && features.containsKey("entity_id")) {
            var fv = features.get("entity_id");
            if (fv instanceof FeatureValue.StringVal sv) return sv.value();
        }
        return m.caseId();
    }
}
