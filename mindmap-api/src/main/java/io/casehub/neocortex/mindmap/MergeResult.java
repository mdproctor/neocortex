package io.casehub.neocortex.mindmap;

import java.util.List;
import java.util.Set;

public record MergeResult(
    String survivingNodeId,
    int edgesRepointed,
    int aliasesMerged,
    int duplicateEdgesRemoved,
    Set<String> traitsMerged,
    List<MergeConflict> propertyConflicts
) {}
