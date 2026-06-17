package io.casehub.rag.runtime;

import io.casehub.rag.PayloadFilter;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;

import java.util.Optional;

/**
 * Translates {@link PayloadFilter} sealed algebra to Qdrant gRPC
 * {@link Filter}/{@link Condition} objects.
 *
 * <p>Package-private — consumers interact with the filter through
 * {@link HybridCaseRetriever} and {@link ReactiveHybridCaseRetriever}.
 */
final class PayloadFilterTranslator {

    private PayloadFilterTranslator() {}

    /**
     * Translate a {@link PayloadFilter} to a Qdrant {@link Filter}.
     *
     * @param filter the payload filter, or {@code null}
     * @return the Qdrant filter wrapped in {@link Optional}, or empty if {@code null}
     */
    static Optional<Filter> toQdrantFilter(PayloadFilter filter) {
        if (filter == null) {
            return Optional.empty();
        }
        return Optional.of(Filter.newBuilder()
            .addMust(toCondition(filter))
            .build());
    }

    private static Condition toCondition(PayloadFilter filter) {
        return switch (filter) {
            case PayloadFilter.Eq eq ->
                ConditionFactory.matchKeyword(eq.field(), eq.value());
            case PayloadFilter.In in ->
                ConditionFactory.matchKeywords(in.field(), in.values());
            case PayloadFilter.Not not ->
                ConditionFactory.filter(
                    Filter.newBuilder().addMustNot(toCondition(not.inner())).build());
            case PayloadFilter.And and -> {
                Filter.Builder nested = Filter.newBuilder();
                for (PayloadFilter f : and.filters()) {
                    nested.addMust(toCondition(f));
                }
                yield ConditionFactory.filter(nested.build());
            }
            case PayloadFilter.Or or -> {
                Filter.Builder nested = Filter.newBuilder();
                for (PayloadFilter f : or.filters()) {
                    nested.addShould(toCondition(f));
                }
                yield ConditionFactory.filter(nested.build());
            }
        };
    }
}
