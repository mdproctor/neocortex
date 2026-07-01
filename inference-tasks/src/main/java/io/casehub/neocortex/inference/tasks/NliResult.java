package io.casehub.neocortex.inference.tasks;

import java.util.Map;

import static io.casehub.neocortex.inference.tasks.NliLabel.CONTRADICTION;
import static io.casehub.neocortex.inference.tasks.NliLabel.ENTAILMENT;
import static io.casehub.neocortex.inference.tasks.NliLabel.NEUTRAL;

public record NliResult(float entailment, float neutral, float contradiction) {

    public NliLabel predicted() {
        if (entailment >= neutral && entailment >= contradiction) return ENTAILMENT;
        if (neutral >= contradiction) return NEUTRAL;
        return CONTRADICTION;
    }

    public Map<NliLabel, Float> scores() {
        return Map.of(ENTAILMENT, entailment, NEUTRAL, neutral, CONTRADICTION, contradiction);
    }
}
