package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.quarkus.arc.properties.IfBuildProperty;
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
public class TrackingCaseRetriever implements CaseRetriever {

    private static final Logger LOG = Logger.getLogger(TrackingCaseRetriever.class);

    private final CaseRetriever delegate;
    private final RetrievalTracker tracker;
    private final Consumer<RetrievalRecorded> eventSink;

    @Inject
    TrackingCaseRetriever(@Delegate @Any CaseRetriever delegate,
                          RetrievalTracker tracker,
                          Event<RetrievalRecorded> recordedEvent) {
        this(delegate, tracker, recordedEvent::fire);
    }

    TrackingCaseRetriever(CaseRetriever delegate, RetrievalTracker tracker,
                          Consumer<RetrievalRecorded> eventSink) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.eventSink = eventSink;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        List<RetrievedChunk> chunks = delegate.retrieve(query, corpus, maxResults, filter);
        if (TrackingLogic.isAlreadyTracked(chunks)) {
            return chunks;
        }
        try {
            String retrievalId = tracker.record(query, corpus, chunks, maxResults);
            var docRefs = TrackingLogic.toDocumentRefs(chunks);
            eventSink.accept(new RetrievalRecorded(retrievalId, query, corpus, docRefs));
            return TrackingLogic.stamp(chunks, retrievalId);
        } catch (Exception e) {
            LOG.warn("Retrieval tracking failed — returning results unchanged", e);
            return chunks;
        }
    }
}
