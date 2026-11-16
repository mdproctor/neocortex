package io.casehub.neocortex.caps.cbr;

import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CbrDispositionFilterIsolationTest {

    private SituationClassifier delegate;
    private InMemoryCbrRecordStore cbrStore;
    private CbrCachingSituationDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock(SituationClassifier.class);
        cbrStore = new InMemoryCbrRecordStore();
        cbrStore.registerSchema(CbrCachingSituationDecorator.SCHEMA);
        decorator = new CbrCachingSituationDecorator(
            delegate, cbrStore, 0.85, "haiku");
    }

    @Test
    void differentDispositions_noCacheCollision() {
        var ctx = ClassificationContext.of("tenant-1");

        var personalityA = dispositionMetadata(
            "competitive", "strict", "cautious", "independent", "direct");
        var personalityB = dispositionMetadata(
            "cooperative", "strict", "cautious", "independent", "direct");

        when(delegate.classify("heated argument", personalityA, ctx))
            .thenReturn(new ClassificationResult(
                List.of(new SituationActivation("threat_detected", 0.9))));
        when(delegate.classify("heated argument", personalityB, ctx))
            .thenReturn(new ClassificationResult(
                List.of(new SituationActivation("social_support", 0.8))));

        // Step 1: personality A — delegate called, result stored
        var result1 = decorator.classify("heated argument", personalityA, ctx);
        assertThat(result1.activations().getFirst().nodeId()).isEqualTo("threat_detected");
        verify(delegate, times(1)).classify("heated argument", personalityA, ctx);

        // Step 2: same text, personality B — filter excludes A's entry, delegate called
        var result2 = decorator.classify("heated argument", personalityB, ctx);
        assertThat(result2.activations().getFirst().nodeId()).isEqualTo("social_support");
        verify(delegate, times(1)).classify("heated argument", personalityB, ctx);

        // Step 3: personality A again — cache hit, delegate NOT called again
        var result3 = decorator.classify("heated argument", personalityA, ctx);
        assertThat(result3.activations().getFirst().nodeId()).isEqualTo("threat_detected");
        verify(delegate, times(1)).classify("heated argument", personalityA, ctx);
    }

    @Test
    void sameDisposition_cacheHitReturned() {
        var ctx = ClassificationContext.of("tenant-1");
        var metadata = dispositionMetadata(
            "competitive", "strict", "cautious", "independent", "direct");

        when(delegate.classify("stressful situation", metadata, ctx))
            .thenReturn(new ClassificationResult(
                List.of(new SituationActivation("threat_detected", 0.9))));

        decorator.classify("stressful situation", metadata, ctx);
        verify(delegate, times(1)).classify("stressful situation", metadata, ctx);

        var result = decorator.classify("stressful situation", metadata, ctx);
        assertThat(result.activations().getFirst().nodeId()).isEqualTo("threat_detected");
        verify(delegate, times(1)).classify("stressful situation", metadata, ctx);
    }

    @Test
    void multipleDispositionAxisDifferences_noCollision() {
        var ctx = ClassificationContext.of("tenant-1");

        var personalityA = dispositionMetadata(
            "competitive", "strict", "bold", "independent", "direct");
        var personalityB = dispositionMetadata(
            "cooperative", "flexible", "cautious", "collaborative", "avoidant");

        when(delegate.classify("group conflict", personalityA, ctx))
            .thenReturn(new ClassificationResult(
                List.of(new SituationActivation("dominance_assertion", 0.85))));
        when(delegate.classify("group conflict", personalityB, ctx))
            .thenReturn(new ClassificationResult(
                List.of(new SituationActivation("withdrawal", 0.7))));

        var result1 = decorator.classify("group conflict", personalityA, ctx);
        assertThat(result1.activations().getFirst().nodeId()).isEqualTo("dominance_assertion");

        var result2 = decorator.classify("group conflict", personalityB, ctx);
        assertThat(result2.activations().getFirst().nodeId()).isEqualTo("withdrawal");
        verify(delegate, times(1)).classify("group conflict", personalityB, ctx);
    }

    private static Map<String, String> dispositionMetadata(
            String socialOrient, String ruleFollowing, String riskAppetite,
            String autonomy, String conflictMode) {
        var map = new HashMap<String, String>();
        map.put("disposition.socialOrient", socialOrient);
        map.put("disposition.ruleFollowing", ruleFollowing);
        map.put("disposition.riskAppetite", riskAppetite);
        map.put("disposition.autonomy", autonomy);
        map.put("disposition.conflictMode", conflictMode);
        return map;
    }
}
