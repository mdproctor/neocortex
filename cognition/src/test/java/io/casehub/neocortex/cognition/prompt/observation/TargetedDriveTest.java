package io.casehub.neocortex.cognition.prompt.observation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetedDriveTest {

    @Test
    void targetedDriveCarriesTargetInfo() {
        var drive = new DriveSnapshot("gloating", 0.7, "jaw tightens");
        var targeted = new TargetedDrive(drive, "penelope-pitstop", "Penelope", "prey");

        assertThat(targeted.hasTarget()).isTrue();
        assertThat(targeted.drive().type()).isEqualTo("gloating");
        assertThat(targeted.targetAgentId()).isEqualTo("penelope-pitstop");
        assertThat(targeted.targetDisplayName()).isEqualTo("Penelope");
        assertThat(targeted.role()).isEqualTo("prey");
    }

    @Test
    void untargetedDriveHasNoTarget() {
        var drive = new DriveSnapshot("curiosity", 0.4, "pull behind your eyes");
        var untargeted = TargetedDrive.untargeted(drive);

        assertThat(untargeted.hasTarget()).isFalse();
        assertThat(untargeted.targetAgentId()).isNull();
    }
}