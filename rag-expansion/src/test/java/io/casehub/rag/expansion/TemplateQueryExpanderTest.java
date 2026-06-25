package io.casehub.rag.expansion;

import io.casehub.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateQueryExpanderTest {

    @Test
    void expandUsesDefaultTemplate() {
        var expander = new TemplateQueryExpander(stubConfig(Optional.empty()));
        var results = expander.expand(RetrievalQuery.of("what is diabetes?"));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.text()).isEqualTo("what is diabetes?");
        assertThat(result.expandedText()).contains("what is diabetes?");
        assertThat(result.expandedText()).contains("A document that answers");
    }

    @Test
    void expandUsesCustomTemplate() {
        var expander = new TemplateQueryExpander(
            stubConfig(Optional.of("Product matching query: %s")));
        var results = expander.expand(RetrievalQuery.of("SKU-123"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).expandedText()).isEqualTo("Product matching query: SKU-123");
    }

    @Test
    void expandPreservesOriginalText() {
        var expander = new TemplateQueryExpander(stubConfig(Optional.empty()));
        var results = expander.expand(RetrievalQuery.of("test"));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.text()).isEqualTo("test");
    }

    private static ExpansionConfig stubConfig(Optional<String> template) {
        return new ExpansionConfig() {
            @Override public boolean enabled() { return true; }
            @Override public String mode() { return "template"; }
            @Override public int hypotheticalCount() { return 1; }
            @Override public Optional<String> promptTemplate() { return Optional.empty(); }
            @Override public Optional<String> template() { return template; }
            @Override public Optional<String> stepBackPromptTemplate() { return Optional.empty(); }
        };
    }
}
