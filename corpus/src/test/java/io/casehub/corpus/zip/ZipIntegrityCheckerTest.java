package io.casehub.corpus.zip;

import io.casehub.corpus.IntegrityIssue;
import io.casehub.corpus.IntegrityReport;
import io.casehub.corpus.Severity;
import net.lingala.zip4j.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZipIntegrityChecker}.
 */
class ZipIntegrityCheckerTest {

    @Test
    void healthyCorpusReportsOk(@TempDir Path tempDir) throws IOException {
        // Create a corpus with some entries and a rollover
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 512);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("doc1.txt", "content 1".getBytes());
        store.append("doc2.txt", "content 2".getBytes());
        store.append("doc3.txt", "content 3 with much longer content to trigger rollover".getBytes());

        // Run integrity check
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.check();

        assertEquals("test-corpus", report.corpusName());
        assertEquals("OK", report.status());
        assertTrue(report.issues().isEmpty());
        assertTrue(report.recovered().isEmpty());
    }

    @Test
    void missingZipFileReportsError(@TempDir Path tempDir) throws IOException {
        // Create a corpus with entries
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 512);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("doc1.txt", "content 1".getBytes());
        store.append("doc2.txt", "content 2".getBytes());

        // Trigger rollover to close the first ZIP
        store.append("doc3.txt", "content 3 with much longer content to trigger rollover".getBytes());

        // Delete the first (closed) ZIP file
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        assertTrue(Files.exists(firstZip), "First ZIP should exist");
        Files.delete(firstZip);

        // Run integrity check
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.check();

        assertEquals("FAILED", report.status());
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.severity() == Severity.ERROR && i.message().contains("not found")));
    }

    @Test
    void orphanedZipReportsWarning(@TempDir Path tempDir) throws IOException {
        // Create a corpus
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        store.append("doc1.txt", "content 1".getBytes());

        // Create an orphaned ZIP file (not referenced in chain.json)
        Path orphan = tempDir.resolve("orphan.zip");
        ZipFile orphanZip = new ZipFile(orphan.toFile());
        net.lingala.zip4j.model.ZipParameters params = new net.lingala.zip4j.model.ZipParameters();
        params.setFileNameInZip("dummy.txt");
        orphanZip.addStream(new java.io.ByteArrayInputStream("dummy".getBytes()), params);

        // Verify orphan exists
        assertTrue(Files.exists(orphan), "Orphan ZIP should exist");

        // Run integrity check
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.check();

        assertEquals("DEGRADED", report.status());
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.severity() == Severity.WARNING &&
                        i.message().toLowerCase().contains("orphan") &&
                        i.zipFile().equals("orphan.zip")),
                "Should report orphan.zip as orphaned");
    }

    @Test
    void missingInternalMetaReportsInfo(@TempDir Path tempDir) throws IOException {
        // Create a corpus and trigger rollover to close a ZIP
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append multiple documents to force rollover
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("doc1.txt", payload);
        store.append("doc2.txt", payload);
        store.append("doc3.txt", payload);  // This should trigger rollover

        // Verify rollover happened — should have test-corpus-1.zip (closed) and test-corpus-2.zip (active)
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        Path secondZip = tempDir.resolve("test-corpus-2.zip");
        assertTrue(Files.exists(firstZip), "First ZIP should exist after rollover");
        assertTrue(Files.exists(secondZip), "Second ZIP should exist after rollover");

        // Remove _chain/meta.json from the closed ZIP
        ZipFile zipFile = new ZipFile(firstZip.toFile());
        zipFile.removeFile("_chain/meta.json");

        // Run integrity check
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.check();

        // INFO severity — missing internal meta is not critical
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.severity() == Severity.INFO &&
                        i.message().contains("_chain/meta.json") &&
                        i.message().contains("missing")),
                "Should report missing _chain/meta.json");
    }

    @Test
    void checkAndRecoverFixesMissingInternalMeta(@TempDir Path tempDir) throws IOException {
        // Create a corpus and trigger rollover
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append multiple documents to force rollover
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("doc1.txt", payload);
        store.append("doc2.txt", payload);
        store.append("doc3.txt", payload);

        // Remove _chain/meta.json from the closed ZIP
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        ZipFile zipFile = new ZipFile(firstZip.toFile());
        zipFile.removeFile("_chain/meta.json");

        // Run checkAndRecover
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.checkAndRecover();

        // Should have recovered
        assertTrue(report.recovered().stream()
                .anyMatch(r -> r.contains("_chain/meta.json") && r.contains("test-corpus-1.zip")));

        // Verify _chain/meta.json was restored
        zipFile = new ZipFile(firstZip.toFile());
        assertNotNull(zipFile.getFileHeader("_chain/meta.json"),
                "_chain/meta.json should be restored");
    }

    @Test
    void missingChainJsonReportsWarningAndRecovers(@TempDir Path tempDir) throws IOException {
        // Create a corpus and trigger rollover
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append multiple documents to force rollover
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("doc1.txt", payload);
        store.append("doc2.txt", payload);
        store.append("doc3.txt", payload);

        // Delete chain.json
        Path chainJson = tempDir.resolve("chain.json");
        assertTrue(Files.exists(chainJson));
        Files.delete(chainJson);

        // Run checkAndRecover
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.checkAndRecover();

        // Should have recovered chain.json
        assertTrue(report.recovered().stream()
                .anyMatch(r -> r.contains("chain.json")));

        // Verify chain.json was restored
        assertTrue(Files.exists(chainJson), "chain.json should be restored");
    }

    @Test
    void fullHashVerificationDetectsTampering(@TempDir Path tempDir) throws IOException {
        // Create a corpus and trigger rollover to close a ZIP
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append multiple documents to force rollover
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("doc1.txt", payload);
        store.append("doc2.txt", payload);
        store.append("doc3.txt", payload);

        // Tamper with the closed ZIP by appending garbage
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        assertTrue(Files.exists(firstZip));
        // Append enough data to definitely change the hash
        byte[] garbage = new byte[1024];
        java.util.Arrays.fill(garbage, (byte) 0xFF);
        Files.write(firstZip, garbage, java.nio.file.StandardOpenOption.APPEND);

        // Run full hash verification
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        IntegrityReport report = checker.fullHashVerification();

        assertEquals("FAILED", report.status());
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.severity() == Severity.ERROR &&
                        i.message().contains("hash") &&
                        i.message().contains("mismatch")));
    }

    @Test
    void checkIsReadOnly(@TempDir Path tempDir) throws IOException {
        // Create a corpus with a missing internal meta
        CorpusConfig config = new CorpusConfig("test-corpus", tempDir, 1024);
        ZipCorpusStore store = new ZipCorpusStore(config);

        // Append multiple documents to force rollover
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("doc1.txt", payload);
        store.append("doc2.txt", payload);
        store.append("doc3.txt", payload);

        // Remove _chain/meta.json from the closed ZIP
        Path firstZip = tempDir.resolve("test-corpus-1.zip");
        ZipFile zipFile = new ZipFile(firstZip.toFile());
        zipFile.removeFile("_chain/meta.json");

        // Record file modification times
        long chainJsonLastModified = Files.getLastModifiedTime(tempDir.resolve("chain.json")).toMillis();
        long zipLastModified = Files.getLastModifiedTime(firstZip).toMillis();

        // Run check (read-only)
        ZipIntegrityChecker checker = new ZipIntegrityChecker(tempDir, "test-corpus");
        checker.check();

        // Wait a bit to ensure any modifications would be detectable
        try { Thread.sleep(100); } catch (InterruptedException e) { }

        // Verify no files were modified
        assertEquals(chainJsonLastModified,
                Files.getLastModifiedTime(tempDir.resolve("chain.json")).toMillis(),
                "chain.json should not be modified by check()");
        assertEquals(zipLastModified,
                Files.getLastModifiedTime(firstZip).toMillis(),
                "ZIP file should not be modified by check()");

        // Verify _chain/meta.json is still missing
        zipFile = new ZipFile(firstZip.toFile());
        assertNull(zipFile.getFileHeader("_chain/meta.json"),
                "_chain/meta.json should still be missing");
    }
}
