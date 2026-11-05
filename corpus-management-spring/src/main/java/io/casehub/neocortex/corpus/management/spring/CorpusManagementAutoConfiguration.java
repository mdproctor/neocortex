package io.casehub.neocortex.corpus.management.spring;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.corpus.management.CorpusCatalogue;
import io.casehub.neocortex.corpus.management.CorpusDescriptor;
import io.casehub.neocortex.corpus.management.CorpusEntry;
import io.casehub.neocortex.corpus.management.CorpusEntryStore;
import io.casehub.neocortex.corpus.management.CorpusLifecycle;
import io.casehub.neocortex.corpus.management.CorpusSearch;
import io.casehub.neocortex.corpus.management.CorpusType;
import io.casehub.neocortex.corpus.management.FederatedCorpusSearch;
import io.casehub.neocortex.corpus.management.FederatedSearchResult;
import io.casehub.neocortex.corpus.management.HealthReport;
import io.casehub.neocortex.corpus.management.SearchReport;
import io.casehub.neocortex.corpus.management.impl.ChainWalkerFederatedSearch;
import io.casehub.neocortex.corpus.management.impl.SqliteCorpusCatalogue;
import io.casehub.neocortex.corpus.management.impl.SqliteCorpusEntryStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@AutoConfiguration
@EnableConfigurationProperties(CorpusManagementProperties.class)
public class CorpusManagementAutoConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "casehub.corpus-management.sqlite.enabled", havingValue = "true")
    static class SqliteConfiguration {

        @Bean(destroyMethod = "close")
        HikariDataSource corpusManagementDataSource(CorpusManagementProperties props) {
            var sqlite = props.getSqlite();
            return SqliteDataSourceFactory.create(
                    sqlite.getPath(), sqlite.getMaxPoolSize(), sqlite.getBusyTimeoutMs());
        }

        @Bean
        CorpusCatalogue corpusCatalogue(HikariDataSource corpusManagementDataSource) {
            return new SqliteCorpusCatalogue(corpusManagementDataSource);
        }

        @Bean
        CorpusEntryStore corpusEntryStore(HikariDataSource corpusManagementDataSource,
                                          EmbeddingIngestor embeddingIngestor) {
            return new SqliteCorpusEntryStore(corpusManagementDataSource, embeddingIngestor);
        }

        @Bean
        FederatedCorpusSearch federatedCorpusSearch(CorpusCatalogue catalogue,
                                                    CorpusSearch corpusSearch,
                                                    CorpusManagementProperties props) {
            return new ChainWalkerFederatedSearch(
                    catalogue, corpusSearch, props.getFederation().getRelevanceThreshold());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    CorpusCatalogue noOpCorpusCatalogue() {
        return new CorpusCatalogue() {
            @Override public List<CorpusDescriptor> list(String tenantId, CorpusLifecycle status) { return List.of(); }
            @Override public Optional<CorpusDescriptor> get(CorpusRef corpus) { return Optional.empty(); }
            @Override public List<CorpusDescriptor> forType(CorpusType type, String tenantId) { return List.of(); }
            @Override public List<CorpusDescriptor> searchScope(CorpusRef corpus) { return List.of(); }
            @Override public HealthReport reconcile(String tenantId) { return HealthReport.HEALTHY; }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    CorpusEntryStore noOpCorpusEntryStore() {
        return new CorpusEntryStore() {
            @Override public String add(CorpusRef corpus, CorpusEntry entry) { return entry.id(); }
            @Override public void update(CorpusRef corpus, String entryId, CorpusEntry entry) {}
            @Override public void remove(CorpusRef corpus, String entryId) {}
            @Override public Optional<CorpusEntry> get(CorpusRef corpus, String entryId) { return Optional.empty(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    CorpusSearch noOpCorpusSearch() {
        return (corpus, query) -> List.of();
    }

    @Bean
    @ConditionalOnMissingBean
    FederatedCorpusSearch noOpFederatedCorpusSearch() {
        return (query, tenantId) ->
                new FederatedSearchResult(List.of(), new SearchReport(List.of(), List.of(), false));
    }
}
