package io.casehub.neocortex.rag.crossencoder;

import io.casehub.neocortex.rag.RelevanceGrade;

public record ScoredGrade(RelevanceGrade grade, float score) {}
