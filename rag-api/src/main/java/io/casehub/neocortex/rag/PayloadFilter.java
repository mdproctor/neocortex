package io.casehub.neocortex.rag;

import java.util.List;
import java.util.Objects;

/**
 * Sealed filter algebra for payload-level filtering in vector search.
 *
 * <p>Implementations translate these nodes to backend-specific conditions:
 * Qdrant gRPC {@code Condition} messages, in-memory metadata matching, etc.
 * The sealed hierarchy enables exhaustive {@code switch} expressions (Java 21+).
 */
public sealed interface PayloadFilter {

    record Eq(String field, String value) implements PayloadFilter {
        public Eq {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }
    }

    record In(String field, List<String> values) implements PayloadFilter {
        public In {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record Not(PayloadFilter inner) implements PayloadFilter {
        public Not {
            Objects.requireNonNull(inner, "inner");
        }
    }

    record And(List<PayloadFilter> filters) implements PayloadFilter {
        public And {
            if (filters.isEmpty()) throw new IllegalArgumentException("And requires at least one filter");
            filters = List.copyOf(filters);
        }
    }

    record Or(List<PayloadFilter> filters) implements PayloadFilter {
        public Or {
            if (filters.isEmpty()) throw new IllegalArgumentException("Or requires at least one filter");
            filters = List.copyOf(filters);
        }
    }

    record Gte(String field, double value) implements PayloadFilter {
        public Gte {
            Objects.requireNonNull(field, "field");
        }
    }

    record Lte(String field, double value) implements PayloadFilter {
        public Lte {
            Objects.requireNonNull(field, "field");
        }
    }

    record Range(String field, double min, double max) implements PayloadFilter {
        public Range {
            Objects.requireNonNull(field, "field");
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
        }
    }

    // ── Factory methods ─────────────────────────────────────────────────

    static PayloadFilter eq(String field, String value) { return new Eq(field, value); }
    static PayloadFilter in(String field, List<String> values) { return new In(field, values); }
    static PayloadFilter not(PayloadFilter inner) { return new Not(inner); }
    static PayloadFilter and(PayloadFilter... filters) { return new And(List.of(filters)); }
    static PayloadFilter or(PayloadFilter... filters) { return new Or(List.of(filters)); }
    static PayloadFilter gte(String field, double value) { return new Gte(field, value); }
    static PayloadFilter lte(String field, double value) { return new Lte(field, value); }
    static PayloadFilter range(String field, double min, double max) { return new Range(field, min, max); }
}
