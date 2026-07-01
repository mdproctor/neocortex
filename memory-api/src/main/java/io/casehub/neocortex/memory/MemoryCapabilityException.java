package io.casehub.neocortex.memory;

public class MemoryCapabilityException extends RuntimeException {

    private final MemoryCapability required;

    public MemoryCapabilityException(final MemoryCapability required, final Class<?> adapter) {
        super(adapter.getSimpleName() + " does not support " + required.name()
              + " — check CaseMemoryStore.capabilities() before calling");
        this.required = required;
    }

    public MemoryCapability required() {
        return required;
    }
}
