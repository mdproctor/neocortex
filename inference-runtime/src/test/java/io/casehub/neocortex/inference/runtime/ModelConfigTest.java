package io.casehub.neocortex.inference.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigTest {

    private static final Path MODEL = Path.of("model.onnx");
    private static final Path TOKENIZER = Path.of("tokenizer.json");

    // -- two-arg convenience constructor -----------------------------------

    @Nested
    @DisplayName("two-arg constructor")
    class TwoArg {

        @Test
        void defaultsMaxSequenceLength512() {
            var cfg = new ModelConfig(MODEL, TOKENIZER);
            assertThat(cfg.maxSequenceLength()).isEqualTo(512);
        }

        @Test
        void defaultsIntraOpThreadsZero() {
            var cfg = new ModelConfig(MODEL, TOKENIZER);
            assertThat(cfg.intraOpThreads()).isEqualTo(0);
        }

        @Test
        void defaultsInterOpThreadsZero() {
            var cfg = new ModelConfig(MODEL, TOKENIZER);
            assertThat(cfg.interOpThreads()).isEqualTo(0);
        }
    }

    // -- three-arg convenience constructor ---------------------------------

    @Nested
    @DisplayName("three-arg constructor")
    class ThreeArg {

        @Test
        void setsMaxSequenceLength() {
            var cfg = new ModelConfig(MODEL, TOKENIZER, 128);
            assertThat(cfg.maxSequenceLength()).isEqualTo(128);
        }

        @Test
        void defaultsThreadCountsZero() {
            var cfg = new ModelConfig(MODEL, TOKENIZER, 256);
            assertThat(cfg.intraOpThreads()).isEqualTo(0);
            assertThat(cfg.interOpThreads()).isEqualTo(0);
        }
    }

    // -- validation --------------------------------------------------------

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsNullModelPath() {
            assertThatThrownBy(() -> new ModelConfig(null, TOKENIZER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelPath");
        }

        @Test
        void rejectsNullTokenizerPath() {
            assertThatThrownBy(() -> new ModelConfig(MODEL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokenizerPath");
        }

        @Test
        void rejectsZeroMaxSequenceLength() {
            assertThatThrownBy(() -> new ModelConfig(MODEL, TOKENIZER, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSequenceLength");
        }

        @Test
        void rejectsNegativeMaxSequenceLength() {
            assertThatThrownBy(() -> new ModelConfig(MODEL, TOKENIZER, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSequenceLength");
        }

        @Test
        void rejectsNegativeIntraOpThreads() {
            assertThatThrownBy(() -> new ModelConfig(MODEL, TOKENIZER, 512, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intraOpThreads");
        }

        @Test
        void rejectsNegativeInterOpThreads() {
            assertThatThrownBy(() -> new ModelConfig(MODEL, TOKENIZER, 512, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interOpThreads");
        }
    }

    // -- custom thread counts ----------------------------------------------

    @Nested
    @DisplayName("custom thread counts")
    class CustomThreadCounts {

        @Test
        void acceptsCustomValues() {
            var cfg = new ModelConfig(MODEL, TOKENIZER, 256, 4, 2);
            assertThat(cfg.maxSequenceLength()).isEqualTo(256);
            assertThat(cfg.intraOpThreads()).isEqualTo(4);
            assertThat(cfg.interOpThreads()).isEqualTo(2);
        }

        @Test
        void zeroThreadCountsMeansOrtDefault() {
            var cfg = new ModelConfig(MODEL, TOKENIZER, 512, 0, 0);
            assertThat(cfg.intraOpThreads()).isEqualTo(0);
            assertThat(cfg.interOpThreads()).isEqualTo(0);
        }
    }
}
