package io.casehub.neocortex.mindmap;

public record MergeConflict(String key, String keptValue, String discardedValue) {}
