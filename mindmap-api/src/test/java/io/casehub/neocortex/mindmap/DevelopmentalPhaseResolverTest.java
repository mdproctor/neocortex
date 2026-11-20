package io.casehub.neocortex.mindmap;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentalPhaseResolverTest {

    @Test
    void childReturnsChildhood() {
        var born = LifecycleDate.parse("2020-01-15");
        var now = LocalDate.of(2028, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.CHILDHOOD);
    }

    @Test
    void adolescentReturnsAdolescence() {
        var born = LifecycleDate.parse("2010-03-20");
        var now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.ADOLESCENCE);
    }

    @Test
    void youngAdultReturnsEarlyAdulthood() {
        var born = LifecycleDate.parse("2004-07-10");
        var now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.EARLY_ADULTHOOD);
    }

    @Test
    void adultReturnsAdulthood() {
        var born = LifecycleDate.parse("1990-01-01");
        var now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

    @Test
    void yearOnlyBornDate() {
        var born = LifecycleDate.parse("1907");
        var now = LocalDate.of(1920, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.ADOLESCENCE);
    }

    @Test
    void nullBornDefaultsToAdulthood() {
        assertThat(DevelopmentalPhaseResolver.resolvePhase((LifecycleDate) null, java.time.Instant.now()))
            .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

    @Test
    void boundaryAge12IsChildhood() {
        var born = LifecycleDate.parse("2013-01-01");
        var now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.CHILDHOOD);
    }

    @Test
    void boundaryAge13IsAdolescence() {
        var born = LifecycleDate.parse("2012-01-01");
        var now = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(born, now))
            .isEqualTo(DevelopmentalPhaseResolver.ADOLESCENCE);
    }

    @Test
    void nodeWithValidBornProperty() {
        var node = new TestNode(java.util.Map.of(LifecycleEvents.key("born"), "2010-03-20"));
        var now  = LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(DevelopmentalPhaseResolver.resolvePhase(node, now))
                .isEqualTo(DevelopmentalPhaseResolver.ADOLESCENCE);
    }

    @Test
    void nodeWithMissingBornProperty() {
        var node = new TestNode(java.util.Map.of());
        assertThat(DevelopmentalPhaseResolver.resolvePhase(node, java.time.Instant.now()))
                .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

    @Test
    void nodeWithBlankBornValue() {
        var node = new TestNode(java.util.Map.of(LifecycleEvents.key("born"), "  "));
        assertThat(DevelopmentalPhaseResolver.resolvePhase(node, java.time.Instant.now()))
                .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

    @Test
    void nodeWithMalformedDateString() {
        var node = new TestNode(java.util.Map.of(LifecycleEvents.key("born"), "not-a-date"));
        assertThat(DevelopmentalPhaseResolver.resolvePhase(node, java.time.Instant.now()))
                .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

    @Test
    void nullNodeDefaultsToAdulthood() {
        assertThat(DevelopmentalPhaseResolver.resolvePhase((MindMapNode) null, java.time.Instant.now()))
                .isEqualTo(DevelopmentalPhaseResolver.ADULTHOOD);
    }

}
