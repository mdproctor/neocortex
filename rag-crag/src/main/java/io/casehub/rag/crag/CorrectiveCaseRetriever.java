package io.casehub.rag.crag;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RelevanceEvaluator;
import io.casehub.rag.RelevanceGrade;
import io.casehub.rag.RetrievalQuality;
import io.casehub.rag.RetrievedChunk;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Decorator
@Priority(100)
public class CorrectiveCaseRetriever implements CaseRetriever {

    private final CaseRetriever delegate;
    private final RelevanceEvaluator evaluator;
    private final CragConfig config;
    private final Event<RetrievalQuality> qualityEvent;

    @Inject
    CorrectiveCaseRetriever(@Delegate @Any CaseRetriever delegate,
                            RelevanceEvaluator evaluator,
                            CragConfig config,
                            Event<RetrievalQuality> qualityEvent) {
        this.delegate = delegate;
        this.evaluator = evaluator;
        this.config = config;
        this.qualityEvent = qualityEvent;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        List<RetrievedChunk> chunks = delegate.retrieve(query, corpus, maxResults, filter);
        int totalRetrieved = chunks.size();

        List<String> contents = chunks.stream().map(RetrievedChunk::content).toList();
        List<RelevanceGrade> grades = evaluator.evaluateBatch(query, contents);

        Set<String> seen = new HashSet<>();
        int correct = 0, ambiguous = 0, incorrect = 0;
        List<RetrievedChunk> graded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RelevanceGrade grade = grades.get(i);
            switch (grade) {
                case CORRECT   -> correct++;
                case AMBIGUOUS -> ambiguous++;
                case INCORRECT -> incorrect++;
                default -> throw new IllegalStateException("Evaluator returned " + grade + " — implementations must return CORRECT, AMBIGUOUS, or INCORRECT");
            }
            RetrievedChunk c = chunks.get(i);
            seen.add(dedupKey(c));
            graded.add(c.withGrade(grade));
        }

        List<RetrievedChunk> surviving = new ArrayList<>(graded.stream()
            .filter(c -> c.grade() != RelevanceGrade.INCORRECT)
            .toList());

        boolean expanded = false;
        if (surviving.size() < maxResults && incorrect > 0) {
            expanded = true;
            int expandedLimit = maxResults * config.expansionMultiplier();
            List<RetrievedChunk> expandedChunks = delegate.retrieve(
                query, corpus, expandedLimit, filter);

            List<RetrievedChunk> newChunks = expandedChunks.stream()
                .filter(c -> !seen.contains(dedupKey(c)))
                .toList();

            if (!newChunks.isEmpty()) {
                List<String> newContents = newChunks.stream()
                    .map(RetrievedChunk::content).toList();
                List<RelevanceGrade> newGrades = evaluator.evaluateBatch(query, newContents);

                totalRetrieved += newChunks.size();
                for (int i = 0; i < newChunks.size(); i++) {
                    RelevanceGrade grade = newGrades.get(i);
                    switch (grade) {
                        case CORRECT   -> correct++;
                        case AMBIGUOUS -> ambiguous++;
                        case INCORRECT -> incorrect++;
                        default -> throw new IllegalStateException("Evaluator returned " + grade + " — implementations must return CORRECT, AMBIGUOUS, or INCORRECT");
                    }
                    if (grade != RelevanceGrade.INCORRECT) {
                        surviving.add(newChunks.get(i).withGrade(grade));
                    }
                }
            }
        }

        surviving.sort(Comparator.comparingInt(
            (RetrievedChunk c) -> c.grade() == RelevanceGrade.CORRECT ? 0 : 1));
        List<RetrievedChunk> result = surviving.stream()
            .limit(maxResults)
            .toList();

        qualityEvent.fire(new RetrievalQuality(
            totalRetrieved, correct, ambiguous, incorrect, true, expanded));

        return result;
    }

    private static String dedupKey(RetrievedChunk c) {
        return c.sourceDocumentId() + "\0" + c.content().hashCode();
    }
}
