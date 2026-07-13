package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.ExplanationRenderer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.stream.Collectors;

@DefaultBean
@ApplicationScoped
public class DefaultExplanationRenderer implements ExplanationRenderer {

    @Override
    public String render(CbrRetrievalTrace trace) {
        int count = trace.results().size();
        var sb = new StringBuilder();
        sb.append("Retrieved ").append(count).append(count == 1 ? " case" : " cases");
        sb.append(" (mode: ").append(trace.query().retrievalMode());
        sb.append(", caseType: ").append(trace.query().caseType()).append(").");

        if (!trace.results().isEmpty()) {
            var top = trace.results().getFirst();
            sb.append("\nTop match:");
            if (top.caseId() != null) {
                sb.append(" caseId=").append(top.caseId()).append(",");
            }
            sb.append(" score=").append(String.format("%.2f", top.score()));
            if (top.confidence() != null) {
                sb.append(", confidence=").append(String.format("%.2f", top.confidence()));
            }
            sb.append(".");
            if (!top.featureSimilarities().isEmpty()) {
                String breakdown = top.featureSimilarities().entrySet().stream()
                        .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                        .map(e -> e.getKey() + "=" + String.format("%.2f", e.getValue()))
                        .collect(Collectors.joining(", "));
                sb.append("\nFeature breakdown: ").append(breakdown).append(".");
            }
        }
        return sb.toString();
    }
}
