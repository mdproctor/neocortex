package io.casehub.neocortex.memory.graphiti;

import io.casehub.neocortex.memory.*;
import jakarta.ws.rs.WebApplicationException;

public class GraphitiStoreException extends RuntimeException {

    private final int httpStatus;

    public GraphitiStoreException(final String message, final Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
    }

    public GraphitiStoreException(final int httpStatus, final String body, final Throwable cause) {
        super("Graphiti HTTP " + httpStatus + ": " + body, cause);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    static GraphitiStoreException from(final WebApplicationException e) {
        final int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
        String body = "";
        try {
            if (e.getResponse() != null) body = e.getResponse().readEntity(String.class);
        } catch (final Exception ignored) {
            // best-effort; body already unavailable
        }
        return new GraphitiStoreException(status, body, e);
    }
}
