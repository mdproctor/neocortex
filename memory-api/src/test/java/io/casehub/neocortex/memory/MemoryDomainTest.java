package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDomainTest {

    @Test
    void name_is_returned() {
        assertEquals("health", new MemoryDomain("health").name());
    }

    @Test
    void null_name_throws() {
        assertThrows(NullPointerException.class, () -> new MemoryDomain(null));
    }

    @Test
    void blank_name_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryDomain("  "));
    }

    @Test
    void empty_name_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryDomain(""));
    }

    @Test
    void equality_by_name() {
        assertEquals(new MemoryDomain("health"), new MemoryDomain("health"));
        assertNotEquals(new MemoryDomain("health"), new MemoryDomain("finance"));
    }
}
