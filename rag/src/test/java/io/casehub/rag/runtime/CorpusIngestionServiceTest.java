package io.casehub.rag.runtime;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import io.casehub.corpus.ChangeListener;
import io.casehub.corpus.ChangedEntry;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeSource;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.CorpusReader;
import io.casehub.corpus.WatchableChangeSource;
import io.casehub.corpus.VersionInfo;
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.ExtractionResult;
import io.casehub.rag.MetadataExtractor;
import io.casehub.rag.testing.InMemoryCursorStore;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusIngestionServiceTest {

    private static final CorpusRef CORPUS = new CorpusRef("default", "garden");

    private InMemoryEmbeddingIngestor ingestor;
    private InMemoryCursorStore cursorStore;

    @BeforeEach
    void setUp() {
        ingestor = new InMemoryEmbeddingIngestor();
        cursorStore = new InMemoryCursorStore();
    }

    // --- Test 1: addedEntriesAreIngested ---

    @Test
    void addedEntriesAreIngested() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/hello.md", ChangeType.ADDED)),
                "cursor-1"
        );
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/hello.md", "Hello world"))
        );

        service().processBinding(binding);

        assertThat(ingestor.getChunks(CORPUS)).hasSize(1);
        assertThat(ingestor.getChunks(CORPUS).getFirst().content()).isEqualTo("Hello world");
        assertThat(ingestor.getChunks(CORPUS).getFirst().sourceDocumentId()).isEqualTo("docs/hello.md");
        assertThat(cursorStore.load("garden")).contains("cursor-1");
    }

    // --- Test 2: deletedEntriesRemoveFromQdrant ---

    @Test
    void deletedEntriesRemoveFromQdrant() {
        // pre-populate Qdrant
        ingestor.ingest(CORPUS, List.of(new ChunkInput("old content", "docs/gone.md", Map.of())));
        assertThat(ingestor.listDocuments(CORPUS)).containsExactly("docs/gone.md");

        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/gone.md", ChangeType.DELETED)),
                "cursor-2"
        );
        var binding = binding(fixedSource(changes), stubReader(Map.of()));

        service().processBinding(binding);

        assertThat(ingestor.listDocuments(CORPUS)).isEmpty();
        assertThat(cursorStore.load("garden")).contains("cursor-2");
    }

    // --- Test 3: modifiedEntriesDeleteThenReingest ---

    @Test
    void modifiedEntriesDeleteThenReingest() {
        // pre-populate with old version
        ingestor.ingest(CORPUS, List.of(new ChunkInput("old content", "docs/updated.md", Map.of())));

        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/updated.md", ChangeType.MODIFIED)),
                "cursor-3"
        );
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/updated.md", "new content"))
        );

        service().processBinding(binding);

        assertThat(ingestor.listDocuments(CORPUS)).containsExactly("docs/updated.md");
        assertThat(ingestor.getChunks(CORPUS)).hasSize(1);
        assertThat(ingestor.getChunks(CORPUS).getFirst().content()).isEqualTo("new content");
        assertThat(cursorStore.load("garden")).contains("cursor-3");
    }

    // --- Test 4: bootstrapCallsFullScanWhenNoCursor ---

    @Test
    void bootstrapCallsFullScanWhenNoCursor() {
        var fullScanChanges = new ChangeSet(
                List.of(new ChangedEntry("docs/a.md", ChangeType.ADDED)),
                "cursor-full"
        );
        AtomicBoolean changesSinceCalled = new AtomicBoolean(false);

        var changeSource = new ChangeSource() {
            @Override
            public ChangeSet changesSince(String cursor) {
                changesSinceCalled.set(true);
                return new ChangeSet(List.of(), "should-not-be-used");
            }

            @Override
            public ChangeSet fullScan() {
                return fullScanChanges;
            }
        };

        var binding = binding(changeSource, stubReader(Map.of("docs/a.md", "content")));

        service().processBinding(binding);

        assertThat(changesSinceCalled.get()).isFalse();
        assertThat(ingestor.getChunks(CORPUS)).hasSize(1);
        assertThat(cursorStore.load("garden")).contains("cursor-full");
    }

    // --- Test 5: existingCursorUsesChangesSince ---

    @Test
    void existingCursorUsesChangesSince() {
        cursorStore.save("garden", "existing-cursor");

        AtomicBoolean fullScanCalled = new AtomicBoolean(false);
        var expectedChanges = new ChangeSet(
                List.of(new ChangedEntry("docs/new.md", ChangeType.ADDED)),
                "cursor-delta"
        );

        var changeSource = new ChangeSource() {
            String receivedCursor;

            @Override
            public ChangeSet changesSince(String cursor) {
                receivedCursor = cursor;
                return expectedChanges;
            }

            @Override
            public ChangeSet fullScan() {
                fullScanCalled.set(true);
                return new ChangeSet(List.of(), "unused");
            }
        };

        var binding = binding(changeSource, stubReader(Map.of("docs/new.md", "delta content")));

        service().processBinding(binding);

        assertThat(fullScanCalled.get()).isFalse();
        assertThat(changeSource.receivedCursor).isEqualTo("existing-cursor");
        assertThat(cursorStore.load("garden")).contains("cursor-delta");
    }

    // --- Test 6: emptyReadSkipsEntryButCursorAdvances ---

    @Test
    void emptyReadSkipsEntryButCursorAdvances() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/vanished.md", ChangeType.ADDED)),
                "cursor-skip"
        );
        // reader returns empty for the path
        var binding = binding(fixedSource(changes), stubReader(Map.of()));

        service().processBinding(binding);

        assertThat(ingestor.getChunks(CORPUS)).isEmpty();
        assertThat(cursorStore.load("garden")).contains("cursor-skip");
    }

    // --- Test 7: extractorErrorBlocksCursor ---

    @Test
    void extractorErrorBlocksCursor() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/bad.md", ChangeType.ADDED)),
                "cursor-blocked"
        );
        MetadataExtractor failingExtractor = (path, content) -> {
            throw new RuntimeException("extractor failure");
        };
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/bad.md", "some content")),
                failingExtractor
        );

        service().processBinding(binding);

        assertThat(cursorStore.load("garden")).isEmpty();
    }

    // --- Test 8: metadataFromExtractorAppearsInChunks ---

    @Test
    void metadataFromExtractorAppearsInChunks() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/meta.md", ChangeType.ADDED)),
                "cursor-meta"
        );
        MetadataExtractor enrichExtractor = (path, content) ->
                new ExtractionResult(
                        new String(content, StandardCharsets.UTF_8),
                        Map.of("author", "alice", "category", "guide")
                );
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/meta.md", "Rich content")),
                enrichExtractor
        );

        service().processBinding(binding);

        assertThat(ingestor.getChunks(CORPUS)).hasSize(1);
        ChunkInput chunk = ingestor.getChunks(CORPUS).getFirst();
        assertThat(chunk.metadata()).containsEntry("author", "alice");
        assertThat(chunk.metadata()).containsEntry("category", "guide");
    }

    // --- Test 9: recursiveChunkingSplitsBody ---

    @Test
    void recursiveChunkingSplitsBody() {
        // Build a string longer than 200 chars to force splitting
        String longBody = "A".repeat(500);
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/long.md", ChangeType.ADDED)),
                "cursor-chunked"
        );
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/long.md", longBody))
        );

        service().processBinding(binding, DocumentSplitters.recursive(200, 20));

        List<ChunkInput> chunks = ingestor.getChunks(CORPUS);
        assertThat(chunks.size()).isGreaterThan(1);
        // all chunks share the same sourceDocumentId
        for (ChunkInput chunk : chunks) {
            assertThat(chunk.sourceDocumentId()).isEqualTo("docs/long.md");
        }
    }

    // --- Test 10: reconcileReingests_missingFromQdrant ---

    @Test
    void reconcileReingests_missingFromQdrant() {
        // corpus has a doc but Qdrant does not
        var fullScan = new ChangeSet(
                List.of(new ChangedEntry("docs/missing.md", ChangeType.ADDED)),
                "cursor-reconcile"
        );
        var binding = binding(
                fixedSource(fullScan),
                stubReader(Map.of("docs/missing.md", "Reingested content"))
        );

        service().reconcile("garden", binding);

        assertThat(ingestor.listDocuments(CORPUS)).containsExactly("docs/missing.md");
        assertThat(ingestor.getChunks(CORPUS).getFirst().content()).isEqualTo("Reingested content");
        assertThat(cursorStore.load("garden")).contains("cursor-reconcile");
    }

    // --- Test 11: reconcileDeletes_extraFromQdrant ---

    @Test
    void reconcileDeletes_extraFromQdrant() {
        // Qdrant has a doc but corpus does not
        ingestor.ingest(CORPUS, List.of(new ChunkInput("stale", "docs/stale.md", Map.of())));

        // fullScan returns no entries — corpus is empty
        var fullScan = new ChangeSet(List.of(), "cursor-clean");
        var binding = binding(fixedSource(fullScan), stubReader(Map.of()));

        service().reconcile("garden", binding);

        assertThat(ingestor.listDocuments(CORPUS)).isEmpty();
        assertThat(cursorStore.load("garden")).contains("cursor-clean");
    }

    // --- Test 12: reconcileIsBestEffort_cursorAdvancesOnError ---

    @Test
    void reconcileIsBestEffort_cursorAdvancesOnError() {
        var fullScan = new ChangeSet(
                List.of(new ChangedEntry("docs/fail.md", ChangeType.ADDED)),
                "cursor-best-effort"
        );
        MetadataExtractor failingExtractor = (path, content) -> {
            throw new RuntimeException("extraction failed during reconcile");
        };
        var binding = binding(
                fixedSource(fullScan),
                stubReader(Map.of("docs/fail.md", "some content")),
                failingExtractor
        );

        service().reconcile("garden", binding);

        // cursor STILL advances (best-effort)
        assertThat(cursorStore.load("garden")).contains("cursor-best-effort");
    }

    // --- Test 13: blankBodySkipsIngestion ---

    @Test
    void blankBodySkipsIngestion() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/blank.md", ChangeType.ADDED)),
                "cursor-blank"
        );
        MetadataExtractor blankExtractor = (path, content) ->
                new ExtractionResult("", Map.of("metadata", "only"));
        var binding = binding(
                fixedSource(changes),
                stubReader(Map.of("docs/blank.md", "")),
                blankExtractor
        );

        service().processBinding(binding);

        assertThat(ingestor.getChunks(CORPUS)).isEmpty();
        assertThat(cursorStore.load("garden")).contains("cursor-blank");
    }

    // --- Test 14: watchableSourceWorksWithPullBasedProcessing ---

    @Test
    void watchableSourceWorksWithPullBasedProcessing() {
        var changes = new ChangeSet(
                List.of(new ChangedEntry("docs/live.md", ChangeType.ADDED)),
                "cursor-watchable"
        );
        var watchable = new TestWatchableSource(changes);

        var binding = binding(watchable, stubReader(Map.of("docs/live.md", "live content")));

        service().processBinding(binding);

        assertThat(ingestor.getChunks(CORPUS)).hasSize(1);
        assertThat(ingestor.getChunks(CORPUS).getFirst().content()).isEqualTo("live content");
        assertThat(cursorStore.load("garden")).contains("cursor-watchable");
    }

    // --- Test 15: watchableSourceCurrentCursorUsedAfterSuccess ---

    @Test
    void watchableSourceCurrentCursorUsedAfterSuccess() {
        var changes = new ChangeSet(List.of(), "cursor-initial");
        var watchable = new TestWatchableSource(changes);

        var binding = binding(watchable, stubReader(Map.of()));

        service().processBinding(binding);

        assertThat(watchable.currentCursor()).isEqualTo("cursor-initial");
    }

    // --- Helper methods ---

    private CorpusIngestionBinding binding(ChangeSource changeSource, CorpusReader reader) {
        return binding(changeSource, reader, passthrough());
    }

    private CorpusIngestionBinding binding(ChangeSource changeSource, CorpusReader reader, MetadataExtractor extractor) {
        return new CorpusIngestionBinding("garden", CORPUS, changeSource, reader, extractor);
    }

    private MetadataExtractor passthrough() {
        return (path, content) -> new ExtractionResult(new String(content, StandardCharsets.UTF_8), Map.of());
    }

    private CorpusReader stubReader(Map<String, String> docs) {
        return new CorpusReader() {
            @Override public Optional<byte[]> read(String path) {
                String c = docs.get(path);
                return c == null ? Optional.empty() : Optional.of(c.getBytes(StandardCharsets.UTF_8));
            }
            @Override public Optional<InputStream> readStream(String path) { return Optional.empty(); }
            @Override public Optional<byte[]> readVersion(String path, int version) { return Optional.empty(); }
            @Override public List<VersionInfo> versions(String path) { return List.of(); }
            @Override public List<String> list() { return List.copyOf(docs.keySet()); }
            @Override public List<String> list(String prefix) { return list(); }
            @Override public boolean exists(String path) { return docs.containsKey(path); }
        };
    }

    private ChangeSource fixedSource(ChangeSet changes) {
        return new ChangeSource() {
            @Override public ChangeSet changesSince(String cursor) { return changes; }
            @Override public ChangeSet fullScan() { return changes; }
        };
    }

    private CorpusIngestionService service() {
        return new CorpusIngestionService(ingestor, cursorStore);
    }

    private static class TestWatchableSource implements WatchableChangeSource {
        private final ChangeSet catchUp;
        private ChangeListener listener;
        private boolean closed = false;

        TestWatchableSource(ChangeSet catchUp) {
            this.catchUp = catchUp;
        }

        @Override public void watch(ChangeListener l) { this.listener = l; }
        @Override public String currentCursor() { return catchUp.newCursor(); }
        @Override public void close() { closed = true; }
        @Override public ChangeSet changesSince(String cursor) { return catchUp; }
        @Override public ChangeSet fullScan() { return catchUp; }

        void fireEvent(List<ChangedEntry> entries) {
            if (listener != null) listener.onChange(entries);
        }
    }
}
