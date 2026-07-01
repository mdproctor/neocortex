package io.casehub.neocortex.corpus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrityIssueTest {

    @Test
    void validConstruction() {
        var issue = new IntegrityIssue(Severity.WARNING, "v3.zip", "Missing hash entry");
        assertThat(issue.severity()).isEqualTo(Severity.WARNING);
        assertThat(issue.zipFile()).isEqualTo("v3.zip");
        assertThat(issue.message()).isEqualTo("Missing hash entry");
    }

    @Test
    void nullSeverityThrows() {
        assertThatThrownBy(() -> new IntegrityIssue(null, "file.zip", "message"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("severity must not be null");
    }

    @Test
    void nullZipFileAllowed() {
        var issue = new IntegrityIssue(Severity.ERROR, null, "Global issue");
        assertThat(issue.zipFile()).isNull();
    }

    @Test
    void nullMessageThrows() {
        assertThatThrownBy(() -> new IntegrityIssue(Severity.INFO, "file.zip", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message must not be null or blank");
    }

    @Test
    void blankMessageThrows() {
        assertThatThrownBy(() -> new IntegrityIssue(Severity.ERROR, "file.zip", "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message must not be null or blank");
    }
}
