package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.caps.SituationActivation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps CAPS situation activations to channel weight profiles.
 *
 * <p>Grounded in three literature sources:
 * <ul>
 *   <li>Easterbrook (1959): emotional arousal narrows the attentional field —
 *       threat situations reduce attentional channel weight</li>
 *   <li>Barrett (2017): predictive coding constructs temporal experience —
 *       contemplative situations elevate temporal channel</li>
 *   <li>Frijda (1986): emotions are action tendencies —
 *       threat and autonomy situations elevate action tendency channel</li>
 * </ul>
 */
public final class TopologyChannelWeighter {

    private TopologyChannelWeighter() {}

    private static final Map<String, ChannelWeightProfile> CATEGORY_PROFILES = Map.of(
            "threat",      new ChannelWeightProfile(0.35, 0.10, 0.15, 0.40),
            "relationship",new ChannelWeightProfile(0.25, 0.30, 0.25, 0.20),
            "achievement", new ChannelWeightProfile(0.15, 0.30, 0.25, 0.30),
            "social",      new ChannelWeightProfile(0.20, 0.35, 0.15, 0.30),
            "resource",    new ChannelWeightProfile(0.15, 0.25, 0.30, 0.30),
            "autonomy",    new ChannelWeightProfile(0.25, 0.15, 0.20, 0.40),
            "consequence", new ChannelWeightProfile(0.20, 0.20, 0.30, 0.30)
    );

    private static final Map<String, String> NODE_TO_CATEGORY = buildNodeCategoryMap();
    private static final ChannelWeightProfile ATTENDING_PROFILE =
            new ChannelWeightProfile(0.15, 0.45, 0.15, 0.25);


    private static Map<String, String> buildNodeCategoryMap() {
        return Map.ofEntries(
                Map.entry("secure_attachment", "relationship"),
                Map.entry("inconsistent_care", "relationship"),
                Map.entry("rejection", "relationship"),
                Map.entry("betrayal", "relationship"),
                Map.entry("abandonment", "relationship"),
                Map.entry("neglect", "relationship"),
                Map.entry("physical_threat", "threat"),
                Map.entry("social_threat", "threat"),
                Map.entry("psychological_threat", "threat"),
                Map.entry("unpredictable_danger", "threat"),
                Map.entry("mastery", "achievement"),
                Map.entry("failure", "achievement"),
                Map.entry("performance_judged", "achievement"),
                Map.entry("competence_recognition", "achievement"),
                Map.entry("acceptance", "social"),
                Map.entry("exclusion", "social"),
                Map.entry("dominance", "social"),
                Map.entry("submission", "social"),
                Map.entry("modeling_observed", "social"),
                Map.entry("scarcity", "resource"),
                Map.entry("abundance", "resource"),
                Map.entry("competition", "resource"),
                Map.entry("sharing", "resource"),
                Map.entry("agency_granted", "autonomy"),
                Map.entry("controlled", "autonomy"),
                Map.entry("choice_available", "autonomy"),
                Map.entry("powerlessness", "autonomy"),
                Map.entry("reward", "consequence"),
                Map.entry("punishment", "consequence"),
                Map.entry("reward_removal", "consequence"),
                Map.entry("aversive_removal", "consequence")
        );
    }

    public static ChannelWeightProfile weight(List<SituationActivation> activations) {
        if (activations == null || activations.isEmpty()) {
            return ChannelWeightProfile.DEFAULT;
        }

        double totalConfidence = 0;
        double somatic = 0, attentional = 0, temporal = 0, actionTendency = 0;

        for (var activation : activations) {
            String category = NODE_TO_CATEGORY.get(activation.nodeId());
            if (category == null) continue;

            ChannelWeightProfile profile = CATEGORY_PROFILES.get(category);
            if (profile == null) continue;

            double conf = activation.confidence();
            totalConfidence += conf;
            somatic        += profile.somatic() * conf;
            attentional    += profile.attentional() * conf;
            temporal       += profile.temporal() * conf;
            actionTendency += profile.actionTendency() * conf;
        }

        if (totalConfidence <= 0) {
            return ChannelWeightProfile.DEFAULT;
        }

        somatic        /= totalConfidence;
        attentional    /= totalConfidence;
        temporal       /= totalConfidence;
        actionTendency /= totalConfidence;

        double sum = somatic + attentional + temporal + actionTendency;
        if (sum <= 0) return ChannelWeightProfile.DEFAULT;

        return new ChannelWeightProfile(
                somatic / sum,
                attentional / sum,
                temporal / sum,
                actionTendency / sum
        );
    }

    public static ChannelWeightProfile weight(List<SituationActivation> activations,
                                              List<ActionTendency> actionTendencies) {
        ChannelWeightProfile base = weight(activations);
        if (actionTendencies == null || actionTendencies.isEmpty()) {
            return base;
        }
        double attendingIntensity = 0.0;
        for (var at : actionTendencies) {
            if (at.readiness() == ActionReadiness.ATTENDING && at.intensity() > attendingIntensity) {
                attendingIntensity = at.intensity();
            }
        }
        if (attendingIntensity <= 0.3) {
            return base;
        }
        return base.blend(ATTENDING_PROFILE, attendingIntensity * 0.4);
    }


    public static Set<String> knownCategories() {
        return CATEGORY_PROFILES.keySet();
    }

    static ChannelWeightProfile profileForCategory(String category) {
        return CATEGORY_PROFILES.getOrDefault(category, ChannelWeightProfile.DEFAULT);
    }
}
