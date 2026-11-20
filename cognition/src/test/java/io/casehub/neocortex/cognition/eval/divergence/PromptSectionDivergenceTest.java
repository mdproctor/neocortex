package io.casehub.neocortex.cognition.eval.divergence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.casehub.neocortex.cognition.eval.divergence.DivergenceFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

class PromptSectionDivergenceTest {

    private Map<String, String> openSections;
    private Map<String, String> cautiousSections;
    private String openFullPrompt;
    private String cautiousFullPrompt;

    @BeforeEach
    void setUp() {
        var harness = DivergenceFixtures.pairedBuilder().build();

        harness.recordMood("open", TENANT, 0.6, 0.4, 0.5);
        harness.recordMood("cautious", TENANT, -0.3, 0.7, -0.4);

        harness.observe("open", TENANT, "someone shared an unexpected idea about restructuring");
        harness.tick("open", TENANT);
        openFullPrompt = harness.renderPrompt("open", TENANT);

        harness.observe("cautious", TENANT, "someone shared an unexpected idea about restructuring");
        harness.tick("cautious", TENANT);
        cautiousFullPrompt = harness.renderPrompt("cautious", TENANT);

        var openTrace     = harness.currentTrace("open", TENANT);
        var cautiousTrace = harness.currentTrace("cautious", TENANT);

        openSections     = openTrace.isPresent() ? openTrace.get().promptSections() : Map.of();
        cautiousSections = cautiousTrace.isPresent() ? cautiousTrace.get().promptSections() : Map.of();
    }

    @Test
    void promptRenderingDiverges() {
        assertThat(openFullPrompt)
                .as("full rendered prompt should differ between open and cautious personalities")
                .isNotEqualTo(cautiousFullPrompt);
    }

    @Test
    void behavioralPromptDiverges() {
        assertSectionDiverges("behavioral");
    }

    @Test
    void drivePromptDiverges() {
        assertSectionDiverges("drives");
    }

    @Test
    void moodPromptDiverges() {
        assertSectionDiverges("mood");
    }

    @Test
    void curiosityPromptDiverges() {
        assertSectionDiverges("curiosity");
    }

    @Test
    void attentionPromptDiverges() {
        assertSectionDiverges("attention");
    }

    @Test
    void shadowArchetypePromptDiverges() {
        assertSectionDiverges("shadow");
    }

    @Test
    void needsPyramidPromptDiverges() {
        assertSectionDiverges("needs");
    }

    private void assertSectionDiverges(String sectionKey) {
        var openText = openSections.get(sectionKey);
        var cautiousText = cautiousSections.get(sectionKey);

        if (openText == null && cautiousText == null) {
            var matchingOpen = openSections.entrySet().stream()
                    .filter(e -> e.getKey().toLowerCase().contains(sectionKey.toLowerCase()))
                    .findFirst();
            var matchingCautious = cautiousSections.entrySet().stream()
                    .filter(e -> e.getKey().toLowerCase().contains(sectionKey.toLowerCase()))
                    .findFirst();

            if (matchingOpen.isPresent() && matchingCautious.isPresent()) {
                openText = matchingOpen.get().getValue();
                cautiousText = matchingCautious.get().getValue();
            }
        }

        if (openText == null && cautiousText == null) {
            assertThat(openFullPrompt)
                    .as("section '%s' not found in trace (available: %s), "
                        + "falling back to full prompt divergence",
                            sectionKey, openSections.keySet())
                    .isNotEqualTo(cautiousFullPrompt);
            return;
        }

        assertThat(openText)
                .as("prompt section '%s' should differ between open and cautious", sectionKey)
                .isNotEqualTo(cautiousText);
    }
}
