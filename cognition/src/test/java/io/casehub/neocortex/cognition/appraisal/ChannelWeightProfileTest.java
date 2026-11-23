package io.casehub.neocortex.cognition.appraisal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.data.Offset;

class ChannelWeightProfileTest {

    @Test
    void defaultProfileHasEqualWeights() {
        var profile = ChannelWeightProfile.DEFAULT;
        assertThat(profile.somatic()).isEqualTo(0.25);
        assertThat(profile.attentional()).isEqualTo(0.25);
        assertThat(profile.temporal()).isEqualTo(0.25);
        assertThat(profile.actionTendency()).isEqualTo(0.25);
        assertThat(profile.isDefault()).isTrue();
    }

    @Test
    void rejectsNegativeWeights() {
        assertThatThrownBy(() -> new ChannelWeightProfile(-0.1, 0.4, 0.4, 0.3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new ChannelWeightProfile(0.5, 0.5, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void dominantChannelReturnsSomatic() {
        var profile = new ChannelWeightProfile(0.40, 0.20, 0.20, 0.20);
        assertThat(profile.dominantChannel()).isEqualTo("somatic");
    }

    @Test
    void dominantChannelReturnsActionTendency() {
        var profile = new ChannelWeightProfile(0.15, 0.10, 0.15, 0.60);
        assertThat(profile.dominantChannel()).isEqualTo("action-tendency");
    }

    @Test
    void blendMixesTwoProfiles() {
        var a = new ChannelWeightProfile(0.40, 0.20, 0.20, 0.20);
        var b = new ChannelWeightProfile(0.20, 0.40, 0.20, 0.20);

        var blended = a.blend(b, 0.5);
        assertThat(blended.somatic()).isCloseTo(0.30, Offset.offset(0.01));
        assertThat(blended.attentional()).isCloseTo(0.30, Offset.offset(0.01));
    }

    @Test
    void blendWithZeroReturnsOriginal() {
        var a = new ChannelWeightProfile(0.40, 0.20, 0.20, 0.20);
        var b = ChannelWeightProfile.DEFAULT;

        var blended = a.blend(b, 0.0);
        assertThat(blended.somatic()).isCloseTo(0.40, Offset.offset(0.001));
    }

    @Test
    void isDefaultDetectsNonDefaultProfile() {
        var profile = new ChannelWeightProfile(0.35, 0.15, 0.15, 0.35);
        assertThat(profile.isDefault()).isFalse();
    }
}
