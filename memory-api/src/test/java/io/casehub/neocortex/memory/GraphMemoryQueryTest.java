package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphMemoryQueryTest {

    static final MemoryDomain DOMAIN = new MemoryDomain("test-domain");

    @Test
    void forEntity_applies_defaults() {
        final var q = GraphMemoryQuery.forEntity("e1", DOMAIN, "tenant-1", "who approved?");
        assertEquals("tenant-1", q.tenantId());
        assertEquals(List.of("e1"), q.entityIds());
        assertEquals(DOMAIN, q.domain());
        assertEquals("who approved?", q.question());
        assertEquals(10, q.limit());
        assertNull(q.since());
        assertNull(q.validAt());
        assertNull(q.entityTypes());
        assertEquals(MemoryResultType.DEFAULT, q.resultType());
    }

    @Test
    void null_resultType_normalized_to_DEFAULT() {
        final var q = new GraphMemoryQuery("t", List.of("e"), DOMAIN, "q", 5,
                null, null, null, null);
        assertEquals(MemoryResultType.DEFAULT, q.resultType());
    }

    @Test
    void null_question_throws() {
        assertThrows(NullPointerException.class, () ->
            new GraphMemoryQuery("t", List.of("e"), DOMAIN, null, 5, null, null, null, null));
    }

    @Test
    void blank_question_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphMemoryQuery("t", List.of("e"), DOMAIN, "  ", 5, null, null, null, null));
    }

    @Test
    void null_domain_throws() {
        assertThrows(NullPointerException.class, () ->
            new GraphMemoryQuery("t", List.of("e"), null, "q", 5, null, null, null, null));
    }

    @Test
    void empty_entityIds_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphMemoryQuery("t", List.of(), DOMAIN, "q", 5, null, null, null, null));
    }

    @Test
    void limit_below_one_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphMemoryQuery("t", List.of("e"), DOMAIN, "q", 0, null, null, null, null));
    }

    @Test
    void entityIds_are_defensively_copied() {
        final var ids = new java.util.ArrayList<>(List.of("e1"));
        final var q = new GraphMemoryQuery("t", ids, DOMAIN, "q", 5, null, null, null, null);
        ids.add("e2");
        assertEquals(1, q.entityIds().size());
    }

    @Test
    void entityTypes_are_defensively_copied_when_non_null() {
        final var types = new java.util.HashSet<>(Set.of("Person"));
        final var q = new GraphMemoryQuery("t", List.of("e"), DOMAIN, "q", 5,
                null, null, types, null);
        types.add("Other");
        assertEquals(1, q.entityTypes().size());
    }

    @Test
    void null_entityTypes_remains_null() {
        final var q = GraphMemoryQuery.forEntity("e", DOMAIN, "t", "q?");
        assertNull(q.entityTypes());
    }

    @Test
    void withValidAt_returns_new_instance_with_updated_field() {
        final var q = GraphMemoryQuery.forEntity("e", DOMAIN, "t", "q?");
        final var now = Instant.now();
        final var updated = q.withValidAt(now);
        assertEquals(now, updated.validAt());
        assertNull(q.validAt(), "original must be unchanged");
    }

    @Test
    void withLimit_returns_new_instance_with_updated_field() {
        final var q = GraphMemoryQuery.forEntity("e", DOMAIN, "t", "q?");
        final var updated = q.withLimit(50);
        assertEquals(50, updated.limit());
        assertEquals(10, q.limit());
    }
}
