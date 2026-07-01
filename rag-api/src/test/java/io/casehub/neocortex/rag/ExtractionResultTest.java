package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionResultTest {

    @Test
    void backwardCompatibleConstructor() {
        var result = new ExtractionResult("body", Map.of("key", "val"));
        assertThat(result.listMetadata()).isEmpty();
    }

    @Test
    void fullConstructorWithListMetadata() {
        var result = new ExtractionResult("body", Map.of("domain", "jvm"),
            Map.of("tags", List.of("cdi", "quarkus")));
        assertThat(result.listMetadata().get("tags")).containsExactly("cdi", "quarkus");
    }

    @Test
    void listMetadataIsImmutable() {
        var result = new ExtractionResult("body", Map.of(),
            Map.of("tags", List.of("one")));
        assertThatThrownBy(() -> result.listMetadata().put("new", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullBodyThrows() {
        assertThatThrownBy(() -> new ExtractionResult(null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("body must not be null");
    }

    @Test
    void nullMetadataDefaultsToEmptyMap() {
        var result = new ExtractionResult("body", null);
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var mutable = new HashMap<String, String>();
        mutable.put("key", "value");
        var result = new ExtractionResult("body", mutable);
        mutable.put("key2", "value2");
        assertThat(result.metadata()).containsOnlyKeys("key");
    }

    @Test
    void metadataIsUnmodifiable() {
        var result = new ExtractionResult("body", Map.of("key", "value"));
        assertThatThrownBy(() -> result.metadata().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyBodyIsAllowed() {
        var result = new ExtractionResult("", Map.of());
        assertThat(result.body()).isEmpty();
    }
}
