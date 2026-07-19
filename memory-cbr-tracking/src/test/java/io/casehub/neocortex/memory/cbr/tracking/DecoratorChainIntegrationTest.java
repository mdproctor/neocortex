package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.BridgedCbrStore;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.OutcomeWeightingFunction;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ReactiveCbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.testing.InMemoryCbrRetrievalTracker;
import jakarta.enterprise.event.Event;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DecoratorChainIntegrationTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    @Test void blockingChain_trackingCapturesPostWeightedScores() {
        var c1 = new FeatureVectorCbrCase("p1", "s1", null, 0.5, Map.of());
        var c2 = new FeatureVectorCbrCase("p2", "s2", null, 1.0, Map.of());
        var baseResults = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c1, "c1", 0.9),
                new ScoredCbrCase<>(c2, "c2", 0.8));

        var tracker = new InMemoryCbrRetrievalTracker();
        var events = new CopyOnWriteArrayList<CbrRetrievalRecorded>();

        CbrCaseMemoryStore base = stubBlockingDelegate(baseResults);
        CbrCaseMemoryStore weighted = new WeightingWrapper(base,
                (score, confidence) -> score * (0.7 + 0.3 * confidence));
        var tracked = new TrackingCbrCaseMemoryStore(weighted, tracker, events::add);

        var query = CbrQuery.of("t1", CBR, io.casehub.platform.api.path.Path.root(), "default", Map.of(), 10);
        var results = tracked.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(2);
        assertThat(events).hasSize(1);

        double weightedC1 = 0.9 * (0.7 + 0.3 * 0.5);
        double weightedC2 = 0.8 * (0.7 + 0.3 * 1.0);
        assertThat(results.getFirst().score()).isCloseTo(weightedC2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(results.get(1).score()).isCloseTo(weightedC1, org.assertj.core.data.Offset.offset(0.001));

        var tracedScores = events.getFirst().results().stream()
                .map(CbrRetrievalTrace.TracedCase::score).toList();
        assertThat(tracedScores).containsExactly(weightedC2, weightedC1);
    }

    @Test void storedAt_preservedThroughDecoratorChain() {
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        var c = new FeatureVectorCbrCase("p", "s", null, 0.8, Map.of());
        var baseResults = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.9, false, Map.of(), oneHourAgo, io.casehub.platform.api.path.Path.root()));

        CbrCaseMemoryStore base = stubBlockingDelegate(baseResults);
        CbrCaseMemoryStore weighted = new WeightingWrapper(base,
                (score, confidence) -> score * (0.7 + 0.3 * confidence));
        var decayed = new io.casehub.neocortex.memory.cbr.runtime.TemporalDecayCbrCaseMemoryStore(weighted);

        var query = CbrQuery.of("t1", CBR, io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5)
                .withTemporalDecay(new io.casehub.neocortex.memory.cbr.TemporalDecay.HalfLife(java.time.Duration.ofHours(1)));
        var results = decayed.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().storedAt()).isEqualTo(oneHourAgo);
        double expectedWeighted = 0.9 * (0.7 + 0.3 * 0.8);
        double expectedDecayed = expectedWeighted * 0.5;
        assertThat(results.getFirst().score()).isCloseTo(expectedDecayed, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test void reactiveDoubleRecordingGuard_bridgedStore_recordsOnce() {
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(new ScoredCbrCase<>(c, "c1", 0.85));

        var blockingTracker = new InMemoryCbrRetrievalTracker();
        var blockingEvents = new CopyOnWriteArrayList<CbrRetrievalRecorded>();
        var reactiveEvents = new CopyOnWriteArrayList<CbrRetrievalRecorded>();

        CbrCaseMemoryStore blockingBase = stubBlockingDelegate(results);
        var blockingTracked = new TrackingCbrCaseMemoryStore(blockingBase, blockingTracker, blockingEvents::add);

        var reactiveTracker = new SimpleReactiveCbrRetrievalTracker();
        ReactiveCbrCaseMemoryStore bridge = new BridgedReactiveTestStore(blockingTracked);
        var reactiveTracked = new ReactiveTrackingCbrCaseMemoryStore(bridge, reactiveTracker, stubEvent(reactiveEvents));

        var query = CbrQuery.of("t1", CBR, io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = reactiveTracked.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();

        assertThat(returned).hasSize(1);
        assertThat(blockingEvents).hasSize(1);
        assertThat(reactiveEvents).as("reactive decorator should skip recording on bridged store").isEmpty();
    }

    @Test void reactiveNonBridgedStore_recordsOnReactiveSide() {
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(new ScoredCbrCase<>(c, "c1", 0.85));

        var reactiveTracker = new SimpleReactiveCbrRetrievalTracker();
        var reactiveEvents = new CopyOnWriteArrayList<CbrRetrievalRecorded>();

        ReactiveCbrCaseMemoryStore nativeReactive = stubReactiveDelegate(results);
        var reactiveTracked = new ReactiveTrackingCbrCaseMemoryStore(nativeReactive, reactiveTracker, stubEvent(reactiveEvents));

        var query = CbrQuery.of("t1", CBR, io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = reactiveTracked.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();

        assertThat(returned).hasSize(1);
        assertThat(reactiveEvents).hasSize(1);
        assertThat(reactiveEvents.getFirst().traceId()).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubBlockingDelegate(List<? extends ScoredCbrCase<?>> results) {
        return new CbrCaseMemoryStore() {
            @Override public void registerSchema(CbrFeatureSchema s) {}
            @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return "id"; }
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) { return (List<ScoredCbrCase<C>>) (List<?>) results; }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String e, String t) { return 0; }
            @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return 0; }
            @Override public void recordOutcome(String c, String t, CbrOutcome o) {}
            @Override public Integer purge(CbrRetentionPolicy p) { return 0; }
            @Override public void supersede(String c, String t, String s, String r) {}
            @Override public void reinstate(String c, String t) {}
        @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED; }
        @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return java.util.List.of(); }

        };
    }

    @SuppressWarnings("unchecked")
    private ReactiveCbrCaseMemoryStore stubReactiveDelegate(List<? extends ScoredCbrCase<?>> results) {
        return new ReactiveCbrCaseMemoryStore() {
            @Override public Uni<Void> registerSchema(CbrFeatureSchema s) { return Uni.createFrom().voidItem(); }
            @Override public Uni<String> store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return Uni.createFrom().item("id"); }
            @Override public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery q, Class<C> cl) { return Uni.createFrom().item((List<ScoredCbrCase<C>>) (List<?>) results); }
            @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseEntity(String e, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> recordOutcome(String c, String t, CbrOutcome o) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Integer> purge(CbrRetentionPolicy p) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> supersede(String c, String t, String s, String r) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Void> reinstate(String c, String t) { return Uni.createFrom().voidItem(); }
        @Override public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) { return io.smallrye.mutiny.Uni.createFrom().item(io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED); }
        @Override public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return io.smallrye.mutiny.Uni.createFrom().item(java.util.List.of()); }

        };
    }

    @SuppressWarnings("unchecked")
    private static Event<CbrRetrievalRecorded> stubEvent(List<CbrRetrievalRecorded> sink) {
        return new Event<>() {
            @Override
            public void fire(CbrRetrievalRecorded event)                                                                                                                  {sink.add(event);}

            @Override
            public <U extends CbrRetrievalRecorded> java.util.concurrent.CompletionStage<U> fireAsync(U event)                                                            {
                                                                                                                                                                              sink.add(event);
                                                                                                                                                                              return java.util.concurrent.CompletableFuture.completedFuture(event);
                                                                                                                                                                          }

            @Override
            public <U extends CbrRetrievalRecorded> java.util.concurrent.CompletionStage<U> fireAsync(U event, jakarta.enterprise.event.NotificationOptions options) {
                                                                                                                                                                              sink.add(event);
                                                                                                                                                                              return java.util.concurrent.CompletableFuture.completedFuture(event);
                                                                                                                                                                          }

            @Override
            public Event<CbrRetrievalRecorded> select(java.lang.annotation.Annotation... qualifiers)                                                                      {return this;}

            @Override
            public <U extends CbrRetrievalRecorded> Event<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers)                                      {return (Event<U>) this;}

            @Override
            public <U extends CbrRetrievalRecorded> Event<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers)        {return (Event<U>) this;}
        };
    }


    private static final class WeightingWrapper implements CbrCaseMemoryStore {
        private final CbrCaseMemoryStore delegate;
        private final OutcomeWeightingFunction fn;
        WeightingWrapper(CbrCaseMemoryStore delegate, OutcomeWeightingFunction fn) { this.delegate = delegate; this.fn = fn; }
        @Override public void registerSchema(CbrFeatureSchema s) { delegate.registerSchema(s); }
        @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return delegate.store(c, t, e, d, tid, cid, scope); }
        @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) {
            var results = delegate.retrieveSimilar(q, cl);
            var weighted = new java.util.ArrayList<ScoredCbrCase<C>>(results.size());
            for (var sc : results) {
                double conf = sc.cbrCase().confidence() != null ? sc.cbrCase().confidence() : 1.0;
                weighted.add(sc.withScore(fn.apply(sc.score(), conf)));
            }
            weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
            return Collections.unmodifiableList(weighted);
        }
        @Override public Integer erase(EraseRequest r) { return delegate.erase(r); }
        @Override public Integer eraseEntity(String e, String t) { return delegate.eraseEntity(e, t); }
        @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return delegate.eraseByScope(scope, t); }
        @Override public void recordOutcome(String c, String t, CbrOutcome o) { delegate.recordOutcome(c, t, o); }
        @Override public Integer purge(CbrRetentionPolicy p) { return delegate.purge(p); }
        @Override public void supersede(String c, String t, String s, String r) { delegate.supersede(c, t, s, r); }
        @Override public void reinstate(String c, String t) { delegate.reinstate(c, t); }
        @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED; }
        @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return java.util.List.of(); }

    }

    private static final class BridgedReactiveTestStore implements ReactiveCbrCaseMemoryStore, BridgedCbrStore {
        private final CbrCaseMemoryStore delegate;
        BridgedReactiveTestStore(CbrCaseMemoryStore delegate) { this.delegate = delegate; }
        @Override public Uni<Void> registerSchema(CbrFeatureSchema s) { return Uni.createFrom().voidItem().invoke(() -> delegate.registerSchema(s)); }
        @Override public Uni<String> store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return Uni.createFrom().item(() -> delegate.store(c, t, e, d, tid, cid, scope)); }
        @Override public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery q, Class<C> cl) { return Uni.createFrom().item(() -> delegate.retrieveSimilar(q, cl)); }
        @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(() -> delegate.erase(r)); }
        @Override public Uni<Integer> eraseEntity(String e, String t) { return Uni.createFrom().item(() -> delegate.eraseEntity(e, t)); }
        @Override public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return Uni.createFrom().item(() -> delegate.eraseByScope(scope, t)); }
        @Override public Uni<Void> recordOutcome(String c, String t, CbrOutcome o) { return Uni.createFrom().voidItem().invoke(() -> delegate.recordOutcome(c, t, o)); }
        @Override public Uni<Integer> purge(CbrRetentionPolicy p) { return Uni.createFrom().item(() -> delegate.purge(p)); }
        @Override public Uni<Void> supersede(String c, String t, String s, String r) { return Uni.createFrom().voidItem().invoke(() -> delegate.supersede(c, t, s, r)); }
        @Override public Uni<Void> reinstate(String c, String t) { return Uni.createFrom().voidItem().invoke(() -> delegate.reinstate(c, t)); }
        @Override public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) { return io.smallrye.mutiny.Uni.createFrom().item(io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED); }
        @Override public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return io.smallrye.mutiny.Uni.createFrom().item(java.util.List.of()); }

    }

    private static final class SimpleReactiveCbrRetrievalTracker implements ReactiveCbrRetrievalTracker {
        @Override public Uni<String> record(CbrQuery query, List<ScoredCbrCase<?>> results) {
            return Uni.createFrom().item(UUID.randomUUID().toString());
        }
        @Override public Uni<List<CbrRetrievalTrace>> findTraces(String caseType, String tenantId, MemoryDomain domain, Instant start, Instant end) {
            return Uni.createFrom().item(List.of());
        }
        @Override public Uni<Integer> purgeOlderThan(Instant cutoff) { return Uni.createFrom().item(0); }
    }
}
