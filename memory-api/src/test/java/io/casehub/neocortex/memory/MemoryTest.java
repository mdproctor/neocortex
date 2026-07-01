package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("test");

    @Test
    void valid_memory_constructs() {
        var now = Instant.now();
        var m = new Memory("mem-1", "e1", DOMAIN, "t1", null, "text", Map.of(), now);
        assertEquals("mem-1", m.memoryId());
        assertEquals("e1", m.entityId());
        assertEquals(now, m.createdAt());
    }

    @Test
    void attributes_are_defensively_copied() {
        var mutable = new HashMap<String, String>();
        mutable.put("k", "v");
        var m = new Memory("id", "e1", DOMAIN, "t1", null, "text", mutable, Instant.now());
        mutable.put("k2", "v2");
        assertFalse(m.attributes().containsKey("k2"));
    }

    @Test
    void attributes_map_is_unmodifiable() {
        var m = new Memory("id", "e1", DOMAIN, "t1", null, "text",
            Map.of("k", "v"), Instant.now());
        assertThrows(UnsupportedOperationException.class,
            () -> m.attributes().put("x", "y"));
    }
}
