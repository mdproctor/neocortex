package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.*;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockingToReactiveBridgeTest {

    @TempDir
    Path tempDir;

    private ZipCorpusStore blockingStore;
    private ZipChangeSource blockingChangeSource;
    private BlockingToReactiveCorpusStoreBridge reactiveStore;
    private BlockingToReactiveCorpusReaderBridge reactiveReader;
    private BlockingToReactiveChangeSourceBridge reactiveChangeSource;

    @BeforeEach
    void setup() {
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir);
        blockingStore = new ZipCorpusStore(config);
        blockingChangeSource = new ZipChangeSource(blockingStore);
        reactiveStore = new BlockingToReactiveCorpusStoreBridge(blockingStore);
        reactiveReader = new BlockingToReactiveCorpusReaderBridge(blockingStore);
        reactiveChangeSource = new BlockingToReactiveChangeSourceBridge(blockingChangeSource);
    }

    @Test
    void reactiveStoreAppendAndRead() {
        // Append via reactive bridge
        byte[] content = "test content".getBytes();
        reactiveStore.append("test.txt", content)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .assertCompleted();

        // Read via blocking reader to verify
        Optional<byte[]> read = blockingStore.read("test.txt");
        assertThat(read).isPresent();
        assertThat(read.get()).isEqualTo(content);
    }

    @Test
    void reactiveStoreAppendInputStream() throws IOException {
        byte[] content = "stream content".getBytes();
        InputStream stream = new ByteArrayInputStream(content);

        reactiveStore.append("stream.txt", stream)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .assertCompleted();

        Optional<byte[]> read = blockingStore.read("stream.txt");
        assertThat(read).isPresent();
        assertThat(read.get()).isEqualTo(content);
    }

    @Test
    void reactiveStoreAppendPath() throws IOException {
        Path file = tempDir.resolve("source.txt");
        byte[] content = "file content".getBytes();
        Files.write(file, content);

        reactiveStore.append("from-path.txt", file)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .assertCompleted();

        Optional<byte[]> read = blockingStore.read("from-path.txt");
        assertThat(read).isPresent();
        assertThat(read.get()).isEqualTo(content);
    }

    @Test
    void reactiveReaderReadReturnsContent() {
        // Append via blocking store
        byte[] content = "reader test".getBytes();
        blockingStore.append("reader.txt", content);

        // Read via reactive bridge
        Optional<byte[]> result = reactiveReader.read("reader.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(content);
    }

    @Test
    void reactiveReaderReadStream() throws IOException {
        byte[] content = "stream read test".getBytes();
        blockingStore.append("stream-read.txt", content);

        Optional<InputStream> result = reactiveReader.readStream("stream-read.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(result).isPresent();
        assertThat(result.get().readAllBytes()).isEqualTo(content);
    }

    @Test
    void reactiveReaderReadVersion() {
        // Create multiple versions
        blockingStore.append("versioned.txt", "v1".getBytes());
        blockingStore.append("versioned.txt", "v2".getBytes());
        blockingStore.append("versioned.txt", "v3".getBytes());

        // Read version 1 (0-indexed)
        Optional<byte[]> result = reactiveReader.readVersion("versioned.txt", 1)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("v1".getBytes());
    }

    @Test
    void reactiveReaderVersions() {
        blockingStore.append("multi.txt", "v1".getBytes());
        blockingStore.append("multi.txt", "v2".getBytes());

        List<VersionInfo> versions = reactiveReader.versions("multi.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
    }

    @Test
    void reactiveReaderList() {
        blockingStore.append("file1.txt", "content1".getBytes());
        blockingStore.append("file2.txt", "content2".getBytes());

        List<String> files = reactiveReader.list()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(files).containsExactlyInAnyOrder("file1.txt", "file2.txt");
    }

    @Test
    void reactiveReaderListPrefix() {
        blockingStore.append("docs/a.txt", "a".getBytes());
        blockingStore.append("docs/b.txt", "b".getBytes());
        blockingStore.append("other/c.txt", "c".getBytes());

        List<String> files = reactiveReader.list("docs/")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(files).containsExactlyInAnyOrder("docs/a.txt", "docs/b.txt");
    }

    @Test
    void reactiveReaderExists() {
        blockingStore.append("exists.txt", "yes".getBytes());

        Boolean exists = reactiveReader.exists("exists.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(exists).isTrue();

        Boolean notExists = reactiveReader.exists("nope.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(notExists).isFalse();
    }

    @Test
    void reactiveChangeSourceReturnsChanges() {
        // Append via blocking store
        blockingStore.append("change1.txt", "c1".getBytes());
        blockingStore.append("change2.txt", "c2".getBytes());

        // Call bridge fullScan()
        ChangeSet changeSet = reactiveChangeSource.fullScan()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(changeSet.entries()).hasSize(2);
        assertThat(changeSet.entries()).extracting(ChangedEntry::path)
            .containsExactlyInAnyOrder("change1.txt", "change2.txt");
    }

    @Test
    void reactiveChangeSourceChangesSince() {
        blockingStore.append("initial.txt", "i".getBytes());
        ChangeSet initial = blockingChangeSource.fullScan();
        String cursor = initial.newCursor();

        blockingStore.append("after.txt", "a".getBytes());

        ChangeSet delta = reactiveChangeSource.changesSince(cursor)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(delta.entries()).hasSize(1);
        assertThat(delta.entries().get(0).path()).isEqualTo("after.txt");
    }

    @Test
    void reactiveStoreDeleteWorks() {
        blockingStore.append("to-delete.txt", "gone".getBytes());
        assertThat(blockingStore.exists("to-delete.txt")).isTrue();

        reactiveStore.delete("to-delete.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .assertCompleted();

        assertThat(blockingStore.exists("to-delete.txt")).isFalse();
    }

    @Test
    void bridgeExceptionFlowsThroughUni() {
        // Attempt invalid operation (null path)
        UniAssertSubscriber<Void> subscriber = reactiveStore.append(null, "content".getBytes())
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure();
        assertThat(subscriber.getFailure()).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path must not be null or blank");
    }

    @Test
    void bridgeReaderExceptionFlowsThroughUni() {
        // Read non-existent path returns empty Optional, not an exception
        UniAssertSubscriber<Optional<byte[]>> subscriber = reactiveReader.read("nonexistent.txt")
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitItem();
        assertThat(subscriber.getItem()).isEmpty();
    }
}
