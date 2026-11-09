package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.cognition.drive.CuriosityChannel;

import java.util.List;

/**
 * Derives four-channel curiosity state from gap data and personality.
 * <p>
 * Channel derivation:
 * <ul>
 *   <li><b>Hedonic (Litman I/D):</b> from deprivationBias — openness pushes I-type,
 *       neuroticism pushes D-type. Derived externally from personality facets.</li>
 *   <li><b>Comprehension (Silvia):</b> mean confidence of top gaps. High = interest,
 *       low = confusion.</li>
 *   <li><b>Focus (Berlyne):</b> gap distribution entropy. One dominant gap = specific,
 *       many similar = diversive.</li>
 *   <li><b>Gap proximity (Loewenstein):</b> mean entropy of top gaps, non-linear
 *       with peak at ~0.5 confidence.</li>
 * </ul>
 */
public class CuriosityChannelComputer {

    // Berlyne (1960) specific→diversive transition. Calibrated empirically.
    private static final double DIVERSIVE_ENTROPY_THRESHOLD = 0.6;
    // Litman (2005) I-type→blended boundary. Calibrated empirically.
    private static final double BLENDED_LOW = 0.3;
    // Litman (2005) blended→D-type boundary. Calibrated empirically.
    private static final double BLENDED_HIGH = 0.7;

    /**
     * Compute four-channel curiosity state from gap scores.
     *
     * @param gaps             information gap scores from InformationGapDetector (sorted by intensity descending)
     * @param deprivationBias  personality-derived D-type bias in [0,1] where 0=pure I-type, 1=pure D-type
     * @return four-channel curiosity state, or null if no gaps
     */
    public CuriosityChannel compute(List<InformationGapScore> gaps, double deprivationBias) {
        if (gaps == null || gaps.isEmpty()) return null;

        var topGaps = gaps.stream().limit(5).toList();

        CuriosityChannel.HedonicType hedonic = deriveHedonic(deprivationBias);
        double comprehension = deriveComprehension(topGaps);
        CuriosityChannel.FocusType focus = deriveFocus(topGaps);
        double gapProximity = deriveGapProximity(topGaps);

        return new CuriosityChannel(hedonic, comprehension, focus, gapProximity);
    }

    private CuriosityChannel.HedonicType deriveHedonic(double deprivationBias) {
        if (deprivationBias <= BLENDED_LOW) return CuriosityChannel.HedonicType.I_TYPE;
        if (deprivationBias >= BLENDED_HIGH) return CuriosityChannel.HedonicType.D_TYPE;
        return CuriosityChannel.HedonicType.BLENDED;
    }

    /**
     * Silvia (2006) appraisal: mean confidence of gaps. Higher confidence = higher comprehension
     * (the agent grasps enough to be interested, not overwhelmed).
     */
    private double deriveComprehension(List<InformationGapScore> gaps) {
        return gaps.stream()
                .mapToDouble(InformationGapScore::confidence)
                .average().orElse(0.5);
    }

    /**
     * Berlyne (1960) specific/diversive: normalized entropy of gap intensity distribution.
     * One dominant gap = specific focus. Many similar-intensity gaps = diversive.
     */
    private CuriosityChannel.FocusType deriveFocus(List<InformationGapScore> gaps) {
        if (gaps.size() <= 1) return CuriosityChannel.FocusType.SPECIFIC;

        double totalIntensity = gaps.stream().mapToDouble(InformationGapScore::gapIntensity).sum();
        if (totalIntensity <= 0.0) return CuriosityChannel.FocusType.SPECIFIC;

        double entropy = 0.0;
        for (var gap : gaps) {
            double p = gap.gapIntensity() / totalIntensity;
            if (p > 0.0) entropy -= p * Math.log(p);
        }
        double maxEntropy = Math.log(gaps.size());
        double normalizedEntropy = maxEntropy > 0 ? entropy / maxEntropy : 0.0;

        return normalizedEntropy >= DIVERSIVE_ENTROPY_THRESHOLD
                ? CuriosityChannel.FocusType.DIVERSIVE
                : CuriosityChannel.FocusType.SPECIFIC;
    }

    /**
     * Loewenstein (1994) gap proximity: mean entropy of gaps. Binary entropy peaks at
     * confidence 0.5 — the partial-knowledge sweet spot.
     */
    private double deriveGapProximity(List<InformationGapScore> gaps) {
        return Math.min(1.0, gaps.stream()
                .mapToDouble(InformationGapScore::entropy)
                .average().orElse(0.0));
    }
}
