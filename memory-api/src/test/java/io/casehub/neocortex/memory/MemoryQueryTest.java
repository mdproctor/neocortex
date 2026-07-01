package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryQueryTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("test");

    // --- forEntity factory ---

    @Test
    void forEntity_sets_defaults() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1");
        assertEquals(List.of("e1"), q.entityIds());
        assertEquals(DOMAIN, q.domain());
        assertEquals("t1", q.tenantId());
        assertNull(q.caseId());
        assertNull(q.question());
        assertEquals(20, q.limit());
        assertNull(q.since());
        assertEquals(MemoryOrder.CHRONOLOGICAL, q.order());
    }

    @Test
    void forEntities_accepts_multiple_entities() {
        var q = MemoryQuery.forEntities(List.of("e1", "e2"), DOMAIN, "t1");
        assertEquals(List.of("e1", "e2"), q.entityIds());
    }

    // --- with* fluent modifiers ---

    @Test
    void withCaseId_returns_new_query() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1").withCaseId("case-1");
        assertEquals("case-1", q.caseId());
        assertEquals(List.of("e1"), q.entityIds()); // unchanged
    }

    @Test
    void withQuestion_returns_new_query() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1").withQuestion("what happened?");
        assertEquals("what happened?", q.question());
    }

    @Test
    void withLimit_returns_new_query() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1").withLimit(5);
        assertEquals(5, q.limit());
    }

    @Test
    void withSince_returns_new_query() {
        Instant now = Instant.now();
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1").withSince(now);
        assertEquals(now, q.since());
    }

    @Test
    void withOrder_returns_new_query() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1").withOrder(MemoryOrder.RELEVANCE);
        assertEquals(MemoryOrder.RELEVANCE, q.order());
    }

    // --- validation ---

    @Test
    void null_entityIds_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryQuery(null, DOMAIN, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void empty_entityIds_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryQuery(List.of(), DOMAIN, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void too_many_entityIds_throws() {
        var ids = java.util.stream.IntStream.range(0, 26).mapToObj(i -> "e" + i).toList();
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryQuery(ids, DOMAIN, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void exactly_max_entityIds_is_valid() {
        var ids = java.util.stream.IntStream.range(0, 25).mapToObj(i -> "e" + i).toList();
        assertDoesNotThrow(
            () -> new MemoryQuery(ids, DOMAIN, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void null_domain_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryQuery(List.of("e1"), null, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void null_tenantId_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryQuery(List.of("e1"), DOMAIN, null, null, null, 10, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void null_order_throws() {
        assertThrows(NullPointerException.class,
            () -> new MemoryQuery(List.of("e1"), DOMAIN, "t1", null, null, 10, null, null));
    }

    @Test
    void zero_limit_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryQuery(List.of("e1"), DOMAIN, "t1", null, null, 0, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void negative_limit_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryQuery(List.of("e1"), DOMAIN, "t1", null, null, -1, null, MemoryOrder.CHRONOLOGICAL));
    }

    @Test
    void entityIds_list_is_defensively_copied() {
        var mutable = new java.util.ArrayList<>(List.of("e1"));
        var q = new MemoryQuery(mutable, DOMAIN, "t1", null, null, 10, null, MemoryOrder.CHRONOLOGICAL);
        mutable.add("e2");
        assertEquals(1, q.entityIds().size());
    }

    @Test
    void chained_with_modifiers_compose_correctly() {
        var q = MemoryQuery.forEntity("e1", DOMAIN, "t1")
            .withCaseId("case-1")
            .withQuestion("any question")
            .withLimit(5)
            .withOrder(MemoryOrder.RELEVANCE);
        assertEquals("case-1", q.caseId());
        assertEquals("any question", q.question());
        assertEquals(5, q.limit());
        assertEquals(MemoryOrder.RELEVANCE, q.order());
        assertEquals(List.of("e1"), q.entityIds()); // required fields unchanged
    }
}
