package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpQueryExpanderTest {

    @Test
    void expandReturnsSingleElementListWithOriginalQuery() {
        var expander = new NoOpQueryExpander();
        var query = RetrievalQuery.of("what is diabetes?");
        var result = expander.expand(query);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(query);
    }

    @Test
    void expandPreservesExistingExpansion() {
        var expander = new NoOpQueryExpander();
        var query = new RetrievalQuery("original", "prior expansion");
        var result = expander.expand(query);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(query);
        assertThat(result.get(0).expandedText()).isEqualTo("prior expansion");
    }
}
