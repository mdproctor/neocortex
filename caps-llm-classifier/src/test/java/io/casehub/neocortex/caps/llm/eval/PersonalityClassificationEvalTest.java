package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.llm.DispositionTension;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalityClassificationEvalTest {

    private static final String TENANT = "personality-eval";

    record Profile(String name, Map<String, String> disposition, double expectedTension, String expectedPath) {}

    private static final List<Profile> PROFILES = List.of(
        new Profile("competitive-assertive", Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "flexible",
            "disposition.riskAppetite", "bold",
            "disposition.autonomy", "high",
            "disposition.conflictMode", "assertive"),
            0.0, "A"),
        new Profile("cooperative-avoidant", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "strict",
            "disposition.riskAppetite", "conservative",
            "disposition.autonomy", "low",
            "disposition.conflictMode", "avoidant"),
            0.0, "A"),
        new Profile("bold-strict", Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "strict",
            "disposition.riskAppetite", "bold",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative"),
            1.0, "C"),
        new Profile("moderate-baseline", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative"),
            0.0, "A")
    );

    private static final List<String> OBSERVATIONS = List.of(
        "A colleague publicly takes credit for work you contributed to significantly",
        "Your manager announces unexpected organizational changes that may affect your role",
        "You're offered a challenging project that could advance your career but requires skills you haven't fully developed",
        "During a team meeting, two colleagues disagree sharply about the approach and look to you to mediate"
    );

    private CapsTopology topology;
    private LlmSituationClassifier classifier;
    private Map<String, Map<String, double[]>> activationsByProfile;

    @BeforeAll
    void setUp() {
        topology = EvalTestSupport.loadTopology();
        var pipeline = EvalTestSupport.sessionPipeline();
        classifier = new LlmSituationClassifier(pipeline, topology, "haiku");

        activationsByProfile = new HashMap<>();
        for (Profile profile : PROFILES) {
            Map<String, double[]> obsActivations = new HashMap<>();
            for (int i = 0; i < OBSERVATIONS.size(); i++) {
                ClassificationResult result = classifier.classify(
                    OBSERVATIONS.get(i), profile.disposition(),
                    ClassificationContext.of(TENANT, profile.name()));
                obsActivations.put("obs-" + i, toActivationVector(result, topology));
            }
            activationsByProfile.put(profile.name(), obsActivations);
        }
    }

    @Test
    void tensionScoring_matchesExpectedRouting() {
        for (Profile profile : PROFILES) {
            double tension = DispositionTension.maxTension(profile.disposition());
            assertThat(tension)
                .as("Tension for %s", profile.name())
                .isEqualTo(profile.expectedTension());
            String path = tension > 0.5 ? "C" : "A";
            assertThat(path)
                .as("Path for %s", profile.name())
                .isEqualTo(profile.expectedPath());
        }
    }

    @Test
    void interProfileVariance_exceedsBaseline() {
        double sumDistance = 0;
        int pairs = 0;
        for (int i = 0; i < PROFILES.size(); i++) {
            for (int j = i + 1; j < PROFILES.size(); j++) {
                for (String obsKey : activationsByProfile.get(PROFILES.get(i).name()).keySet()) {
                    double[] v1 = activationsByProfile.get(PROFILES.get(i).name()).get(obsKey);
                    double[] v2 = activationsByProfile.get(PROFILES.get(j).name()).get(obsKey);
                    sumDistance += cosineDistance(v1, v2);
                    pairs++;
                }
            }
        }
        double avgDistance = pairs > 0 ? sumDistance / pairs : 0;
        assertThat(avgDistance)
            .as("Average inter-profile cosine distance should indicate meaningful differentiation")
            .isGreaterThan(0.05);
    }

    @Test
    void tensionProfile_dualActivation() {
        Map<String, double[]> boldStrictActivations = activationsByProfile.get("bold-strict");
        assertThat(boldStrictActivations).isNotNull();

        Set<String> approachNodes = Set.of("dominance", "agency_granted", "choice_available", "reward", "mastery");
        Set<String> inhibitionNodes = Set.of("social_threat", "norm_violation", "punishment", "powerlessness");

        int observationsWithDualActivation = 0;
        for (var entry : boldStrictActivations.entrySet()) {
            double[] vector = entry.getValue();
            boolean hasApproach = hasActivationInGroup(vector, approachNodes, topology);
            boolean hasInhibition = hasActivationInGroup(vector, inhibitionNodes, topology);
            if (hasApproach && hasInhibition) {
                observationsWithDualActivation++;
            }
        }

        assertThat(observationsWithDualActivation)
            .as("Bold-strict profile should show dual approach+inhibition activation in at least some observations")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void biasDetection_noProfileDominatesSingleCategory() {
        for (Profile profile : PROFILES) {
            Map<String, double[]> obsMap = activationsByProfile.get(profile.name());
            Map<String, Integer> categoryDominance = new HashMap<>();

            for (var entry : obsMap.entrySet()) {
                double[] vector = entry.getValue();
                String dominantCategory = findDominantCategory(vector, topology);
                if (dominantCategory != null) {
                    categoryDominance.merge(dominantCategory, 1, Integer::sum);
                }
            }

            for (var catEntry : categoryDominance.entrySet()) {
                double ratio = (double) catEntry.getValue() / obsMap.size();
                assertThat(ratio)
                    .as("Profile %s should not map >80%% of observations to category %s",
                        profile.name(), catEntry.getKey())
                    .isLessThanOrEqualTo(0.8);
            }
        }
    }

    private static double[] toActivationVector(ClassificationResult result, CapsTopology topology) {
        List<String> nodeIds = topology.nodes().entrySet().stream()
            .filter(e -> e.getValue().type() == io.casehub.neocortex.caps.NodeType.INPUT)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        double[] vector = new double[nodeIds.size()];
        for (SituationActivation activation : result.activations()) {
            int idx = nodeIds.indexOf(activation.nodeId());
            if (idx >= 0) {
                vector[idx] = activation.confidence();
            }
        }
        return vector;
    }

    private static double cosineDistance(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 1.0;
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return 1.0 - similarity;
    }

    private static boolean hasActivationInGroup(double[] vector, Set<String> nodeGroup, CapsTopology topology) {
        List<String> nodeIds = topology.nodes().entrySet().stream()
            .filter(e -> e.getValue().type() == io.casehub.neocortex.caps.NodeType.INPUT)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        for (int i = 0; i < nodeIds.size(); i++) {
            if (nodeGroup.contains(nodeIds.get(i)) && vector[i] > 0.3) {
                return true;
            }
        }
        return false;
    }

    private static String findDominantCategory(double[] vector, CapsTopology topology) {
        List<String> nodeIds = topology.nodes().entrySet().stream()
            .filter(e -> e.getValue().type() == io.casehub.neocortex.caps.NodeType.INPUT)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        String maxNode = null;
        double maxVal = 0;
        for (int i = 0; i < vector.length; i++) {
            if (vector[i] > maxVal) {
                maxVal = vector[i];
                maxNode = nodeIds.get(i);
            }
        }
        if (maxNode == null) return null;
        return topology.nodes().get(maxNode).category();
    }
}
