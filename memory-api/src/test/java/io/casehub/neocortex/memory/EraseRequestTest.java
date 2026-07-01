package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EraseRequestTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("test");

    @Test
    void valid_request_constructs() {
        var r = new EraseRequest("e1", DOMAIN, "t1", null);
        assertEquals("e1", r.entityId());
        assertEquals(DOMAIN, r.domain());
        assertEquals("t1", r.tenantId());
        assertNull(r.caseId());
    }

    @Test
    void null_entityId_throws() {
        assertThrows(NullPointerException.class,
            () -> new EraseRequest(null, DOMAIN, "t1", null));
    }

    @Test
    void null_tenantId_throws() {
        assertThrows(NullPointerException.class,
            () -> new EraseRequest("e1", DOMAIN, null, null));
    }

    @Test
    void null_domain_throws() {
        assertThrows(NullPointerException.class,
            () -> new EraseRequest("e1", null, "t1", null));
    }

    @Test
    void caseId_is_nullable() {
        assertDoesNotThrow(() -> new EraseRequest("e1", DOMAIN, "t1", null));
        assertDoesNotThrow(() -> new EraseRequest("e1", DOMAIN, "t1", "case-1"));
    }
}
