package io.casehub.neocortex.rag.runtime;

import io.quarkus.arc.Arc;

final class RequestContextCheck {

    private RequestContextCheck() {}

    static boolean isActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }
}
