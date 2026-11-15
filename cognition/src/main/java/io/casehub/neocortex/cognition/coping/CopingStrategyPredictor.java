/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognition.appraisal.SecDimensions;
import io.casehub.neocortex.cognition.regulation.CognitiveLoadLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Controllability-first coping strategy prediction using EMA model,
 * enriched with regulation-pipeline intelligence migrated from
 * RegulationStrategyPredictor (cognitive load gate, mild distress
 * override, self-conscious emotion refinement).
 *
 * Connor-Smith & Flachsbart (2007): personality bias resolution.
 * Nathanson (1992): self-conscious emotion compass-of-shame.
 * Marsella & Gratch (2009): EMA controllability-based filtering.
 */
public class CopingStrategyPredictor {

    static final double HIGH_CONTROLLABILITY = 0.6;
    static final double LOW_CONTROLLABILITY = 0.3;
    static final double MILD_DISTRESS_THRESHOLD = 0.40;
    static final double AVOIDANCE_TENDENCY_THRESHOLD = 0.6;
    static final double HIGH_CONFIDENCE = 0.75;
    static final double FAMILY_CONFIDENCE = 0.55;

    private static final Set<CopingStrategy> LOW_COST_STRATEGIES = EnumSet.of(
            CopingStrategy.SUPPRESSION, CopingStrategy.AVOIDANCE,
            CopingStrategy.MENTAL_DISENGAGEMENT, CopingStrategy.RESIGNATION);

    private static final Set<EmotionType> SELF_CONSCIOUS = EnumSet.of(
            EmotionType.SHAME, EmotionType.REMORSE);

    private static final Set<EmotionType> UNCONTROLLABLE_EMOTIONS = EnumSet.of(
            EmotionType.FEAR, EmotionType.DISTRESS, EmotionType.FEARS_CONFIRMED);

    private static final Map<CopingStrategy, String> STRATEGY_TO_DIMENSION;
    private static final Map<String, List<CopingStrategy>> DIMENSION_STRATEGIES;

    static {
        STRATEGY_TO_DIMENSION = new EnumMap<>(CopingStrategy.class);
        STRATEGY_TO_DIMENSION.put(CopingStrategy.PLANNING, "planful");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.ACTIVE_CONFRONTATION, "planful");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SEEK_INFORMATION, "reinterpretation");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.POSITIVE_REINTERPRETATION, "reinterpretation");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.AVOIDANCE, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.MENTAL_DISENGAGEMENT, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.DENIAL, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.WISHFUL_THINKING, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SUPPRESS_INFORMATION, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SUPPRESSION, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.PROCRASTINATION, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.RESIGNATION, "avoidance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SEEK_INSTRUMENTAL_SUPPORT, "support");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SEEK_EMOTIONAL_SUPPORT, "support");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.ACCEPTANCE, "acceptance");
        STRATEGY_TO_DIMENSION.put(CopingStrategy.SHIFT_RESPONSIBILITY, "avoidance");

        var mutable = new java.util.LinkedHashMap<String, List<CopingStrategy>>();
        STRATEGY_TO_DIMENSION.forEach((s, d) ->
                mutable.computeIfAbsent(d, k -> new ArrayList<>()).add(s));
        mutable.replaceAll((k, v) -> List.copyOf(v));
        DIMENSION_STRATEGIES = Map.copyOf(mutable);
    }

    public CopingStrategyPredictor() {}

    public Optional<CopingPrediction> predict(
            @Nullable CopingDefaults defaults,
            Map<String, Double> secDimensions,
            List<CognitiveEmotion> emotions,
            @Nullable DispositionAxes axes,
            @Nullable CognitiveLoadLevel cognitiveLoad) {

        if (defaults == null || emotions.isEmpty()) {
            return Optional.empty();
        }

        CognitiveEmotion dominant = dominantEmotion(emotions);
        double controllability = secDimensions.getOrDefault(SecDimensions.CONTROLLABILITY, 0.5);

        var viable = EnumSet.allOf(CopingStrategy.class);

        // Stage 1: Cognitive load gate
        if (cognitiveLoad == CognitiveLoadLevel.HIGH) {
            viable.retainAll(LOW_COST_STRATEGIES);
        }

        // Stage 2: Controllability-based filtering
        boolean isUncontrollable = controllability < LOW_CONTROLLABILITY;
        boolean isControllable = controllability > HIGH_CONTROLLABILITY;
        boolean mildDistress = dominant.intensity() < MILD_DISTRESS_THRESHOLD
                && UNCONTROLLABLE_EMOTIONS.contains(dominant.type());

        // Stage 3: Mild distress override — skip controllability narrowing
        if (!mildDistress) {
            if (isControllable) {
                viable.removeIf(s -> s.category() != CopingCategory.INTENTION);
            } else if (isUncontrollable) {
                viable.removeIf(s -> s.category() != CopingCategory.BELIEF
                        && s.category() != CopingCategory.DESIRE);
            }
        }

        if (viable.isEmpty()) {
            return Optional.empty();
        }

        // Stage 4: Self-conscious emotion refinement (Nathanson 1992)
        if (SELF_CONSCIOUS.contains(dominant.type()) && viable.contains(CopingStrategy.AVOIDANCE)) {
            boolean avoidantPersonality = defaults.avoidanceTendency() > AVOIDANCE_TENDENCY_THRESHOLD;
            boolean avoidantConflict = axes != null && "avoidant".equals(axes.conflictMode());
            if (avoidantPersonality || avoidantConflict) {
                return Optional.of(new CopingPrediction(
                        CopingStrategy.AVOIDANCE, HIGH_CONFIDENCE, null));
            }
        }

        // Stage 5: Personality bias resolution
        return resolveFromPersonality(defaults, viable);
    }

    private Optional<CopingPrediction> resolveFromPersonality(
            CopingDefaults defaults, EnumSet<CopingStrategy> viable) {

        record DimensionScore(String dimension, double score, List<CopingStrategy> strategies) {}

        Map<String, Double> dimensionScores = Map.of(
                "planful", defaults.planfulTendency(),
                "reinterpretation", defaults.reinterpretationTendency(),
                "avoidance", defaults.avoidanceTendency(),
                "support", defaults.supportSeekingTendency(),
                "acceptance", defaults.acceptanceTendency());

        List<DimensionScore> candidates = new ArrayList<>();
        for (var entry : DIMENSION_STRATEGIES.entrySet()) {
            String dim = entry.getKey();
            List<CopingStrategy> viableInDim = entry.getValue().stream()
                    .filter(viable::contains).toList();
            if (!viableInDim.isEmpty()) {
                candidates.add(new DimensionScore(dim,
                        dimensionScores.getOrDefault(dim, 0.5), viableInDim));
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        DimensionScore best = candidates.getFirst();

        if (best.strategies.size() == 1) {
            return Optional.of(new CopingPrediction(
                    best.strategies.getFirst(), HIGH_CONFIDENCE, null));
        }

        return Optional.of(new CopingPrediction(
                best.strategies.getFirst(), FAMILY_CONFIDENCE, best.dimension));
    }

    private static CognitiveEmotion dominantEmotion(List<CognitiveEmotion> emotions) {
        return emotions.stream()
                .max((a, b) -> Double.compare(a.intensity(), b.intensity()))
                .orElseThrow();
    }
}
