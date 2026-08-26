package io.casehub.neocortex.mindmap;

import java.util.Objects;

public record NodeRef(
    String scheme,
    String id,
    String qualifier
) {

    public NodeRef {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(id, "id");
    }
}
