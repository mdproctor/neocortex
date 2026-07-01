package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryInputTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("test");

    @Test
    void valid_input_constructs() {
        var input = new MemoryInput("e1", DOMAIN, "t1", null, "text", Map.of());
        assertEquals("e1", input.entityId());
        assertEquals(DOMAIN, input.domain());
        assertEquals("t1", input.tenantId());
        assertNull(input.caseId());
        assertEquals("text", input.text());
        assertTrue(input.attributes().isEmpty());
    }

    @Test
    void null_entityId_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryInput(null, DOMAIN, "t1", null, "text", Map.of()));
    }

    @Test
    void null_domain_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryInput("e1", null, "t1", null, "text", Map.of()));
    }

    @Test
    void null_tenantId_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryInput("e1", DOMAIN, null, null, "text", Map.of()));
    }

    @Test
    void null_text_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryInput("e1", DOMAIN, "t1", null, null, Map.of()));
    }

    @Test
    void attributes_are_defensively_copied() {
        var mutable = new HashMap<String, String>();
        mutable.put("k", "v");
        var input = new MemoryInput("e1", DOMAIN, "t1", null, "text", mutable);
        mutable.put("k2", "v2");
        assertFalse(input.attributes().containsKey("k2"));
    }

    @Test
    void attributes_map_is_unmodifiable() {
        var input = new MemoryInput("e1", DOMAIN, "t1", null, "text", Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class,
            () -> input.attributes().put("x", "y"));
    }

    @Test
    void caseId_is_nullable() {
        assertDoesNotThrow(() -> new MemoryInput("e1", DOMAIN, "t1", "case-1", "text", Map.of()));
    }

    @Test
    void blank_text_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryInput("e1", DOMAIN, "t1", null, "   ", Map.of()));
    }

    @Test
    void empty_text_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryInput("e1", DOMAIN, "t1", null, "", Map.of()));
    }
}
