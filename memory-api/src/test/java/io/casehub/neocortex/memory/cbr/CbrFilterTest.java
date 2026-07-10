package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrFilterTest {

    @Test
    void contains_validValue() {
        var f = CbrFilter.contains("EARLY_AGGRESSION");
        assertThat(f).isInstanceOf(CbrFilter.Contains.class);
        assertThat(f.value()).isEqualTo("EARLY_AGGRESSION");
    }

    @Test
    void contains_nullRejected() {
        assertThatThrownBy(() -> CbrFilter.contains(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void containsAll_validValues() {
        var f = CbrFilter.containsAll(List.of("A", "B"));
        assertThat(f.values()).containsExactly("A", "B");
    }

    @Test
    void containsAll_emptyRejected() {
        assertThatThrownBy(() -> CbrFilter.containsAll(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void containsAll_defensivelyCopied() {
        var list = new java.util.ArrayList<>(List.of("A"));
        var f = CbrFilter.containsAll(list);
        list.add("B");
        assertThat(f.values()).hasSize(1);
    }

    @Test
    void containsAny_validValues() {
        var f = CbrFilter.containsAny(List.of("X", "Y"));
        assertThat(f.values()).containsExactly("X", "Y");
    }

    @Test
    void containsAny_emptyRejected() {
        assertThatThrownBy(() -> CbrFilter.containsAny(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasMatch_validSubFields() {
        var f = CbrFilter.hasMatch(Map.of("type", "FIRST_CONTACT", "minute", 3.2));
        assertThat(f.subFields()).hasSize(2);
    }

    @Test
    void hasMatch_emptySubFieldsRejected() {
        assertThatThrownBy(() -> CbrFilter.hasMatch(Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void hasMatch_invalidSubFieldValueTypeRejected() {
        assertThatThrownBy(() -> CbrFilter.hasMatch(Map.of("bad", List.of("x"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be String, Number, or NumericRange");
    }

    @Test
    void hasMatch_acceptsNumericRange() {
        var f = CbrFilter.hasMatch(Map.of("score", NumericRange.of(80, 90)));
        assertThat(f.subFields().get("score")).isInstanceOf(NumericRange.class);
    }

    @Test
    void hasMatch_defensivelyCopied() {
        var map = new java.util.HashMap<String, Object>();
        map.put("type", "X");
        var f = CbrFilter.hasMatch(map);
        map.put("extra", "Y");
        assertThat(f.subFields()).hasSize(1);
    }
}
