package io.casehub.neocortex.memory;

/**
 * Records a single backend failure from a {@link CaseMemoryStore#storeAll} call.
 *
 * <p>SecurityException is never wrapped here — it propagates immediately and the whole
 * storeAll call aborts. StoreFailure carries only non-security backend failures.
 *
 * @param inputIndex zero-based position of the failed input in the original list
 * @param input      the MemoryInput that failed (use for retry)
 * @param cause      the exception thrown by the backend store operation
 */
public record StoreFailure(int inputIndex, MemoryInput input, RuntimeException cause) {}
