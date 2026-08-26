package io.casehub.neocortex.mindmap;

import java.util.Objects;
import java.util.Set;

public record EdgeTypeDefinition(
    String canonical,
    Set<String> aliases,
    Double defaultDecayHalfLifeDays
) {

    public EdgeTypeDefinition {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.isBlank()) throw new IllegalArgumentException("canonical must not be blank");
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }
}
