package io.casehub.neocortex.cognition.appraisal;

import java.util.ArrayList;

public class SchererAppraisalStrategy implements AppraisalStrategy {

    private final SecCheck relevanceCheck;
    private final SecCheck implicationCheck;
    private final SecCheck copingCheck;
    private final SecCheck normativeCheck;
    private final SchererAppraisalConfig secConfig;

    public SchererAppraisalStrategy(
            SecCheck relevanceCheck,
            SecCheck implicationCheck,
            SecCheck copingCheck,
            SecCheck normativeCheck,
            SchererAppraisalConfig secConfig) {
        this.relevanceCheck = relevanceCheck;
        this.implicationCheck = implicationCheck;
        this.copingCheck = copingCheck;
        this.normativeCheck = normativeCheck;
        this.secConfig = secConfig;
    }

    @Override
    public AppraisalResult appraise(AppraisalContext context) {
        var results = new ArrayList<SecResult>();

        if (secConfig.relevanceEnabled() && relevanceCheck != null) {
            results.add(relevanceCheck.evaluate(context));
        }
        if (secConfig.implicationsEnabled() && implicationCheck != null) {
            results.add(implicationCheck.evaluate(context));
        }
        if (secConfig.copingEnabled() && copingCheck != null) {
            results.add(copingCheck.evaluate(context));
        }
        if (secConfig.normativeEnabled() && normativeCheck != null) {
            results.add(normativeCheck.evaluate(context));
        }

        if (results.isEmpty()) return AppraisalResult.empty();

        String subjectId = context.situation().narrative();
        var emotions = EmotionMapper.mapEmotions(results, subjectId);
        var tendencies = EmotionMapper.mapTendencies(results);

        var dims = EmotionMapper.mergeDimensions(results);
        double novelty = dims.getOrDefault(SecDimensions.NOVELTY, 1.0);
        var hash = Integer.toHexString(subjectId.hashCode());
        var habituation = context.habituation() != null
                ? context.habituation().withObservation(hash, novelty)
                : HabituationState.empty().withObservation(hash, novelty);

        return new AppraisalResult(emotions, tendencies, habituation, null, dims);
    }
}
