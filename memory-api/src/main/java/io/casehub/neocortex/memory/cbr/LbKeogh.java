package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;

public final class LbKeogh {

    private LbKeogh() {}

    public record Envelope(double[][] upper, double[][] lower, int length, int dimensions) {}

    public static Envelope computeEnvelope(List<Map<String, FeatureValue>> sequence,
                                            FeatureField.TimeSeries schema,
                                            int windowSize) {
        int m = sequence.size();
        if (m == 0) return new Envelope(new double[0][], new double[0][], 0, 0);

        List<FeatureField.Numeric> fields = DtwSimilarity.scorableNumericFields(schema);
        int D = fields.size();

        double[][] upper = new double[m][D];
        double[][] lower = new double[m][D];

        for (int i = 0; i < m; i++) {
            int wStart = Math.max(0, i - windowSize);
            int wEnd = Math.min(m - 1, i + windowSize);
            for (int d = 0; d < D; d++) {
                double range = fields.get(d).max() - fields.get(d).min();
                if (range <= 0) { upper[i][d] = 0; lower[i][d] = 0; continue; }
                double maxVal = Double.NEGATIVE_INFINITY;
                double minVal = Double.POSITIVE_INFINITY;
                for (int j = wStart; j <= wEnd; j++) {
                    FeatureValue fv = sequence.get(j).get(fields.get(d).name());
                    if (fv instanceof FeatureValue.NumberVal nv) {
                        double norm = nv.value() / range;
                        maxVal = Math.max(maxVal, norm);
                        minVal = Math.min(minVal, norm);
                    }
                }
                upper[i][d] = maxVal;
                lower[i][d] = minVal;
            }
        }
        return new Envelope(upper, lower, m, D);
    }

    public static double lowerBound(List<Map<String, FeatureValue>> query,
                                     Envelope caseEnvelope,
                                     FeatureField.TimeSeries schema) {
        if (query.isEmpty() || caseEnvelope.length() == 0) return 0.0;

        List<FeatureField.Numeric> fields = DtwSimilarity.scorableNumericFields(schema);
        int D = fields.size();
        int overlap = Math.min(query.size(), caseEnvelope.length());
        double lb = 0.0;

        for (int i = 0; i < overlap; i++) {
            double sumSq = 0.0;
            for (int d = 0; d < D; d++) {
                double range = fields.get(d).max() - fields.get(d).min();
                if (range <= 0) continue;
                FeatureValue fv = query.get(i).get(fields.get(d).name());
                if (fv instanceof FeatureValue.NumberVal nv) {
                    double qNorm = nv.value() / range;
                    if (qNorm > caseEnvelope.upper()[i][d]) {
                        double diff = qNorm - caseEnvelope.upper()[i][d];
                        sumSq += diff * diff;
                    } else if (qNorm < caseEnvelope.lower()[i][d]) {
                        double diff = caseEnvelope.lower()[i][d] - qNorm;
                        sumSq += diff * diff;
                    }
                }
            }
            lb += Math.sqrt(sumSq);
        }
        return lb;
    }
}
