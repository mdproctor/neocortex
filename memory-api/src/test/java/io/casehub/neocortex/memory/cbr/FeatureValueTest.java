package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureValueTest {

    @Test void stringVal_requiresNonNull() {
        assertThatThrownBy(() -> FeatureValue.string(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void stringVal_preservesValue() {
        var sv = FeatureValue.string("hello");
        assertThat(sv).isInstanceOf(FeatureValue.StringVal.class);
        assertThat(((FeatureValue.StringVal) sv).value()).isEqualTo("hello");
    }

    @Test void numberVal_preservesValue() {
        var nv = FeatureValue.number(42.5);
        assertThat(nv).isInstanceOf(FeatureValue.NumberVal.class);
        assertThat(((FeatureValue.NumberVal) nv).value()).isEqualTo(42.5);
    }

    @Test void rangeVal_validRange() {
        var rv = FeatureValue.range(1.0, 10.0);
        assertThat(rv).isInstanceOf(FeatureValue.RangeVal.class);
        assertThat(((FeatureValue.RangeVal) rv).min()).isEqualTo(1.0);
        assertThat(((FeatureValue.RangeVal) rv).max()).isEqualTo(10.0);
    }

    @Test void rangeVal_minGreaterThanMax_throws() {
        assertThatThrownBy(() -> FeatureValue.range(10.0, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rangeVal_equalMinMax_allowed() {
        var rv = FeatureValue.range(5.0, 5.0);
        assertThat(((FeatureValue.RangeVal) rv).min()).isEqualTo(5.0);
    }

    @Test void stringListVal_immutable() {
        var list = new java.util.ArrayList<>(List.of("a", "b"));
        var sl = FeatureValue.stringList(list);
        list.add("c");
        assertThat(((FeatureValue.StringListVal) sl).values()).containsExactly("a", "b");
    }

    @Test void stringListVal_varargs() {
        var sl = FeatureValue.stringList("x", "y", "z");
        assertThat(((FeatureValue.StringListVal) sl).values()).containsExactly("x", "y", "z");
    }

    @Test void numberListVal_immutable() {
        var list = new java.util.ArrayList<>(List.of(1.0, 2.0));
        var nl = FeatureValue.numberList(list);
        list.add(3.0);
        assertThat(((FeatureValue.NumberListVal) nl).values()).containsExactly(1.0, 2.0);
    }

    @Test void numberListVal_varargs() {
        var nl = FeatureValue.numberList(1.0, 2.0, 3.0);
        assertThat(((FeatureValue.NumberListVal) nl).values()).containsExactly(1.0, 2.0, 3.0);
    }

    @Test void structVal_immutable() {
        var map = new java.util.HashMap<String, FeatureValue>();
        map.put("k", FeatureValue.string("v"));
        var sv = FeatureValue.struct(map);
        map.put("k2", FeatureValue.number(1));
        assertThat(((FeatureValue.StructVal) sv).fields()).hasSize(1);
    }

    @Test void structListVal_deepImmutable() {
        var inner = new java.util.HashMap<String, FeatureValue>();
        inner.put("k", FeatureValue.number(1));
        var items = new java.util.ArrayList<Map<String, FeatureValue>>();
        items.add(inner);
        var sl = FeatureValue.structList(items);
        items.add(Map.of());
        assertThat(((FeatureValue.StructListVal) sl).items()).hasSize(1);
    }

    @Test void structListVal_varargs() {
        @SuppressWarnings("unchecked")
        var sl = FeatureValue.structList(
            Map.of("a", FeatureValue.number(1)),
            Map.of("b", FeatureValue.number(2))
        );
        assertThat(((FeatureValue.StructListVal) sl).items()).hasSize(2);
    }

    @Test void stringListVal_requiresNonNull() {
        assertThatThrownBy(() -> new FeatureValue.StringListVal(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void numberListVal_requiresNonNull() {
        assertThatThrownBy(() -> new FeatureValue.NumberListVal(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void structVal_requiresNonNull() {
        assertThatThrownBy(() -> new FeatureValue.StructVal(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void structListVal_requiresNonNull() {
        assertThatThrownBy(() -> new FeatureValue.StructListVal(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void equality_sameValues() {
        assertThat(FeatureValue.string("a")).isEqualTo(FeatureValue.string("a"));
        assertThat(FeatureValue.number(1.0)).isEqualTo(FeatureValue.number(1.0));
        assertThat(FeatureValue.range(1.0, 5.0)).isEqualTo(FeatureValue.range(1.0, 5.0));
        assertThat(FeatureValue.stringList("x")).isEqualTo(FeatureValue.stringList("x"));
        assertThat(FeatureValue.numberList(1.0)).isEqualTo(FeatureValue.numberList(1.0));
    }

    @Test void equality_differentValues() {
        assertThat(FeatureValue.string("a")).isNotEqualTo(FeatureValue.string("b"));
        assertThat(FeatureValue.number(1.0)).isNotEqualTo(FeatureValue.number(2.0));
    }

    @Test void equality_differentTypes() {
        assertThat(FeatureValue.string("1")).isNotEqualTo(FeatureValue.number(1.0));
    }

    @Test void patternMatching_exhaustive() {
        FeatureValue v = FeatureValue.string("test");
        String result = switch (v) {
            case FeatureValue.StringVal s -> "string:" + s.value();
            case FeatureValue.NumberVal n -> "number:" + n.value();
            case FeatureValue.RangeVal r -> "range";
            case FeatureValue.StringListVal sl -> "stringList";
            case FeatureValue.NumberListVal nl -> "numberList";
            case FeatureValue.StructVal sv -> "struct";
            case FeatureValue.StructListVal sl -> "structList";
        };
        assertThat(result).isEqualTo("string:test");
    }

    @Test
    void of_boolean_true_mapsToStringVal() {
        var result = FeatureValue.of(true);
        assertThat(result).isEqualTo(FeatureValue.string("true"));
    }

    @Test
    void of_boolean_false_mapsToStringVal() {
        var result = FeatureValue.of(false);
        assertThat(result).isEqualTo(FeatureValue.string("false"));
    }

    @Test
    void of_boxedBoolean_mapsToStringVal() {
        Boolean boxed  = Boolean.TRUE;
        var     result = FeatureValue.of(boxed);
        assertThat(result).isEqualTo(FeatureValue.string("true"));
    }

    @Test
    void toFeatureMap_withBoolean_mapsToStringVal() {
        var raw    = Map.<String, Object>of("active", true, "name", "test");
        var result = FeatureValue.toFeatureMap(raw);
        assertThat(result.get("active")).isEqualTo(FeatureValue.string("true"));
        assertThat(result.get("name")).isEqualTo(FeatureValue.string("test"));
    }

    @Test
    void of_booleanList_mapsToStringListVal() {
        var result = FeatureValue.of(List.of(true, false, true));
        assertThat(result).isEqualTo(FeatureValue.stringList("true", "false", "true"));
    }
}
