package io.casehub.rag.crag;

import io.casehub.rag.RelevanceGrade;
import io.casehub.rag.RetrievalQuality;
import io.casehub.rag.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CragEvaluationLogic {

    private CragEvaluationLogic() {}

    record GradeResult(List<RetrievedChunk> graded, Set<String> seen,
                       int correct, int ambiguous, int incorrect) {}

    static boolean isAlreadyGraded(List<RetrievedChunk> chunks) {
        return !chunks.isEmpty()
            && chunks.stream().noneMatch(c -> c.grade() == RelevanceGrade.UNGRADED);
    }

    static GradeResult gradeChunks(List<RetrievedChunk> chunks,
                                   List<RelevanceGrade> grades) {
        Set<String> seen = new HashSet<>();
        int correct = 0, ambiguous = 0, incorrect = 0;
        List<RetrievedChunk> graded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RelevanceGrade grade = grades.get(i);
            switch (grade) {
                case CORRECT   -> correct++;
                case AMBIGUOUS -> ambiguous++;
                case INCORRECT -> incorrect++;
                default -> throw new IllegalStateException(
                    "Evaluator returned " + grade
                        + " — implementations must return CORRECT, AMBIGUOUS, or INCORRECT");
            }
            RetrievedChunk c = chunks.get(i);
            seen.add(dedupKey(c));
            graded.add(c.withGrade(grade));
        }
        return new GradeResult(graded, seen, correct, ambiguous, incorrect);
    }

    static List<RetrievedChunk> filterIncorrect(List<RetrievedChunk> graded) {
        return graded.stream()
            .filter(c -> c.grade() != RelevanceGrade.INCORRECT)
            .toList();
    }

    static boolean needsExpansion(int survivorCount, int maxResults,
                                  int incorrectCount) {
        return survivorCount < maxResults && incorrectCount > 0;
    }

    static List<RetrievedChunk> deduplicateExpanded(
            List<RetrievedChunk> expanded, Set<String> seen) {
        return expanded.stream()
            .filter(c -> !seen.contains(dedupKey(c)))
            .toList();
    }

    static List<RetrievedChunk> sortAndTruncate(
            List<RetrievedChunk> merged, int maxResults) {
        return merged.stream()
            .sorted(Comparator.comparingInt(
                (RetrievedChunk c) -> c.grade() == RelevanceGrade.CORRECT ? 0 : 1))
            .limit(maxResults)
            .toList();
    }

    static RetrievalQuality buildQualityEvent(
            int totalRetrieved, int correct, int ambiguous,
            int incorrect, boolean expanded) {
        return new RetrievalQuality(
            totalRetrieved, correct, ambiguous, incorrect, true, expanded);
    }

    static String dedupKey(RetrievedChunk c) {
        return c.sourceDocumentId() + "\0" + c.content();
    }
}
