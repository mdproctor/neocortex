package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryQueryExpanderTest {

    @Test
    void expandReturnsSingleElementList() {
        var expander = new InMemoryQueryExpander();
        var result = expander.expand(RetrievalQuery.of("what is diabetes?"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("what is diabetes?");
        assertThat(result.get(0).expandedText()).isEqualTo("hypothetical: what is diabetes?");
        assertThat(result.get(0).searchText()).isEqualTo("hypothetical: what is diabetes?");
    }

    @Test
    void expandRecordsQueriesForAssertions() {
        var expander = new InMemoryQueryExpander();
        expander.expand(RetrievalQuery.of("query1"));
        expander.expand(RetrievalQuery.of("query2"));
        assertThat(expander.expandedQueries()).hasSize(2);
        assertThat(expander.expandedQueries().get(0).text()).isEqualTo("query1");
        assertThat(expander.expandedQueries().get(1).text()).isEqualTo("query2");
    }

    @Test
    void clearResetsRecordedQueries() {
        var expander = new InMemoryQueryExpander();
        expander.expand(RetrievalQuery.of("query1"));
        expander.clear();
        assertThat(expander.expandedQueries()).isEmpty();
    }

    @Test
    void expandPreservesExistingExpansion() {
        var expander = new InMemoryQueryExpander();
        var alreadyExpanded = new RetrievalQuery("original", "prior expansion");
        var result = expander.expand(alreadyExpanded);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("original");
        assertThat(result.get(0).expandedText()).isEqualTo("hypothetical: original");
    }
}
