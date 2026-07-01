package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.PayloadFilter;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFilterTranslatorTest {

    @Test
    void nullFilterReturnsEmpty() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(null);
        assertThat(result).isEmpty();
    }

    @Test
    void eqProducesMustConditionWithMatchKeyword() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.eq("domain", "jvm"));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("domain");
        assertThat(condition.getField().getMatch().getKeyword()).isEqualTo("jvm");
    }

    @Test
    void inProducesMatchKeywordsCondition() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.in("type", List.of("gotcha", "technique")));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("type");
        assertThat(condition.getField().getMatch().getKeywords().getStringsList())
            .containsExactly("gotcha", "technique");
    }

    @Test
    void notProducesNestedFilterWithMustNot() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.not(PayloadFilter.eq("domain", "jvm")));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        // The top-level must condition wraps a nested filter
        Condition outerCondition = filter.getMust(0);
        assertThat(outerCondition.hasFilter()).isTrue();

        // The nested filter has mustNot containing the eq condition
        Filter nestedFilter = outerCondition.getFilter();
        assertThat(nestedFilter.getMustNotCount()).isEqualTo(1);

        Condition innerCondition = nestedFilter.getMustNot(0);
        assertThat(innerCondition.hasField()).isTrue();
        assertThat(innerCondition.getField().getKey()).isEqualTo("domain");
        assertThat(innerCondition.getField().getMatch().getKeyword()).isEqualTo("jvm");
    }

    @Test
    void andProducesNestedFilterWithMultipleMustConditions() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.and(
                PayloadFilter.eq("domain", "jvm"),
                PayloadFilter.eq("type", "gotcha")));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        // The top-level must wraps a nested filter
        Condition outerCondition = filter.getMust(0);
        assertThat(outerCondition.hasFilter()).isTrue();

        // The nested filter has 2 must conditions
        Filter nestedFilter = outerCondition.getFilter();
        assertThat(nestedFilter.getMustCount()).isEqualTo(2);

        assertThat(nestedFilter.getMust(0).getField().getKey()).isEqualTo("domain");
        assertThat(nestedFilter.getMust(0).getField().getMatch().getKeyword()).isEqualTo("jvm");
        assertThat(nestedFilter.getMust(1).getField().getKey()).isEqualTo("type");
        assertThat(nestedFilter.getMust(1).getField().getMatch().getKeyword()).isEqualTo("gotcha");
    }

    @Test
    void orProducesNestedFilterWithMultipleShouldConditions() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.or(
                PayloadFilter.eq("domain", "jvm"),
                PayloadFilter.eq("domain", "python")));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        // The top-level must wraps a nested filter
        Condition outerCondition = filter.getMust(0);
        assertThat(outerCondition.hasFilter()).isTrue();

        // The nested filter has 2 should conditions
        Filter nestedFilter = outerCondition.getFilter();
        assertThat(nestedFilter.getShouldCount()).isEqualTo(2);

        assertThat(nestedFilter.getShould(0).getField().getKey()).isEqualTo("domain");
        assertThat(nestedFilter.getShould(0).getField().getMatch().getKeyword()).isEqualTo("jvm");
        assertThat(nestedFilter.getShould(1).getField().getKey()).isEqualTo("domain");
        assertThat(nestedFilter.getShould(1).getField().getMatch().getKeyword()).isEqualTo("python");
    }

    @Test
    void gteProducesRangeCondition() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.gte("score", 0.5));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("score");
        assertThat(condition.getField().hasRange()).isTrue();
        assertThat(condition.getField().getRange().hasGte()).isTrue();
        assertThat(condition.getField().getRange().getGte()).isEqualTo(0.5);
    }

    @Test
    void lteProducesRangeCondition() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.lte("score", 0.9));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("score");
        assertThat(condition.getField().hasRange()).isTrue();
        assertThat(condition.getField().getRange().hasLte()).isTrue();
        assertThat(condition.getField().getRange().getLte()).isEqualTo(0.9);
    }

    @Test
    void rangeProducesRangeConditionWithBothBounds() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.range("score", 0.1, 0.9));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("score");
        assertThat(condition.getField().hasRange()).isTrue();
        assertThat(condition.getField().getRange().hasGte()).isTrue();
        assertThat(condition.getField().getRange().getGte()).isEqualTo(0.1);
        assertThat(condition.getField().getRange().hasLte()).isTrue();
        assertThat(condition.getField().getRange().getLte()).isEqualTo(0.9);
    }

    @Test
    void rangeComposedWithEqTranslates() {
        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(
            PayloadFilter.and(
                PayloadFilter.eq("type", "game"),
                PayloadFilter.range("score", 0.5, 1.0)));

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        // Top-level And produces nested filter with 2 must conditions
        Condition andCondition = filter.getMust(0);
        assertThat(andCondition.hasFilter()).isTrue();
        Filter andFilter = andCondition.getFilter();
        assertThat(andFilter.getMustCount()).isEqualTo(2);

        // First must: Eq("type","game")
        Condition eqCondition = andFilter.getMust(0);
        assertThat(eqCondition.hasField()).isTrue();
        assertThat(eqCondition.getField().getKey()).isEqualTo("type");
        assertThat(eqCondition.getField().getMatch().getKeyword()).isEqualTo("game");

        // Second must: Range("score", 0.5, 1.0)
        Condition rangeCondition = andFilter.getMust(1);
        assertThat(rangeCondition.hasField()).isTrue();
        assertThat(rangeCondition.getField().getKey()).isEqualTo("score");
        assertThat(rangeCondition.getField().hasRange()).isTrue();
        assertThat(rangeCondition.getField().getRange().getGte()).isEqualTo(0.5);
        assertThat(rangeCondition.getField().getRange().getLte()).isEqualTo(1.0);
    }

    @Test
    void nestedAndOrNotComposition() {
        // And(Eq("domain","jvm"), Or(Eq("type","gotcha"), Not(In("lang", ["rust","go"]))))
        PayloadFilter complex = PayloadFilter.and(
            PayloadFilter.eq("domain", "jvm"),
            PayloadFilter.or(
                PayloadFilter.eq("type", "gotcha"),
                PayloadFilter.not(
                    PayloadFilter.in("lang", List.of("rust", "go")))));

        Optional<Filter> result = PayloadFilterTranslator.toQdrantFilter(complex);

        assertThat(result).isPresent();
        Filter filter = result.get();
        assertThat(filter.getMustCount()).isEqualTo(1);

        // Top-level: And produces nested filter with 2 must conditions
        Condition andCondition = filter.getMust(0);
        assertThat(andCondition.hasFilter()).isTrue();
        Filter andFilter = andCondition.getFilter();
        assertThat(andFilter.getMustCount()).isEqualTo(2);

        // First must: Eq("domain","jvm")
        Condition eqCondition = andFilter.getMust(0);
        assertThat(eqCondition.hasField()).isTrue();
        assertThat(eqCondition.getField().getKey()).isEqualTo("domain");
        assertThat(eqCondition.getField().getMatch().getKeyword()).isEqualTo("jvm");

        // Second must: Or(...) — wraps a filter with should conditions
        Condition orCondition = andFilter.getMust(1);
        assertThat(orCondition.hasFilter()).isTrue();
        Filter orFilter = orCondition.getFilter();
        assertThat(orFilter.getShouldCount()).isEqualTo(2);

        // Or first: Eq("type","gotcha")
        Condition shouldFirst = orFilter.getShould(0);
        assertThat(shouldFirst.hasField()).isTrue();
        assertThat(shouldFirst.getField().getKey()).isEqualTo("type");

        // Or second: Not(In("lang",["rust","go"])) — filter with mustNot
        Condition shouldSecond = orFilter.getShould(1);
        assertThat(shouldSecond.hasFilter()).isTrue();
        Filter notFilter = shouldSecond.getFilter();
        assertThat(notFilter.getMustNotCount()).isEqualTo(1);

        // The mustNot inner: In("lang",["rust","go"])
        Condition inCondition = notFilter.getMustNot(0);
        assertThat(inCondition.hasField()).isTrue();
        assertThat(inCondition.getField().getKey()).isEqualTo("lang");
        assertThat(inCondition.getField().getMatch().getKeywords().getStringsList())
            .containsExactly("rust", "go");
    }
}
