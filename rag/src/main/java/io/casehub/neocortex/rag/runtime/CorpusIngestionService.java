package io.casehub.neocortex.rag.runtime;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import io.casehub.neocortex.corpus.ChangedEntry;
import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.neocortex.corpus.ChangeType;
import io.casehub.neocortex.corpus.WatchableChangeSource;
import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.CursorStore;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.ExtractionResult;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CorpusIngestionService {

    private static final Logger LOG = Logger.getLogger(CorpusIngestionService.class.getName());

    private final EmbeddingIngestor ingestor;
    private final CursorStore cursorStore;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final List<WatchableChangeSource> activeWatchers = new ArrayList<>();

    @Inject CorpusBindingProducer bindingProducer;
    @Inject Instance<CorpusIngestionBinding> customBindings;
    @Inject IngestionConfig config;

    public CorpusIngestionService(EmbeddingIngestor ingestor, CursorStore cursorStore) {
        this.ingestor = ingestor;
        this.cursorStore = cursorStore;
    }

    void onStart(@Observes StartupEvent event) {
        for (CorpusIngestionBinding binding : allBindings()) {
            IngestionMode mode = modeFor(binding);
            if (mode != IngestionMode.AUTO) continue;

            processBinding(binding, splitterFor(binding.name()));

            if (binding.changeSource() instanceof WatchableChangeSource watchable) {
                try {
                    watchable.watch(entries -> onWatchEvent(binding, entries));
                    activeWatchers.add(watchable);
                    LOG.info(() -> "Started filesystem watcher for corpus '" + binding.name() + "'");
                } catch (Exception e) {
                    LOG.log(Level.WARNING,
                            "Failed to start watcher for corpus '" + binding.name()
                                    + "' — falling back to polling", e);
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        for (WatchableChangeSource watchable : activeWatchers) {
            try {
                watchable.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to close watcher", e);
            }
        }
        activeWatchers.clear();
    }

    private void onWatchEvent(CorpusIngestionBinding binding, List<ChangedEntry> entries) {
        if (entries.isEmpty()) return;

        ReentrantLock lock = locks.computeIfAbsent(binding.name(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            LOG.fine(() -> "Skipping watch event for corpus '"
                    + binding.name() + "' — already being processed");
            return;
        }
        try {
            doProcessWatchEvent(binding, entries, splitterFor(binding.name()));
        } finally {
            lock.unlock();
        }
    }

    private void doProcessWatchEvent(CorpusIngestionBinding binding,
                                     List<ChangedEntry> entries,
                                     DocumentSplitter splitter) {
        CorpusRef corpusRef = binding.corpusRef();
        boolean anyFailure = false;

        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.DELETED || entry.type() == ChangeType.MODIFIED) {
                try {
                    ingestor.deleteDocument(corpusRef, entry.path());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to delete document '" + entry.path() + "'", e);
                    anyFailure = true;
                }
            }
        }

        List<ChunkInput> allChunks = new ArrayList<>();
        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.ADDED || entry.type() == ChangeType.MODIFIED) {
                try {
                    Optional<byte[]> content = binding.corpusReader().read(entry.path());
                    if (content.isEmpty()) {
                        LOG.fine(() -> "Document '" + entry.path() + "' no longer readable — skipping");
                        continue;
                    }

                    ExtractionResult result = binding.metadataExtractor().extract(entry.path(), content.get());
                    List<ChunkInput> chunks = chunkDocument(entry.path(), result, splitter);
                    allChunks.addAll(chunks);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to process document '" + entry.path() + "'", e);
                    anyFailure = true;
                }
            }
        }

        if (!allChunks.isEmpty()) {
            try {
                ingestor.ingest(corpusRef, allChunks);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to batch ingest chunks for corpus '" + binding.name() + "'", e);
                anyFailure = true;
            }
        }

        if (!anyFailure && binding.changeSource() instanceof WatchableChangeSource watchable) {
            cursorStore.save(binding.name(), watchable.currentCursor());
        }
    }

    public void processBinding(CorpusIngestionBinding binding) {
        processBinding(binding, null);
    }

    public void processBinding(CorpusIngestionBinding binding, DocumentSplitter splitter) {
        ReentrantLock lock = locks.computeIfAbsent(binding.name(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            LOG.fine(() -> "Skipping corpus '" + binding.name() + "' — already being processed");
            return;
        }
        try {
            doProcessBinding(binding, splitter);
        } finally {
            lock.unlock();
        }
    }

    public void reconcile(String corpusName, CorpusIngestionBinding binding) {
        reconcile(corpusName, binding, null);
    }

    public void reconcile(String corpusName, CorpusIngestionBinding binding, DocumentSplitter splitter) {
        ReentrantLock lock = locks.computeIfAbsent(binding.name(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            LOG.fine(() -> "Skipping reconciliation for corpus '" + binding.name() + "' — already being processed");
            return;
        }
        try {
            doReconcile(corpusName, binding, splitter);
        } finally {
            lock.unlock();
        }
    }

    private void doProcessBinding(CorpusIngestionBinding binding, DocumentSplitter splitter) {
        Optional<String> existingCursor = cursorStore.load(binding.name());

        ChangeSet changeSet;
        if (existingCursor.isEmpty()) {
            LOG.info(() -> "No cursor for corpus '" + binding.name() + "' — bootstrapping with fullScan");
            changeSet = binding.changeSource().fullScan();
        } else {
            changeSet = binding.changeSource().changesSince(existingCursor.get());
        }

        if (changeSet.entries().isEmpty()) {
            return;
        }

        CorpusRef corpusRef = binding.corpusRef();
        boolean anyFailure = false;

        for (ChangedEntry entry : changeSet.entries()) {
            if (entry.type() == ChangeType.DELETED || entry.type() == ChangeType.MODIFIED) {
                try {
                    ingestor.deleteDocument(corpusRef, entry.path());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to delete document '" + entry.path() + "' from corpus '" + binding.name() + "'", e);
                    anyFailure = true;
                }
            }
        }

        List<ChunkInput> allChunks = new ArrayList<>();
        for (ChangedEntry entry : changeSet.entries()) {
            if (entry.type() == ChangeType.ADDED || entry.type() == ChangeType.MODIFIED) {
                try {
                    Optional<byte[]> content = binding.corpusReader().read(entry.path());
                    if (content.isEmpty()) {
                        LOG.fine(() -> "Document '" + entry.path() + "' no longer readable — skipping");
                        continue;
                    }

                    ExtractionResult result = binding.metadataExtractor().extract(entry.path(), content.get());
                    List<ChunkInput> chunks = chunkDocument(entry.path(), result, splitter);
                    allChunks.addAll(chunks);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to process document '" + entry.path() + "' in corpus '" + binding.name() + "'", e);
                    anyFailure = true;
                }
            }
        }

        if (!allChunks.isEmpty()) {
            try {
                ingestor.ingest(corpusRef, allChunks);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to batch ingest chunks for corpus '" + binding.name() + "'", e);
                anyFailure = true;
            }
        }

        if (!anyFailure) {
            cursorStore.save(binding.name(), changeSet.newCursor());
        } else {
            LOG.warning(() -> "Cursor NOT advanced for corpus '" + binding.name() + "' due to processing failures — will retry next poll");
        }
    }

    private void doReconcile(String corpusName, CorpusIngestionBinding binding, DocumentSplitter splitter) {
        CorpusRef corpusRef = binding.corpusRef();

        ChangeSet fullScan = binding.changeSource().fullScan();

        List<String> qdrantDocs = ingestor.listDocuments(corpusRef);

        Set<String> corpusPaths = new HashSet<>();
        for (ChangedEntry entry : fullScan.entries()) {
            corpusPaths.add(entry.path());
        }
        Set<String> qdrantPaths = new HashSet<>(qdrantDocs);

        for (String path : corpusPaths) {
            if (!qdrantPaths.contains(path)) {
                try {
                    Optional<byte[]> content = binding.corpusReader().read(path);
                    if (content.isEmpty()) {
                        LOG.fine(() -> "Document '" + path + "' no longer readable during reconciliation — skipping");
                        continue;
                    }

                    ExtractionResult result = binding.metadataExtractor().extract(path, content.get());
                    List<ChunkInput> chunks = chunkDocument(path, result, splitter);
                    if (!chunks.isEmpty()) {
                        ingestor.ingest(corpusRef, chunks);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to reingest document '" + path + "' during reconciliation", e);
                }
            }
        }

        for (String path : qdrantPaths) {
            if (!corpusPaths.contains(path)) {
                try {
                    ingestor.deleteDocument(corpusRef, path);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to delete stale document '" + path + "' during reconciliation", e);
                }
            }
        }

        cursorStore.save(corpusName, fullScan.newCursor());
    }

    private List<ChunkInput> chunkDocument(String path, ExtractionResult result, DocumentSplitter splitter) {
        if (result.body().isBlank()) {
            return List.of();
        }
        if (splitter == null) {
            return List.of(new ChunkInput(result.body(), path, result.metadata()));
        }
        Document doc = Document.from(result.body());
        List<TextSegment> segments = splitter.split(doc);
        List<ChunkInput> chunks = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            if (!segment.text().isBlank()) {
                chunks.add(new ChunkInput(segment.text(), path, result.metadata()));
            }
        }
        return chunks;
    }

    // --- Scheduling and convenience methods ---

    @Scheduled(every = "${casehub.rag.ingestion.interval:30s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        for (CorpusIngestionBinding binding : allBindings()) {
            IngestionMode mode = modeFor(binding);
            if (mode == IngestionMode.AUTO) {
                if (binding.changeSource() instanceof WatchableChangeSource) {
                    continue;
                }
                processBinding(binding, splitterFor(binding.name()));
            }
        }
    }

    @Scheduled(every = "${casehub.rag.ingestion.cursor-checkpoint-interval:5m}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkpointCursors() {
        for (CorpusIngestionBinding binding : allBindings()) {
            if (binding.changeSource() instanceof WatchableChangeSource watchable) {
                try {
                    String cursor = watchable.currentCursor();
                    cursorStore.save(binding.name(), cursor);
                } catch (Exception e) {
                    LOG.log(Level.WARNING,
                            "Failed to checkpoint cursor for corpus '" + binding.name() + "'", e);
                }
            }
        }
    }

    public void triggerManual(String corpusName) {
        for (CorpusIngestionBinding binding : allBindings()) {
            if (binding.name().equals(corpusName)) {
                processBinding(binding, splitterFor(binding.name()));
                return;
            }
        }
        LOG.warning(() -> "No binding found for corpus: " + corpusName);
    }

    public void reconcile(String corpusName) {
        for (CorpusIngestionBinding binding : allBindings()) {
            if (binding.name().equals(corpusName)) {
                reconcile(corpusName, binding, splitterFor(binding.name()));
                return;
            }
        }
        LOG.warning(() -> "No binding found for corpus: " + corpusName);
    }

    public void reconcileAll() {
        for (CorpusIngestionBinding binding : allBindings()) {
            reconcile(binding.name(), binding, splitterFor(binding.name()));
        }
    }

    private List<CorpusIngestionBinding> allBindings() {
        List<CorpusIngestionBinding> all = new ArrayList<>();
        if (bindingProducer != null) {
            all.addAll(bindingProducer.bindings());
        }
        if (customBindings != null) {
            customBindings.forEach(all::add);
        }
        return all;
    }

    private IngestionMode modeFor(CorpusIngestionBinding binding) {
        if (config == null) return IngestionMode.AUTO;
        var corpusConfig = config.corpora().get(binding.name());
        if (corpusConfig == null) return IngestionMode.AUTO;
        return corpusConfig.mode();
    }

    private DocumentSplitter splitterFor(String corpusName) {
        if (config == null) return null;
        var corpusConfig = config.corpora().get(corpusName);
        if (corpusConfig == null || "none".equalsIgnoreCase(corpusConfig.chunking())) {
            return null;
        }
        if ("recursive".equalsIgnoreCase(corpusConfig.chunking())) {
            int maxSize = corpusConfig.chunkingMaxSize().orElse(1000);
            int overlap = corpusConfig.chunkingOverlapSize().orElse(200);
            return DocumentSplitters.recursive(maxSize, overlap);
        }
        LOG.warning(() -> "Unknown chunking strategy: " + corpusConfig.chunking() + " — defaulting to none");
        return null;
    }
}
