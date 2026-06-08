package io.casehub.inference.tasks;

public record RankedResult(String text, float score, int originalIndex) {}
