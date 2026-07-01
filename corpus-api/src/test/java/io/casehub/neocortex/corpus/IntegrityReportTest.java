package io.casehub.neocortex.corpus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrityReportTest {

    @Test
    void validConstruction() {
        var issues = List.of(new IntegrityIssue(Severity.WARNING, "v2.zip", "Duplicate entry"));
        var recovered = List.of("v1.zip", "v3.zip");
        var report = new IntegrityReport("legal-docs", 5, 1000L, "OK", issues, recovered);

        assertThat(report.corpusName()).isEqualTo("legal-docs");
        assertThat(report.chainLength()).isEqualTo(5);
        assertThat(report.totalEntries()).isEqualTo(1000L);
        assertThat(report.status()).isEqualTo("OK");
        assertThat(report.issues()).hasSize(1);
        assertThat(report.recovered()).hasSize(2);
    }

    @Test
    void nullCorpusNameThrows() {
        assertThatThrownBy(() -> new IntegrityReport(null, 1, 0L, "OK", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corpusName must not be null or blank");
    }

    @Test
    void blankCorpusNameThrows() {
        assertThatThrownBy(() -> new IntegrityReport("  ", 1, 0L, "OK", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corpusName must not be null or blank");
    }

    @Test
    void nullStatusThrows() {
        assertThatThrownBy(() -> new IntegrityReport("corpus", 1, 0L, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status must not be null or blank");
    }

    @Test
    void blankStatusThrows() {
        assertThatThrownBy(() -> new IntegrityReport("corpus", 1, 0L, "   ", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status must not be null or blank");
    }

    @Test
    void nullIssuesBecomesEmptyList() {
        var report = new IntegrityReport("corpus", 1, 0L, "OK", null, null);
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void nullRecoveredBecomesEmptyList() {
        var report = new IntegrityReport("corpus", 1, 0L, "OK", null, null);
        assertThat(report.recovered()).isEmpty();
    }

    @Test
    void issuesListIsDefensivelyCopied() {
        var mutable = new ArrayList<IntegrityIssue>();
        mutable.add(new IntegrityIssue(Severity.INFO, null, "test"));
        var report = new IntegrityReport("corpus", 1, 0L, "OK", mutable, null);

        mutable.add(new IntegrityIssue(Severity.ERROR, null, "another"));

        assertThat(report.issues()).hasSize(1);
        assertThatThrownBy(() -> report.issues().add(new IntegrityIssue(Severity.WARNING, null, "extra")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recoveredListIsDefensivelyCopied() {
        var mutable = new ArrayList<String>();
        mutable.add("file1.zip");
        var report = new IntegrityReport("corpus", 1, 0L, "OK", null, mutable);

        mutable.add("file2.zip");

        assertThat(report.recovered()).hasSize(1);
        assertThatThrownBy(() -> report.recovered().add("file3.zip"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
