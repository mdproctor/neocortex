package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.CorpusRef;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Common.Filter;

import java.util.Optional;

public enum TenancyStrategy {

    SEPARATE_COLLECTIONS {
        @Override
        public String collectionName(CorpusRef corpus) {
            return corpus.tenantId() + "_" + corpus.corpusName();
        }

        @Override
        public Optional<Filter> tenantFilter(CorpusRef corpus) {
            return Optional.empty();
        }
    },

    SHARED_COLLECTION {
        @Override
        public String collectionName(CorpusRef corpus) {
            return corpus.corpusName();
        }

        @Override
        public Optional<Filter> tenantFilter(CorpusRef corpus) {
            return Optional.of(Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", corpus.tenantId()))
                .build());
        }
    };

    public abstract String collectionName(CorpusRef corpus);
    public abstract Optional<Filter> tenantFilter(CorpusRef corpus);
}
