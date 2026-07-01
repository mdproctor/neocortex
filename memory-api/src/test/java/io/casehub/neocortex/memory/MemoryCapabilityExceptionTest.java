package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryCapabilityExceptionTest {

    @Test
    void message_contains_capability_name_and_adapter_class() {
        final var ex = new MemoryCapabilityException(MemoryCapability.TEMPORAL_GRAPH, String.class);
        assertTrue(ex.getMessage().contains("TEMPORAL_GRAPH"), "message must contain capability name");
        assertTrue(ex.getMessage().contains("String"), "message must contain adapter class simple name");
    }

    @Test
    void required_returns_the_capability() {
        final var ex = new MemoryCapabilityException(MemoryCapability.ERASE_BY_ID, Object.class);
        assertEquals(MemoryCapability.ERASE_BY_ID, ex.required());
    }

    @Test
    void is_unchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(MemoryCapabilityException.class));
    }
}
