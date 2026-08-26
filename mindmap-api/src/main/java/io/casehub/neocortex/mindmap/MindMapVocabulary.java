package io.casehub.neocortex.mindmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record MindMapVocabulary(List<EdgeTypeDefinition> edgeTypes) {

    public MindMapVocabulary {
        edgeTypes = List.copyOf(edgeTypes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<EdgeTypeDefinition> edgeTypes = new ArrayList<>();

        public Builder edgeType(String canonical, String... aliases) {
            edgeTypes.add(new EdgeTypeDefinition(canonical, Set.of(aliases), null));
            return this;
        }

        public Builder edgeType(String canonical, Double decayHalfLifeDays, String... aliases) {
            edgeTypes.add(new EdgeTypeDefinition(canonical, Set.of(aliases), decayHalfLifeDays));
            return this;
        }

        public MindMapVocabulary build() {
            return new MindMapVocabulary(edgeTypes);
        }
    }
}
