package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryAttributeKeysTest {

    @Test
    void format_confidence_produces_four_decimal_places() {
        assertEquals("0.8500", MemoryAttributeKeys.formatConfidence(0.85));
        assertEquals("1.0000", MemoryAttributeKeys.formatConfidence(1.0));
        assertEquals("0.0000", MemoryAttributeKeys.formatConfidence(0.0));
    }

    @Test
    void format_confidence_rejects_out_of_range() {
        assertThrows(IllegalArgumentException.class, () -> MemoryAttributeKeys.formatConfidence(-0.01));
        assertThrows(IllegalArgumentException.class, () -> MemoryAttributeKeys.formatConfidence(1.01));
    }

    @Test
    void parse_confidence_round_trips() {
        double original = 0.8234;
        String formatted = MemoryAttributeKeys.formatConfidence(original);
        assertEquals(original, MemoryAttributeKeys.parseConfidence(formatted), 0.00001);
    }

    @Test
    void key_constants_use_kebab_case() {
        assertTrue(MemoryAttributeKeys.ACTOR_ID.contains("-"));
        assertTrue(MemoryAttributeKeys.ACTOR_ROLE.contains("-"));
        assertTrue(MemoryAttributeKeys.OUTCOME.equals("outcome") || !MemoryAttributeKeys.OUTCOME.contains("_"));
        assertTrue(MemoryAttributeKeys.CONFIDENCE.equals("confidence") || !MemoryAttributeKeys.CONFIDENCE.contains("_"));
    }
}
