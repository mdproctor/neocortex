package io.casehub.neocortex.inference.tasks;

public record RankedResult(String text, float score, int originalIndex) {}
