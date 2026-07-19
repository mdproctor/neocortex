package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.BridgedCbrStore;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ReactiveCbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ReactiveTrackingCbrCaseMemoryStoreTest {

    @Test void recordsTraceAndFiresEvent() {
        var tracker = stubTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85));
        var delegate = stubDelegate(results);
        var decorator = new ReactiveTrackingCbrCaseMemoryStore(delegate, tracker, stubEvent(eventRef));

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();

        assertThat(returned).isEqualTo(results);
        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().traceId()).isNotBlank();
        assertThat(eventRef.get().results()).hasSize(1);
        assertThat(eventRef.get().results().getFirst().caseId()).isEqualTo("c1");
    }

    @Test void trackerFailure_nonFatal() {
        ReactiveCbrRetrievalTracker failingTracker = new ReactiveCbrRetrievalTracker() {
            @Override public Uni<String> record(CbrQuery q, List<ScoredCbrCase<?>> r) {
                return Uni.createFrom().failure(new RuntimeException("boom"));
            }
            @Override public Uni<List<CbrRetrievalTrace>> findTraces(String ct, String t, MemoryDomain d, Instant s, Instant u) { return Uni.createFrom().item(List.of()); }
            @Override public Uni<Integer> purgeOlderThan(Instant cutoff) { return Uni.createFrom().item(0); }
        };
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85));
        var delegate = stubDelegate(results);
        var decorator = new ReactiveTrackingCbrCaseMemoryStore(delegate, failingTracker, stubEvent(new AtomicReference<>()));

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(returned).isEqualTo(results);
    }

    @Test void resultsUnchanged() {
        var tracker = stubTracker();
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85, true, Map.of("f", 0.9), null, io.casehub.platform.api.path.Path.root()));
        var delegate = stubDelegate(results);
        var decorator = new ReactiveTrackingCbrCaseMemoryStore(delegate, tracker, stubEvent(new AtomicReference<>()));

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(returned.getFirst().score()).isEqualTo(0.85);
        assertThat(returned.getFirst().reranked()).isTrue();
        assertThat(returned.getFirst().caseId()).isEqualTo("c1");
    }

    @Test void bridgeActive_passesThrough() {
        var tracker = stubTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c = new FeatureVectorCbrCase("p", "s", null, 0.9, Map.of());
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85));
        var bridgedDelegate = stubBridgedDelegate(results);
        var decorator = new ReactiveTrackingCbrCaseMemoryStore(bridgedDelegate, tracker, stubEvent(eventRef));

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class)
                .await().indefinitely();

        assertThat(returned).isEqualTo(results);
        assertThat(eventRef.get()).isNull();
    }

    private ReactiveCbrRetrievalTracker stubTracker() {
        return new ReactiveCbrRetrievalTracker() {
            @Override public Uni<String> record(CbrQuery q, List<ScoredCbrCase<?>> r) {
                return Uni.createFrom().item(java.util.UUID.randomUUID().toString());
            }
            @Override public Uni<List<CbrRetrievalTrace>> findTraces(String ct, String t, MemoryDomain d, Instant s, Instant u) { return Uni.createFrom().item(List.of()); }
            @Override public Uni<Integer> purgeOlderThan(Instant cutoff) { return Uni.createFrom().item(0); }
        };
    }

    @SuppressWarnings("unchecked")
    private ReactiveCbrCaseMemoryStore stubDelegate(List<? extends ScoredCbrCase<?>> results) {
        return new ReactiveCbrCaseMemoryStore() {
            @Override public Uni<Void> registerSchema(CbrFeatureSchema s) { return Uni.createFrom().voidItem(); }
            @Override public Uni<String> store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return Uni.createFrom().item("id"); }
            @Override public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                return Uni.createFrom().item((List<ScoredCbrCase<C>>) (List<?>) results);
            }
            @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseEntity(String e, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> recordOutcome(String c, String t, CbrOutcome o) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Integer> purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy p) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> supersede(String c, String t, String s, String r) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Void> reinstate(String c, String t) { return Uni.createFrom().voidItem(); }
        @Override public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) { return io.smallrye.mutiny.Uni.createFrom().item(io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED); }
        @Override public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return io.smallrye.mutiny.Uni.createFrom().item(java.util.List.of()); }

        };
    }

    @SuppressWarnings("unchecked")
    private ReactiveCbrCaseMemoryStore stubBridgedDelegate(List<? extends ScoredCbrCase<?>> results) {
        return new BridgedReactiveStub(results);
    }

    private static final class BridgedReactiveStub implements ReactiveCbrCaseMemoryStore, BridgedCbrStore {
        private final List<? extends ScoredCbrCase<?>> results;
        BridgedReactiveStub(List<? extends ScoredCbrCase<?>> results) { this.results = results; }
        @Override public Uni<Void> registerSchema(CbrFeatureSchema s) { return Uni.createFrom().voidItem(); }
        @Override public Uni<String> store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return Uni.createFrom().item("id"); }
        @Override @SuppressWarnings("unchecked") public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery q, Class<C> cl) {
            return Uni.createFrom().item((List<ScoredCbrCase<C>>) (List<?>) results);
        }
        @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(0); }
        @Override public Uni<Integer> eraseEntity(String e, String t) { return Uni.createFrom().item(0); }
        @Override public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return Uni.createFrom().item(0); }
        @Override public Uni<Void> recordOutcome(String c, String t, CbrOutcome o) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Integer> purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy p) { return Uni.createFrom().item(0); }
        @Override public Uni<Void> supersede(String c, String t, String s, String r) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> reinstate(String c, String t) { return Uni.createFrom().voidItem(); }
        @Override public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) { return io.smallrye.mutiny.Uni.createFrom().item(io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED); }
        @Override public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return io.smallrye.mutiny.Uni.createFrom().item(java.util.List.of()); }

    }

    @SuppressWarnings("unchecked")
    private static Event<CbrRetrievalRecorded> stubEvent(AtomicReference<CbrRetrievalRecorded> ref) {
        return new Event<>() {
            @Override public void fire(CbrRetrievalRecorded event) { ref.set(event); }
            @Override public <U extends CbrRetrievalRecorded> CompletionStage<U> fireAsync(U event) { ref.set(event); return CompletableFuture.completedFuture(event); }
            @Override public <U extends CbrRetrievalRecorded> CompletionStage<U> fireAsync(U event, NotificationOptions options) { ref.set(event); return CompletableFuture.completedFuture(event); }
            @Override public Event<CbrRetrievalRecorded> select(Annotation... qualifiers) { return this; }
            @Override public <U extends CbrRetrievalRecorded> Event<U> select(Class<U> subtype, Annotation... qualifiers) { return (Event<U>) this; }
            @Override public <U extends CbrRetrievalRecorded> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return (Event<U>) this; }
        };
    }
}
