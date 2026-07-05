package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryScanRequestTest {

    @Test
    void requiresTenantId() {
        assertThrows(NullPointerException.class,
            () -> new MemoryScanRequest(null, null, null, null, 10, null));
    }

    @Test
    void requiresPositiveLimit() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryScanRequest("t1", null, null, null, 0, null));
    }

    @Test
    void rejectsAttributeValueWithoutKey() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryScanRequest("t1", null, null, "val", 10, null));
    }

    @Test
    void rejectsAttributeKeyWithoutValue() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryScanRequest("t1", null, "key", null, 10, null));
    }

    @Test
    void acceptsNoFilter() {
        var r = new MemoryScanRequest("t1", null, null, null, 10, null);
        assertEquals("t1", r.tenantId());
        assertNull(r.attributeKey());
    }

    @Test
    void acceptsKeyValuePair() {
        var r = new MemoryScanRequest("t1", null, "cbr.caseType", "aml", 10, null);
        assertEquals("cbr.caseType", r.attributeKey());
        assertEquals("aml", r.attributeValue());
    }

    @Test
    void acceptsDomainFilter() {
        var r = new MemoryScanRequest("t1", "cbr", null, null, 10, null);
        assertEquals("cbr", r.domain());
    }

    @Test
    void acceptsCursor() {
        var r = new MemoryScanRequest("t1", null, null, null, 10, "mem-abc");
        assertEquals("mem-abc", r.afterMemoryId());
    }
}
