package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.corpus.ChangeSource;
import io.casehub.neocortex.corpus.CorpusReader;
import io.casehub.neocortex.corpus.zip.CompositeChangeSource;
import io.casehub.neocortex.corpus.zip.CompositeCorpusStore;
import io.casehub.neocortex.corpus.zip.CorpusConfig;
import io.casehub.neocortex.corpus.zip.FlatChangeSource;
import io.casehub.neocortex.corpus.zip.FlatCorpusStore;
import io.casehub.neocortex.corpus.zip.ZipChangeSource;
import io.casehub.neocortex.corpus.zip.ZipCorpusStore;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.MetadataExtractor;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads {@link IngestionConfig} and {@link CorpusStorageConfig} to construct
 * {@link CorpusIngestionBinding} instances for each configured corpus.
 *
 * <p>This is the only class in the {@code rag} module that depends on
 * {@code corpus} implementation types ({@link ZipCorpusStore},
 * {@link FlatCorpusStore}, {@link CompositeCorpusStore}).
 */
@ApplicationScoped
public class CorpusBindingProducer {

    private static final Logger LOG = Logger.getLogger(CorpusBindingProducer.class.getName());

    private final IngestionConfig ingestionConfig;
    private final CorpusStorageConfig storageConfig;
    private final MetadataExtractor metadataExtractor;

    private volatile List<CorpusIngestionBinding> cached;

    public CorpusBindingProducer(IngestionConfig ingestionConfig,
                                 CorpusStorageConfig storageConfig,
                                 MetadataExtractor metadataExtractor) {
        this.ingestionConfig = ingestionConfig;
        this.storageConfig = storageConfig;
        this.metadataExtractor = metadataExtractor;
    }

    /**
     * Returns the list of corpus ingestion bindings derived from configuration.
     * The result is computed once and cached for the lifetime of this bean.
     */
    public List<CorpusIngestionBinding> bindings() {
        List<CorpusIngestionBinding> result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) {
                    result = buildBindings();
                    cached = result;
                }
            }
        }
        return result;
    }

    private List<CorpusIngestionBinding> buildBindings() {
        Map<String, IngestionConfig.CorpusIngestionConfig> corpora = ingestionConfig.corpora();
        Map<String, CorpusStorageConfig.CorpusInstanceConfig> storage = storageConfig.corpora();

        List<CorpusIngestionBinding> bindings = new ArrayList<>();

        for (Map.Entry<String, IngestionConfig.CorpusIngestionConfig> entry : corpora.entrySet()) {
            String name = entry.getKey();
            IngestionConfig.CorpusIngestionConfig ingestion = entry.getValue();

            if (ingestion.mode() == IngestionMode.NONE) {
                LOG.fine(() -> "Skipping corpus '" + name + "' — ingestion mode is NONE");
                continue;
            }

            CorpusStorageConfig.CorpusInstanceConfig instance = storage.get(name);
            if (instance == null) {
                LOG.warning(() -> "Ingestion config references corpus '" + name
                        + "' but no matching casehub.corpus.corpora." + name + " storage config exists — skipping");
                continue;
            }

            CorpusIngestionBinding binding = createBinding(name, ingestion, instance);
            bindings.add(binding);
        }

        return Collections.unmodifiableList(bindings);
    }

    private CorpusIngestionBinding createBinding(String name,
                                                  IngestionConfig.CorpusIngestionConfig ingestion,
                                                  CorpusStorageConfig.CorpusInstanceConfig instance) {
        CorpusRef corpusRef = new CorpusRef(ingestion.tenantId(), ingestion.corpusName());
        Path sourcePath = Path.of(instance.source());
        String mode = instance.mode().toUpperCase();

        CorpusReader reader;
        ChangeSource changeSource;

        switch (mode) {
            case "ZIP" -> {
                CorpusConfig config = new CorpusConfig(name, sourcePath, instance.maxZipSize());
                ZipCorpusStore zipStore = new ZipCorpusStore(config);
                reader = zipStore;
                changeSource = new ZipChangeSource(zipStore);
            }
            case "COMPOSITE" -> {
                CorpusConfig config = new CorpusConfig(name, sourcePath, instance.maxZipSize());
                ZipCorpusStore zipStore = new ZipCorpusStore(config);
                FlatCorpusStore flatStore = new FlatCorpusStore(sourcePath);
                CompositeCorpusStore compositeStore = new CompositeCorpusStore(zipStore, flatStore);
                reader = compositeStore;
                changeSource = new CompositeChangeSource(zipStore, sourcePath);
            }
            default -> {
                // FLAT is the default
                FlatCorpusStore flatStore = new FlatCorpusStore(sourcePath);
                reader = flatStore;
                changeSource = new FlatChangeSource(flatStore, sourcePath);
            }
        }

        // All config-driven bindings share the injected MetadataExtractor (YamlFrontmatterExtractor @DefaultBean).
        // Per-corpus extractor selection deferred until a second document format is needed.
        // Custom bindings via Instance<CorpusIngestionBinding> can already carry per-binding extractors.
        return new CorpusIngestionBinding(name, corpusRef, changeSource, reader, metadataExtractor);
    }
}
