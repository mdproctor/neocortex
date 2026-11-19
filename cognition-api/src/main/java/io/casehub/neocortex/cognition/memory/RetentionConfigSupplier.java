package io.casehub.neocortex.cognition.memory;

@FunctionalInterface
public interface RetentionConfigSupplier {
    RetentionConfig current();

    default RetentionConfig current(String tenantId) {
        return current();
    }
}
