package io.casehub.neocortex.fusion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CamelCaseExpanderTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ConcurrentHashMap | ConcurrentHashMap Concurrent Hash Map",
        "maxResults | maxResults max Results",
        "HTMLParser | HTMLParser HTML Parser",
        "Base64Encoder | Base64Encoder Base 64 Encoder",
        "simple | simple",
        "ALLCAPS | ALLCAPS",
        "a | a",
        "AB | AB",
    })
    void expandsSingleToken(String input, String expected) {
        assertThat(CamelCaseExpander.expand(input)).isEqualTo(expected);
    }

    @Test
    void expandsMultipleTokensInText() {
        assertThat(CamelCaseExpander.expand("ConcurrentHashMap is useful"))
            .isEqualTo("ConcurrentHashMap Concurrent Hash Map is useful");
    }

    @Test
    void expandsMixedText() {
        assertThat(CamelCaseExpander.expand("maxResults for HTMLParser"))
            .isEqualTo("maxResults max Results for HTMLParser HTML Parser");
    }

    @Test
    void preservesPlainText() {
        assertThat(CamelCaseExpander.expand("simple words only"))
            .isEqualTo("simple words only");
    }

    @Test
    void handlesEmptyString() {
        assertThat(CamelCaseExpander.expand("")).isEqualTo("");
    }

    @Test
    void handlesNullReturnsEmpty() {
        assertThat(CamelCaseExpander.expand(null)).isEqualTo("");
    }

    @Test
    void xmlHttpRequestSplitsCorrectly() {
        assertThat(CamelCaseExpander.expand("XMLHttpRequest"))
            .isEqualTo("XMLHttpRequest XML Http Request");
    }

    @Test
    void consecutiveUppercaseEdgeCase() {
        assertThat(CamelCaseExpander.expand("XMLHTTPRequest"))
            .isEqualTo("XMLHTTPRequest XMLHTTP Request");
    }

    @Test
    void numberBoundary() {
        assertThat(CamelCaseExpander.expand("Base64Encoder"))
            .isEqualTo("Base64Encoder Base 64 Encoder");
    }

    @Test
    void preservesWhitespaceAndPunctuation() {
        assertThat(CamelCaseExpander.expand("@DefaultBean annotation"))
            .isEqualTo("@DefaultBean @Default Bean annotation");
    }
}
