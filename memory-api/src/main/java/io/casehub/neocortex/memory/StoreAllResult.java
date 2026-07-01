package io.casehub.neocortex.memory;

import java.util.List;

/**
 * Result of {@link CaseMemoryStore#storeAll}, carrying both the IDs assigned to
 * successfully stored inputs and any backend failures for inputs that could not be stored.
 *
 * <p><strong>Security exceptions are never collected here.</strong> A
 * {@link SecurityException} from a tenant-mismatch check propagates immediately and
 * aborts the entire storeAll call — no partial result is returned.
 *
 * <p><strong>Ordering invariant:</strong> {@link #stored()} lists IDs in the order the
 * corresponding inputs appeared in the original list. For inputs that failed, no ID is
 * present — correlate by {@link StoreFailure#inputIndex()}.
 *
 * @param stored   assigned memory IDs for all successfully stored inputs, in input order
 * @param failures backend failures for inputs that could not be stored (never security failures)
 */
public record StoreAllResult(List<String> stored, List<StoreFailure> failures) {

    public StoreAllResult {
        stored = List.copyOf(stored);
        failures = List.copyOf(failures);
    }

    /** Returns an empty result (no successful stores, no failures). */
    public static StoreAllResult empty() {
        return new StoreAllResult(List.of(), List.of());
    }

    /** Returns true if every input was stored successfully (no backend failures). */
    public boolean allSucceeded() {
        return failures.isEmpty();
    }
}
