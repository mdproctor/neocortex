package io.casehub.neocortex.rag;

import java.util.ArrayList;
import java.util.List;

public interface RelevanceEvaluator {

    RelevanceGrade evaluate(String query, String chunkContent);

    default List<RelevanceGrade> evaluateBatch(String query, List<String> chunkContents) {
        List<RelevanceGrade> grades = new ArrayList<>(chunkContents.size());
        for (String content : chunkContents) {
            grades.add(evaluate(query, content));
        }
        return List.copyOf(grades);
    }
}
