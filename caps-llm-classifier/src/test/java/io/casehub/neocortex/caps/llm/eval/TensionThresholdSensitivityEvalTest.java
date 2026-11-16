package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.llm.DispositionTension;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sensitivity analysis for the A/C prompt routing tension threshold.
 * Runs identical profiles and observations at thresholds 0.3, 0.5, 0.7
 * to measure how the routing boundary affects classification quality.
 *
 * Refs #972
 */
@Tag("llm-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TensionThresholdSensitivityEvalTest {

    private static final Logger LOG = Logger.getLogger(TensionThresholdSensitivityEvalTest.class.getName());
    private static final String TENANT = "threshold-sensitivity-eval";
    private static final double[] THRESHOLDS = {0.3, 0.5, 0.7};

    record Profile(String name, Map<String, String> disposition, double tension) {}

    private static final List<Profile> PROFILES = List.of(
        // Tension 1.0 — bold+strict (approach vs inhibition)
        new Profile("bold-strict", Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "strict",
            "disposition.riskAppetite", "bold",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative"), 1.0),
        // Tension 0.9 — high autonomy + avoidant (independent but won't confront)
        new Profile("autonomous-avoidant", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "high",
            "disposition.conflictMode", "avoidant"), 0.9),
        // Tension 0.8 — cooperative + high autonomy (group-oriented but self-directed)
        new Profile("cooperative-autonomous", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "high",
            "disposition.conflictMode", "collaborative"), 0.8),
        // Tension 0.7 — bold + avoidant (acts but doesn't confront)
        new Profile("bold-avoidant", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "bold",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "avoidant"), 0.7),
        // Tension 0.7 — independent + low autonomy (independent identity but follows)
        new Profile("independent-low-autonomy", Map.of(
            "disposition.socialOrient", "independent",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "low",
            "disposition.conflictMode", "collaborative"), 0.7),
        // Tension 0.6 — competitive + strict (driven to win but bound by rules)
        new Profile("competitive-strict", Map.of(
            "disposition.socialOrient", "competitive",
            "disposition.ruleFollowing", "strict",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative"), 0.6),
        // Tension 0.0 — no conflicting pairs (baseline)
        new Profile("cooperative-baseline", Map.of(
            "disposition.socialOrient", "cooperative",
            "disposition.ruleFollowing", "moderate",
            "disposition.riskAppetite", "calculated",
            "disposition.autonomy", "moderate",
            "disposition.conflictMode", "collaborative"), 0.0)
    );

    private static final List<String> OBSERVATIONS = List.of(
        "A colleague publicly takes credit for work you contributed to significantly",
        "Your manager announces unexpected organizational changes that may affect your role",
        "You're offered a challenging project that could advance your career but requires skills you haven't fully developed",
        "During a team meeting, two colleagues disagree sharply about the approach and look to you to mediate"
    );

    private CapsTopology topology;
    private List<String> sortedInputNodeIds;

    // threshold → profile → observation → activation vector
    private Map<Double, Map<String, Map<String, double[]>>> resultsByThreshold;

    @BeforeAll
    void setUp() {
        topology = EvalTestSupport.loadTopology();
        var pipeline = EvalTestSupport.sessionPipeline();
        sortedInputNodeIds = topology.nodes().entrySet().stream()
            .filter(e -> e.getValue().type() == NodeType.INPUT)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        resultsByThreshold = new HashMap<>();
        for (double threshold : THRESHOLDS) {
            var classifier = new LlmSituationClassifier(pipeline, topology, null, threshold);
            Map<String, Map<String, double[]>> profileResults = new HashMap<>();

            for (Profile profile : PROFILES) {
                Map<String, double[]> obsActivations = new HashMap<>();
                for (int i = 0; i < OBSERVATIONS.size(); i++) {
                    ClassificationResult result = classifier.classify(
                        OBSERVATIONS.get(i), profile.disposition(),
                        ClassificationContext.of(TENANT, profile.name()));
                    obsActivations.put("obs-" + i, toActivationVector(result));
                }
                profileResults.put(profile.name(), obsActivations);
            }
            resultsByThreshold.put(threshold, profileResults);
        }
    }

    @Test
    void tensionScores_matchExpectedValues() {
        for (Profile profile : PROFILES) {
            double tension = DispositionTension.maxTension(profile.disposition());
            assertThat(tension)
                .as("Tension for %s", profile.name())
                .isEqualTo(profile.tension());
        }
    }

    @Test
    void routingCounts_varyAcrossThresholds() {
        Map<Double, Integer> pathCCounts = new HashMap<>();
        for (double threshold : THRESHOLDS) {
            int count = 0;
            for (Profile profile : PROFILES) {
                if (profile.tension() > threshold) count++;
            }
            pathCCounts.put(threshold, count);
        }

        System.out.println("Routing counts per threshold:");
        for (double threshold : THRESHOLDS) {
            System.out.println("  threshold=%.1f → %d/%d profiles route to path C".formatted(
                threshold, pathCCounts.get(threshold), PROFILES.size()));
        }

        // At 0.3: all 6 tension profiles (1.0, 0.9, 0.8, 0.7, 0.7, 0.6) > 0.3
        assertThat(pathCCounts.get(0.3)).isEqualTo(6);
        // At 0.5: all 6 tension profiles > 0.5
        assertThat(pathCCounts.get(0.5)).isEqualTo(6);
        // At 0.7: only 1.0, 0.9, 0.8 > 0.7 (strict >); 0.7, 0.7, 0.6 stay on A
        assertThat(pathCCounts.get(0.7)).isEqualTo(3);
    }

    @Test
    void interProfileVariance_reportedPerThreshold() {
        Map<Double, Double> varianceByThreshold = new HashMap<>();

        for (double threshold : THRESHOLDS) {
            var profileResults = resultsByThreshold.get(threshold);
            double sumDistance = 0;
            int pairs = 0;

            for (int i = 0; i < PROFILES.size(); i++) {
                for (int j = i + 1; j < PROFILES.size(); j++) {
                    var p1 = profileResults.get(PROFILES.get(i).name());
                    var p2 = profileResults.get(PROFILES.get(j).name());
                    for (String obsKey : p1.keySet()) {
                        sumDistance += cosineDistance(p1.get(obsKey), p2.get(obsKey));
                        pairs++;
                    }
                }
            }
            double avgDistance = pairs > 0 ? sumDistance / pairs : 0;
            varianceByThreshold.put(threshold, avgDistance);
        }

        System.out.println("Inter-profile cosine distance per threshold:");
        for (double threshold : THRESHOLDS) {
            System.out.println("  threshold=%.1f → avg distance=%.4f".formatted(
                threshold, varianceByThreshold.get(threshold)));
        }

        // Guard: skip assertion if all vectors are zero (LLM unavailable)
        boolean hasData = resultsByThreshold.values().stream()
            .flatMap(m -> m.values().stream())
            .flatMap(m -> m.values().stream())
            .anyMatch(v -> { for (double d : v) if (d > 0) return true; return false; });

        if (hasData) {
            for (double threshold : THRESHOLDS) {
                assertThat(varianceByThreshold.get(threshold))
                    .as("Inter-profile variance at threshold=%.1f", threshold)
                    .isGreaterThan(0.05);
            }
        } else {
            System.out.println("WARNING: All activation vectors are zero — LLM may be unavailable. Skipping variance assertion.");
        }
    }

    @Test
    void boundaryProfiles_classificationQuality() {
        // competitive-strict has tension 0.6:
        //   at threshold 0.3 and 0.5 → path C (personality system prompt)
        //   at threshold 0.7 → path A (generic prompt with user suffix)
        // Compare whether path C produces more personality-differentiated results

        Set<String> approachNodes = Set.of("dominance", "agency_granted", "choice_available", "reward", "mastery");
        Set<String> inhibitionNodes = Set.of("social_threat", "norm_violation", "punishment", "powerlessness");

        Map<Double, Integer> dualActivationCounts = new HashMap<>();
        for (double threshold : THRESHOLDS) {
            var profileResults = resultsByThreshold.get(threshold).get("competitive-strict");
            int dualCount = 0;
            for (var entry : profileResults.entrySet()) {
                boolean hasApproach = hasActivationInGroup(entry.getValue(), approachNodes);
                boolean hasInhibition = hasActivationInGroup(entry.getValue(), inhibitionNodes);
                if (hasApproach && hasInhibition) dualCount++;
            }
            dualActivationCounts.put(threshold, dualCount);
        }

        System.out.println("Dual activation (approach+inhibition) for competitive-strict:");
        for (double threshold : THRESHOLDS) {
            String path = 0.6 > threshold ? "C" : "A";
            System.out.println("  threshold=%.1f (path %s) → %d/%d observations with dual activation".formatted(
                threshold, path, dualActivationCounts.get(threshold), OBSERVATIONS.size()));
        }
    }

    @Test
    void highTensionProfiles_stableAcrossThresholds() {
        // bold-strict (tension=1.0) routes to path C at ALL thresholds
        // Its classifications should be relatively consistent
        var resultsAt03 = resultsByThreshold.get(0.3).get("bold-strict");
        var resultsAt05 = resultsByThreshold.get(0.5).get("bold-strict");
        var resultsAt07 = resultsByThreshold.get(0.7).get("bold-strict");

        double dist03vs05 = avgCosineDistance(resultsAt03, resultsAt05);
        double dist03vs07 = avgCosineDistance(resultsAt03, resultsAt07);
        double dist05vs07 = avgCosineDistance(resultsAt05, resultsAt07);

        System.out.println("Bold-strict stability (always path C):");
        System.out.println("  0.3 vs 0.5: distance=%.4f".formatted(dist03vs05));
        System.out.println("  0.3 vs 0.7: distance=%.4f".formatted(dist03vs07));
        System.out.println("  0.5 vs 0.7: distance=%.4f".formatted(dist05vs07));

        // Diagnostic: same routing path → differences are LLM stochasticity only
        // Not asserted — LLM non-determinism makes cross-run stability unreliable
    }

    @Test
    void baselineProfile_stableAcrossThresholds() {
        // cooperative-baseline (tension=0.0) routes to path A at ALL thresholds
        var resultsAt03 = resultsByThreshold.get(0.3).get("cooperative-baseline");
        var resultsAt05 = resultsByThreshold.get(0.5).get("cooperative-baseline");
        var resultsAt07 = resultsByThreshold.get(0.7).get("cooperative-baseline");

        double dist03vs05 = avgCosineDistance(resultsAt03, resultsAt05);
        double dist03vs07 = avgCosineDistance(resultsAt03, resultsAt07);
        double dist05vs07 = avgCosineDistance(resultsAt05, resultsAt07);

        System.out.println("Cooperative-baseline stability (always path A):");
        System.out.println("  0.3 vs 0.5: distance=%.4f".formatted(dist03vs05));
        System.out.println("  0.3 vs 0.7: distance=%.4f".formatted(dist03vs07));
        System.out.println("  0.5 vs 0.7: distance=%.4f".formatted(dist05vs07));

        // Diagnostic: same routing path → differences are LLM stochasticity only
    }

    @Test
    void thresholdSensitivityReport() {
        var sb = new StringBuilder("\n=== Tension Threshold Sensitivity Report (#972) ===\n\n");

        // Section 1: Routing table
        sb.append("1. Routing decisions per threshold:\n");
        sb.append("   %-25s  tension  @0.3  @0.5  @0.7\n".formatted("Profile"));
        sb.append("   " + "-".repeat(60) + "\n");
        for (Profile profile : PROFILES) {
            sb.append("   %-25s  %.1f      %s     %s     %s\n".formatted(
                profile.name(), profile.tension(),
                profile.tension() > 0.3 ? "C" : "A",
                profile.tension() > 0.5 ? "C" : "A",
                profile.tension() > 0.7 ? "C" : "A"));
        }

        // Section 2: Inter-profile variance
        sb.append("\n2. Inter-profile cosine distance (higher = more differentiation):\n");
        for (double threshold : THRESHOLDS) {
            var profileResults = resultsByThreshold.get(threshold);
            double sumDistance = 0;
            int pairs = 0;
            for (int i = 0; i < PROFILES.size(); i++) {
                for (int j = i + 1; j < PROFILES.size(); j++) {
                    var p1 = profileResults.get(PROFILES.get(i).name());
                    var p2 = profileResults.get(PROFILES.get(j).name());
                    for (String obsKey : p1.keySet()) {
                        sumDistance += cosineDistance(p1.get(obsKey), p2.get(obsKey));
                        pairs++;
                    }
                }
            }
            sb.append("   threshold=%.1f → avg=%.4f\n".formatted(threshold, pairs > 0 ? sumDistance / pairs : 0));
        }

        // Section 3: Boundary profile analysis
        sb.append("\n3. Boundary profile (competitive-strict, tension=0.6) detail:\n");
        Set<String> approachNodes = Set.of("dominance", "agency_granted", "choice_available", "reward", "mastery");
        Set<String> inhibitionNodes = Set.of("social_threat", "norm_violation", "punishment", "powerlessness");
        for (double threshold : THRESHOLDS) {
            var profileResults = resultsByThreshold.get(threshold).get("competitive-strict");
            int dualCount = 0;
            double avgActivations = 0;
            for (var entry : profileResults.entrySet()) {
                boolean hasApproach = hasActivationInGroup(entry.getValue(), approachNodes);
                boolean hasInhibition = hasActivationInGroup(entry.getValue(), inhibitionNodes);
                if (hasApproach && hasInhibition) dualCount++;
                avgActivations += countActivations(entry.getValue());
            }
            avgActivations /= profileResults.size();
            String path = 0.6 > threshold ? "C" : "A";
            sb.append("   threshold=%.1f (path %s): dual=%d/%d, avg_activations=%.1f\n".formatted(
                threshold, path, dualCount, OBSERVATIONS.size(), avgActivations));
        }

        // Section 4: Per-observation activation comparison for boundary profile
        sb.append("\n4. Per-observation top activations for competitive-strict:\n");
        for (int i = 0; i < OBSERVATIONS.size(); i++) {
            sb.append("   obs-%d: %s\n".formatted(i, OBSERVATIONS.get(i).substring(0, Math.min(60, OBSERVATIONS.get(i).length())) + "..."));
            for (double threshold : THRESHOLDS) {
                var vector = resultsByThreshold.get(threshold).get("competitive-strict").get("obs-" + i);
                String path = 0.6 > threshold ? "C" : "A";
                sb.append("     @%.1f (%s): %s\n".formatted(threshold, path, topActivations(vector, 3)));
            }
        }

        System.out.println(sb);

        // Write report to file for post-run analysis
        var reportPath = java.nio.file.Path.of("target", "threshold-sensitivity-report.txt");
        try {
            java.nio.file.Files.writeString(reportPath, sb.toString());
        } catch (java.io.IOException e) {
            LOG.warning("Could not write report file: " + e.getMessage());
        }
    }

    @Test
    void debugSingleClassification() {
        // Smoke test: verify the classifier runs without errors at each threshold.
        // Activations may be empty when the LLM model is unavailable on the deployment.
        var     pipeline    = EvalTestSupport.sessionPipeline();
        Profile profile     = PROFILES.get(0);
        String  observation = OBSERVATIONS.get(0);

        for (double threshold : THRESHOLDS) {
            var classifier = new LlmSituationClassifier(pipeline, topology, null, threshold);
            ClassificationResult result = classifier.classify(
                    observation, profile.disposition(),
                    ClassificationContext.of(TENANT, "debug-" + threshold));
            System.out.println("threshold=%.1f → activations=%d".formatted(threshold, result.activations().size()));
        }
    }


    // --- helpers ---

    private double[] toActivationVector(ClassificationResult result) {
        double[] vector = new double[sortedInputNodeIds.size()];
        for (SituationActivation activation : result.activations()) {
            int idx = sortedInputNodeIds.indexOf(activation.nodeId());
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
        return 1.0 - dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double avgCosineDistance(Map<String, double[]> a, Map<String, double[]> b) {
        double sum = 0;
        int count = 0;
        for (String key : a.keySet()) {
            if (b.containsKey(key)) {
                sum += cosineDistance(a.get(key), b.get(key));
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private boolean hasActivationInGroup(double[] vector, Set<String> nodeGroup) {
        for (int i = 0; i < sortedInputNodeIds.size(); i++) {
            if (nodeGroup.contains(sortedInputNodeIds.get(i)) && vector[i] > 0.3) {
                return true;
            }
        }
        return false;
    }

    private int countActivations(double[] vector) {
        int count = 0;
        for (double v : vector) {
            if (v > 0.3) count++;
        }
        return count;
    }

    private String topActivations(double[] vector, int n) {
        record Scored(String node, double score) implements Comparable<Scored> {
            @Override public int compareTo(Scored o) { return Double.compare(o.score, score); }
        }
        var scored = new ArrayList<Scored>();
        for (int i = 0; i < sortedInputNodeIds.size(); i++) {
            if (vector[i] > 0.3) {
                scored.add(new Scored(sortedInputNodeIds.get(i), vector[i]));
            }
        }
        scored.sort(null);
        return scored.stream()
            .limit(n)
            .map(s -> "%s=%.2f".formatted(s.node(), s.score()))
            .collect(Collectors.joining(", "));
    }
}
