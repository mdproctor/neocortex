package io.casehub.neocortex.cognition.coping;

public record AttentionDirective(
    AttentionDirectiveType type,
    String target,
    String reason
) {}
