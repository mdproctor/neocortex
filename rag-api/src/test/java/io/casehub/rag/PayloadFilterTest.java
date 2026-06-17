package io.casehub.rag;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFilterTest {

    // ── Eq ───────────────────────────────────────────────────────────────

    @Nested
    class EqTests {

        @Test
        void validConstruction() {
            var eq = new PayloadFilter.Eq("status", "active");
            assertThat(eq.field()).isEqualTo("status");
            assertThat(eq.value()).isEqualTo("active");
        }

        @Test
        void nullFieldThrowsNPE() {
            assertThatThrownBy(() -> new PayloadFilter.Eq(null, "active"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("field");
        }

        @Test
        void nullValueThrowsNPE() {
            assertThatThrownBy(() -> new PayloadFilter.Eq("status", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
        }

        @Test
        void valueBasedEquality() {
            var a = new PayloadFilter.Eq("f", "v");
            var b = new PayloadFilter.Eq("f", "v");
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }
    }

    // ── In ───────────────────────────────────────────────────────────────

    @Nested
    class InTests {

        @Test
        void validConstruction() {
            var in = new PayloadFilter.In("status", List.of("a", "b"));
            assertThat(in.field()).isEqualTo("status");
            assertThat(in.values()).containsExactly("a", "b");
        }

        @Test
        void nullFieldThrowsNPE() {
            assertThatThrownBy(() -> new PayloadFilter.In(null, List.of("a")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("field");
        }

        @Test
        void nullValuesThrowsNPE() {
            assertThatThrownBy(() -> new PayloadFilter.In("status", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("values");
        }

        @Test
        void defensiveCopyPreventsExternalMutation() {
            var mutable = new ArrayList<>(List.of("a", "b"));
            var in = new PayloadFilter.In("status", mutable);
            mutable.add("c");
            assertThat(in.values()).containsExactly("a", "b");
        }

        @Test
        void valuesAreUnmodifiable() {
            var in = new PayloadFilter.In("status", List.of("a"));
            assertThatThrownBy(() -> in.values().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Not ──────────────────────────────────────────────────────────────

    @Nested
    class NotTests {

        @Test
        void validConstruction() {
            var inner = new PayloadFilter.Eq("f", "v");
            var not = new PayloadFilter.Not(inner);
            assertThat(not.inner()).isEqualTo(inner);
        }

        @Test
        void nullInnerThrowsNPE() {
            assertThatThrownBy(() -> new PayloadFilter.Not(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inner");
        }
    }

    // ── And ──────────────────────────────────────────────────────────────

    @Nested
    class AndTests {

        @Test
        void validConstruction() {
            var f1 = new PayloadFilter.Eq("a", "1");
            var f2 = new PayloadFilter.Eq("b", "2");
            var and = new PayloadFilter.And(List.of(f1, f2));
            assertThat(and.filters()).containsExactly(f1, f2);
        }

        @Test
        void emptyListThrowsIAE() {
            assertThatThrownBy(() -> new PayloadFilter.And(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one filter");
        }

        @Test
        void defensiveCopyPreventsExternalMutation() {
            var f1 = new PayloadFilter.Eq("a", "1");
            var mutable = new ArrayList<PayloadFilter>(List.of(f1));
            var and = new PayloadFilter.And(mutable);
            mutable.add(new PayloadFilter.Eq("b", "2"));
            assertThat(and.filters()).containsExactly(f1);
        }

        @Test
        void filtersAreUnmodifiable() {
            var and = new PayloadFilter.And(List.of(new PayloadFilter.Eq("a", "1")));
            assertThatThrownBy(() -> and.filters().add(new PayloadFilter.Eq("b", "2")))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Or ───────────────────────────────────────────────────────────────

    @Nested
    class OrTests {

        @Test
        void validConstruction() {
            var f1 = new PayloadFilter.Eq("a", "1");
            var f2 = new PayloadFilter.Eq("b", "2");
            var or = new PayloadFilter.Or(List.of(f1, f2));
            assertThat(or.filters()).containsExactly(f1, f2);
        }

        @Test
        void emptyListThrowsIAE() {
            assertThatThrownBy(() -> new PayloadFilter.Or(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one filter");
        }

        @Test
        void defensiveCopyPreventsExternalMutation() {
            var f1 = new PayloadFilter.Eq("a", "1");
            var mutable = new ArrayList<PayloadFilter>(List.of(f1));
            var or = new PayloadFilter.Or(mutable);
            mutable.add(new PayloadFilter.Eq("b", "2"));
            assertThat(or.filters()).containsExactly(f1);
        }

        @Test
        void filtersAreUnmodifiable() {
            var or = new PayloadFilter.Or(List.of(new PayloadFilter.Eq("a", "1")));
            assertThatThrownBy(() -> or.filters().add(new PayloadFilter.Eq("b", "2")))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Factory methods ─────────────────────────────────────────────────

    @Nested
    class FactoryMethodTests {

        @Test
        void eqFactory() {
            var filter = PayloadFilter.eq("field", "value");
            assertThat(filter).isInstanceOf(PayloadFilter.Eq.class);
            assertThat(((PayloadFilter.Eq) filter).field()).isEqualTo("field");
            assertThat(((PayloadFilter.Eq) filter).value()).isEqualTo("value");
        }

        @Test
        void inFactory() {
            var filter = PayloadFilter.in("field", List.of("a", "b"));
            assertThat(filter).isInstanceOf(PayloadFilter.In.class);
            assertThat(((PayloadFilter.In) filter).values()).containsExactly("a", "b");
        }

        @Test
        void notFactory() {
            var inner = PayloadFilter.eq("f", "v");
            var filter = PayloadFilter.not(inner);
            assertThat(filter).isInstanceOf(PayloadFilter.Not.class);
            assertThat(((PayloadFilter.Not) filter).inner()).isEqualTo(inner);
        }

        @Test
        void andFactory() {
            var f1 = PayloadFilter.eq("a", "1");
            var f2 = PayloadFilter.eq("b", "2");
            var filter = PayloadFilter.and(f1, f2);
            assertThat(filter).isInstanceOf(PayloadFilter.And.class);
            assertThat(((PayloadFilter.And) filter).filters()).containsExactly(f1, f2);
        }

        @Test
        void orFactory() {
            var f1 = PayloadFilter.eq("a", "1");
            var f2 = PayloadFilter.eq("b", "2");
            var filter = PayloadFilter.or(f1, f2);
            assertThat(filter).isInstanceOf(PayloadFilter.Or.class);
            assertThat(((PayloadFilter.Or) filter).filters()).containsExactly(f1, f2);
        }
    }

    // ── Nested composition ──────────────────────────────────────────────

    @Nested
    class CompositionTests {

        @Test
        void andWithEqAndNotIn() {
            var eq = PayloadFilter.eq("status", "active");
            var notIn = PayloadFilter.not(PayloadFilter.in("role", List.of("admin", "superadmin")));
            var filter = PayloadFilter.and(eq, notIn);

            assertThat(filter).isInstanceOf(PayloadFilter.And.class);
            var and = (PayloadFilter.And) filter;
            assertThat(and.filters()).hasSize(2);
            assertThat(and.filters().get(0)).isEqualTo(eq);

            var not = (PayloadFilter.Not) and.filters().get(1);
            var inner = (PayloadFilter.In) not.inner();
            assertThat(inner.field()).isEqualTo("role");
            assertThat(inner.values()).containsExactly("admin", "superadmin");
        }

        @Test
        void orWithEqAndNestedAnd() {
            var eq = PayloadFilter.eq("priority", "high");
            var nested = PayloadFilter.and(
                PayloadFilter.eq("status", "open"),
                PayloadFilter.eq("assignee", "bob")
            );
            var filter = PayloadFilter.or(eq, nested);

            assertThat(filter).isInstanceOf(PayloadFilter.Or.class);
            var or = (PayloadFilter.Or) filter;
            assertThat(or.filters()).hasSize(2);
            assertThat(or.filters().get(0)).isEqualTo(eq);

            var and = (PayloadFilter.And) or.filters().get(1);
            assertThat(and.filters()).hasSize(2);
            assertThat(((PayloadFilter.Eq) and.filters().get(0)).field()).isEqualTo("status");
            assertThat(((PayloadFilter.Eq) and.filters().get(1)).field()).isEqualTo("assignee");
        }

        @Test
        void patternMatchingViaSealedInterface() {
            PayloadFilter filter = PayloadFilter.eq("f", "v");

            // Sealed interface enables exhaustive switch (Java 21+)
            String result = switch (filter) {
                case PayloadFilter.Eq eq -> "eq:" + eq.field();
                case PayloadFilter.In in -> "in:" + in.field();
                case PayloadFilter.Not not -> "not";
                case PayloadFilter.And and -> "and:" + and.filters().size();
                case PayloadFilter.Or or -> "or:" + or.filters().size();
            };
            assertThat(result).isEqualTo("eq:f");
        }
    }
}
