package io.casehub.neocortex.rag.expansion;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class ExpansionConfigValidatorTest {

    @Test
    void warnsWhenModeIsEmpty() {
        var validator = new ExpansionConfigValidator();
        validator.config = stubConfig(Optional.empty());

        var record = captureWarning(validator);

        assertThat(record).isNotNull();
        assertThat(record.getMessage()).contains("no mode is set");
        assertThat(record.getMessage()).contains("casehub.rag.expansion.mode");
    }

    @Test
    void noWarningWhenModeIsSet() {
        var validator = new ExpansionConfigValidator();
        validator.config = stubConfig(Optional.of("llm"));

        var record = captureWarning(validator);

        assertThat(record).isNull();
    }

    private LogRecord captureWarning(ExpansionConfigValidator validator) {
        var logger = Logger.getLogger(ExpansionConfigValidator.class.getName());
        var captured = new LogRecord[1];
        var handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) captured[0] = r;
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            validator.onStartup(new StartupEvent());
        } finally {
            logger.removeHandler(handler);
        }
        return captured[0];
    }

    private static ExpansionConfig stubConfig(Optional<String> mode) {
        return new ExpansionConfig() {
            @Override public boolean enabled() { return true; }
            @Override public Optional<String> mode() { return mode; }
            @Override public int hypotheticalCount() { return 1; }
            @Override public Optional<String> promptTemplate() { return Optional.empty(); }
            @Override public Optional<String> template() { return Optional.empty(); }
            @Override public Optional<String> stepBackPromptTemplate() { return Optional.empty(); }
        };
    }
}
