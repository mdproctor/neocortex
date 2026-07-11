package io.casehub.neocortex.memory.cbr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DtwSimilarity {

    private DtwSimilarity() {}

    public static double compute(List<Map<String, Object>> query,
                                  List<Map<String, Object>> caseSeq,
                                  FeatureField.TimeSeries schema) {
        int n = query.size();
        int m = caseSeq.size();
        if (n == 0 && m == 0) return 1.0;
        if (n == 0 || m == 0) return 0.0;

        List<FeatureField.Numeric> numericFields = scorableNumericFields(schema);

        double[][] cost = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) cost[i][0] = Double.MAX_VALUE;
        for (int j = 0; j <= m; j++) cost[0][j] = Double.MAX_VALUE;
        cost[0][0] = 0.0;

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                double dist = observationDistance(query.get(i - 1), caseSeq.get(j - 1), numericFields);
                cost[i][j] = dist + Math.min(cost[i - 1][j],
                                     Math.min(cost[i][j - 1], cost[i - 1][j - 1]));
            }
        }

        double dtwDistance = cost[n][m];
        double normalized = dtwDistance / Math.max(n, m);
        return 1.0 / (1.0 + normalized);
    }

    static List<FeatureField.Numeric> scorableNumericFields(FeatureField.TimeSeries schema) {
        List<FeatureField.Numeric> result = new ArrayList<>();
        for (FeatureField f : schema.innerFields()) {
            if (f instanceof FeatureField.Numeric num && !f.name().equals(schema.timestampField())) {
                result.add(num);
            }
        }
        return result;
    }

    private static double observationDistance(Map<String, Object> a, Map<String, Object> b,
                                              List<FeatureField.Numeric> fields) {
        double sumSq = 0.0;
        for (FeatureField.Numeric f : fields) {
            double range = f.max() - f.min();
            if (range <= 0) continue;
            Number aVal = (Number) a.get(f.name());
            Number bVal = (Number) b.get(f.name());
            if (aVal == null || bVal == null) continue;
            double diff = (aVal.doubleValue() - bVal.doubleValue()) / range;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq);
    }
}
