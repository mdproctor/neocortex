package io.casehub.neocortex.memory;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactiveCaseMemoryStoreSpiTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("d");

    // Stubs for all three abstract methods; default methods under test are NOT overridden.
    // Compiler error on any omitted abstract method = it is abstract (RED state).
    // Compiles without implementing defaults = they are default (GREEN proves contract).
    private final ReactiveCaseMemoryStore sut = new ReactiveCaseMemoryStore() {
        @Override public Uni<String> store(MemoryInput i) { return Uni.createFrom().item("mem-1"); }
        @Override public Uni<List<Memory>> query(MemoryQuery q) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(0); }
    };

    @Test void storeAll_delegates_to_store() {
        var a = new MemoryInput("e1", DOMAIN, "t1", null, "a", Map.of());
        var b = new MemoryInput("e1", DOMAIN, "t1", null, "b", Map.of());
        var result = sut.storeAll(List.of(a, b)).await().indefinitely();
        assertEquals(List.of("mem-1", "mem-1"), result.stored());
        assertTrue(result.allSucceeded());
    }

    @Test void eraseEntity_default_fails_with_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseEntity("e", "t").await().indefinitely());
        assertEquals(MemoryCapability.ERASE_ENTITY, ex.required());
    }

    @Test void eraseById_default_fails_with_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseById("id", "e", "t").await().indefinitely());
        assertEquals(MemoryCapability.ERASE_BY_ID, ex.required());
    }

    @Test void eraseEntityAcrossTenants_default_fails_with_MemoryCapabilityException() {
        final var ex = assertThrows(MemoryCapabilityException.class,
            () -> sut.eraseEntityAcrossTenants("e", Set.of("t")).await().indefinitely());
        assertEquals(MemoryCapability.CROSS_TENANT_ERASE, ex.required());
    }
}
