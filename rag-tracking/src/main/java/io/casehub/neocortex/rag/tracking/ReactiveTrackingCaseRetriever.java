package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Consumer;

@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.rag.tracking.enabled", stringValue = "true")
public class ReactiveTrackingCaseRetriever implements ReactiveCaseRetriever {

    private static final Logger LOG = Logger.getLogger(ReactiveTrackingCaseRetriever.class);

    private final ReactiveCaseRetriever delegate;
    private final RetrievalTracker tracker;
    private final Consumer<RetrievalRecorded> eventSink;

    @Inject
    ReactiveTrackingCaseRetriever(@Delegate @Any ReactiveCaseRetriever delegate,
                                  RetrievalTracker tracker,
                                  Event<RetrievalRecorded> recordedEvent) {
        this(delegate, tracker, recordedEvent::fireAsync);
    }

    ReactiveTrackingCaseRetriever(ReactiveCaseRetriever delegate,
                                  RetrievalTracker tracker,
                                  Consumer<RetrievalRecorded> eventSink) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.eventSink = eventSink;
    }

    @Override
    public Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                               int maxResults, PayloadFilter filter) {
        return delegate.retrieve(query, corpus, maxResults, filter)
            .onItem().transformToUni(chunks -> {
                if (TrackingLogic.isAlreadyTracked(chunks)) {
                    return Uni.createFrom().item(chunks);
                }
                return Uni.createFrom().item(() -> {
                        String id = tracker.record(query, corpus, chunks, maxResults);
                        var refs = TrackingLogic.toDocumentRefs(chunks);
                        eventSink.accept(new RetrievalRecorded(id, query, corpus, refs));
                        return TrackingLogic.stamp(chunks, id);
                    })
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                    .onFailure().recoverWithItem(failure -> {
                        LOG.warn("Retrieval tracking failed — returning results unchanged", failure);
                        return chunks;
                    });
            });
    }
}
