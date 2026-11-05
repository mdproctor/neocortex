package io.casehub.neocortex.corpus.management.cdi;

import com.zaxxer.hikari.HikariDataSource;
import io.casehub.neocortex.corpus.management.CorpusCatalogue;
import io.casehub.neocortex.corpus.management.CorpusEntryStore;
import io.casehub.neocortex.corpus.management.CorpusSearch;
import io.casehub.neocortex.corpus.management.FederatedCorpusSearch;
import io.casehub.neocortex.corpus.management.impl.ChainWalkerFederatedSearch;
import io.casehub.neocortex.corpus.management.impl.SqliteCorpusCatalogue;
import io.casehub.neocortex.corpus.management.impl.SqliteCorpusEntryStore;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.sqlite.SqliteDataSourceFactory;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
@IfBuildProperty(name = "casehub.corpus-management.sqlite.enabled", stringValue = "true", enableIfMissing = false)
public class CorpusManagementBeans {

    private volatile HikariDataSource dataSource;

    private HikariDataSource dataSource(CorpusManagementConfig config) {
        if (dataSource == null) {
            synchronized (this) {
                if (dataSource == null) {
                    var sqlite = config.sqlite();
                    String path = sqlite.path()
                            .orElseThrow(() -> new IllegalStateException(
                                    "casehub.corpus-management.sqlite.path must be set when sqlite is enabled"));
                    dataSource = SqliteDataSourceFactory.create(
                            path, sqlite.maxPoolSize(), sqlite.busyTimeoutMs());
                }
            }
        }
        return dataSource;
    }

    @Produces
    @Singleton
    public CorpusCatalogue corpusCatalogue(CorpusManagementConfig config) {
        return new SqliteCorpusCatalogue(dataSource(config));
    }

    @Produces
    @Singleton
    public CorpusEntryStore corpusEntryStore(CorpusManagementConfig config,
                                             EmbeddingIngestor embeddingIngestor) {
        return new SqliteCorpusEntryStore(dataSource(config), embeddingIngestor);
    }

    @Produces
    @Singleton
    public FederatedCorpusSearch federatedCorpusSearch(CorpusCatalogue catalogue,
                                                       CorpusSearch corpusSearch,
                                                       CorpusManagementConfig config) {
        return new ChainWalkerFederatedSearch(
                catalogue, corpusSearch, config.federation().relevanceThreshold());
    }

    @PreDestroy
    void shutdown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
