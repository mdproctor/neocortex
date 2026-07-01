package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalQueryTest {

    @Test
    void ofCreatesQueryWithNullExpandedText() {
        var query = RetrievalQuery.of("find me something");
        assertThat(query.text()).isEqualTo("find me something");
        assertThat(query.expandedText()).isNull();
    }

    @Test
    void searchTextReturnsTextWhenNoExpansion() {
        var query = RetrievalQuery.of("original");
        assertThat(query.searchText()).isEqualTo("original");
    }

    @Test
    void searchTextReturnsExpandedTextWhenPresent() {
        var query = new RetrievalQuery("original", "hypothetical document about original");
        assertThat(query.searchText()).isEqualTo("hypothetical document about original");
    }

    @Test
    void withExpansionPreservesOriginalText() {
        var original = RetrievalQuery.of("my question");
        var expanded = original.withExpansion("a document answering my question");
        assertThat(expanded.text()).isEqualTo("my question");
        assertThat(expanded.expandedText()).isEqualTo("a document answering my question");
        assertThat(expanded.searchText()).isEqualTo("a document answering my question");
    }

    @Test
    void withExpansionDoesNotMutateOriginal() {
        var original = RetrievalQuery.of("my question");
        original.withExpansion("something");
        assertThat(original.expandedText()).isNull();
    }

    @Test
    void nullTextThrows() {
        assertThatThrownBy(() -> RetrievalQuery.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTextThrows() {
        assertThatThrownBy(() -> RetrievalQuery.of("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExpandedTextIsAllowed() {
        var query = new RetrievalQuery("question", null);
        assertThat(query.expandedText()).isNull();
        assertThat(query.searchText()).isEqualTo("question");
    }

    @Test
    void blankExpandedTextThrows() {
        assertThatThrownBy(() -> new RetrievalQuery("question", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordEquality() {
        var a = new RetrievalQuery("q", "e");
        var b = new RetrievalQuery("q", "e");
        assertThat(a).isEqualTo(b);
    }
}
