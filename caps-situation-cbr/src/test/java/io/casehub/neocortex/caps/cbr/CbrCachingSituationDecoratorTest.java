package io.casehub.neocortex.caps.cbr;

import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CbrCachingSituationDecoratorTest {

    private SituationClassifier delegate;
    private CbrRecordStore cbrStore;
    private CbrCachingSituationDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock(SituationClassifier.class);
        cbrStore = mock(CbrRecordStore.class);
        decorator = new CbrCachingSituationDecorator(
            delegate, cbrStore, 0.85, "haiku");
    }

    @Test
    void contextNone_passesThrough() {
        var expected = new ClassificationResult(List.of(new SituationActivation("acceptance", 0.8)));
        when(delegate.classify("test", Map.of(), ClassificationContext.NONE))
            .thenReturn(expected);

        var result = decorator.classify("test", Map.of(), ClassificationContext.NONE);

        assertThat(result.activations()).isEqualTo(expected.activations());
        assertThat(result.caseId()).isNull();
        verifyNoInteractions(cbrStore);
    }

    @SuppressWarnings("unchecked")
    @Test
    void cbrHit_returnsCached() {
        var ctx = ClassificationContext.of("tenant-1");
        String json = "{\"activations\":[{\"node\":\"mastery\",\"confidence\":0.9}]}";

        var match = mock(CbrMatch.class);
        var record = new CbrFeatureRecord("test desc", json,
            null, null, Map.of("model-version", FeatureValue.string("haiku")),
            null, null);
        when(match.cbrRecord()).thenReturn(record);
        when(match.score()).thenReturn(0.92);
        when(cbrStore.retrieveSimilar(any(CbrQuery.class), eq(CbrFeatureRecord.class)))
            .thenReturn(List.of(match));

        var result = decorator.classify("test desc", Map.of(), ctx);

        assertThat(result.activations()).hasSize(1);
        assertThat(result.activations().getFirst().nodeId()).isEqualTo("mastery");
        assertThat(result.activations().getFirst().confidence()).isEqualTo(0.9);
        assertThat(result.caseId()).isNotNull();
        verifyNoInteractions(delegate);
    }

    @Test
    void cbrMiss_delegatesAndStores() {
        var ctx = ClassificationContext.of("tenant-1");
        var llmResult = new ClassificationResult(List.of(new SituationActivation("acceptance", 0.8)));
        when(cbrStore.retrieveSimilar(any(CbrQuery.class), eq(CbrFeatureRecord.class)))
            .thenReturn(List.of());
        when(delegate.classify("new situation", Map.of(), ctx))
            .thenReturn(llmResult);

        var result = decorator.classify("new situation", Map.of(), ctx);

        assertThat(result.activations()).isEqualTo(llmResult.activations());
        assertThat(result.caseId()).isNotNull();
        verify(cbrStore).store(any(), eq("situation-classification"),
            eq("caps-classifier"), any(MemoryDomain.class),
            eq("tenant-1"), any(), any());
    }

    @Test
    void delegateThrows_exceptionPropagates_noStore() {
        var ctx = ClassificationContext.of("tenant-1");
        when(cbrStore.retrieveSimilar(any(CbrQuery.class), eq(CbrFeatureRecord.class)))
            .thenReturn(List.of());
        when(delegate.classify("failing", Map.of(), ctx))
            .thenThrow(new RuntimeException("LLM failed"));

        assertThatThrownBy(() -> decorator.classify("failing", Map.of(), ctx))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("LLM failed");
        verify(cbrStore, never()).store(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void noCbrStore_passesThrough() {
        var noCbrDecorator = new CbrCachingSituationDecorator(
            delegate, null, 0.85, "haiku");
        var ctx = ClassificationContext.of("tenant-1");
        var expected = new ClassificationResult(List.of(new SituationActivation("mastery", 0.7)));
        when(delegate.classify("test", Map.of(), ctx)).thenReturn(expected);

        var result = noCbrDecorator.classify("test", Map.of(), ctx);

        assertThat(result.activations()).isEqualTo(expected.activations());
        assertThat(result.caseId()).isNull();
    }

    @Test
    void serializeAndParseRoundTrip() {
        var activations = List.of(
            new SituationActivation("acceptance", 0.8),
            new SituationActivation("mastery", 0.6));
        String json = decorator.serializeActivations(activations);
        List<SituationActivation> parsed = decorator.parseActivations(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).nodeId()).isEqualTo("acceptance");
        assertThat(parsed.get(0).confidence()).isEqualTo(0.8);
        assertThat(parsed.get(1).nodeId()).isEqualTo("mastery");
        assertThat(parsed.get(1).confidence()).isEqualTo(0.6);
    }

    @Test
    void deterministicCaseId() {
        String id1 = CbrCachingSituationDecorator.computeCaseId("desc", "obs", "concern");
        String id2 = CbrCachingSituationDecorator.computeCaseId("desc", "obs", "concern");
        String id3 = CbrCachingSituationDecorator.computeCaseId("desc2", "obs", "concern");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
    }

    @Test
    void computeCaseId_includesDisposition() {
        String idNoDisp = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern", Map.of());
        String idWithDisp = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern",
            Map.of("disposition.socialOrient", "competitive"));

        assertThat(idNoDisp).isNotEqualTo(idWithDisp);
    }

    @Test
    void computeCaseId_differentDispositions_differentIds() {
        String id1 = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern",
            Map.of("disposition.socialOrient", "competitive"));
        String id2 = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern",
            Map.of("disposition.socialOrient", "cooperative"));

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void computeCaseId_sameDisposition_sameId() {
        String id1 = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern",
            Map.of("disposition.socialOrient", "competitive",
                   "disposition.riskAppetite", "bold"));
        String id2 = CbrCachingSituationDecorator.computeCaseId(
            "desc", "obs", "concern",
            Map.of("disposition.socialOrient", "competitive",
                   "disposition.riskAppetite", "bold"));

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void computeCaseId_backwardCompatible_noDisposition() {
        String oldStyle = CbrCachingSituationDecorator.computeCaseId("desc", "obs", "concern");
        String newStyleEmpty = CbrCachingSituationDecorator.computeCaseId("desc", "obs", "concern", Map.of());

        assertThat(oldStyle).isEqualTo(newStyleEmpty);
    }

    @Test
    void emptyActivations_notStored() {
        var ctx = ClassificationContext.of("tenant-1");
        when(cbrStore.retrieveSimilar(any(CbrQuery.class), eq(CbrFeatureRecord.class)))
            .thenReturn(List.of());
        when(delegate.classify("empty", Map.of(), ctx))
            .thenReturn(new ClassificationResult(List.of()));

        var result = decorator.classify("empty", Map.of(), ctx);

        assertThat(result.activations()).isEmpty();
        verify(cbrStore, never()).store(any(), any(), any(), any(), any(), any(), any());
    }
}
