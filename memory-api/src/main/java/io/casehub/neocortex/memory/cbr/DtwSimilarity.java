package io.casehub.neocortex.memory.cbr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DtwSimilarity {

    private DtwSimilarity() {}

    public static DtwResult compute(List<Map<String, Object>> query,
                                    List<Map<String, Object>> caseSeq,
                                    FeatureField.TimeSeries schema) {
        return compute(query, caseSeq, schema, null);
    }

    public static DtwResult compute(List<Map<String, Object>> query,
                                    List<Map<String, Object>> caseSeq,
                                    FeatureField.TimeSeries schema,
                                    Integer windowSize) {
        int n = query.size();
        int m = caseSeq.size();
        if (n == 0 && m == 0) {return new DtwResult(1.0, List.of());}
        if (n == 0 || m == 0) {return new DtwResult(0.0, List.of());}

        List<FeatureField.Numeric> numericFields = scorableNumericFields(schema);
        int                        w             = windowSize == null ? Math.max(n, m) : Math.max(windowSize, Math.abs(n - m));

        double[][] cost = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                cost[i][j] = Double.MAX_VALUE;
            }
        }
        cost[0][0] = 0.0;

        for (int i = 1; i <= n; i++) {
            int jStart = Math.max(1, i - w);
            int jEnd   = Math.min(m, i + w);
            for (int j = jStart; j <= jEnd; j++) {
                double dist = observationDistance(query.get(i - 1), caseSeq.get(j - 1), numericFields);
                cost[i][j] = dist + Math.min(cost[i - 1][j],
                                             Math.min(cost[i][j - 1], cost[i - 1][j - 1]));
            }
        }

        double dtwDistance = cost[n][m];
        double normalized  = dtwDistance / Math.max(n, m);
        double score       = 1.0 / (1.0 + normalized);

        List<AlignmentPair> path = backtrace(cost, n, m, w);
        return new DtwResult(score, path);
    }

    private static List<AlignmentPair> backtrace(double[][] cost, int n, int m, int w) {
        List<AlignmentPair> path = new ArrayList<>();
        int                 i    = n, j = m;
        while (i > 0 || j > 0) {
            path.add(new AlignmentPair(i - 1, j - 1));
            if (i > 0 && j > 0) {
                double diag = cost[i - 1][j - 1];
                double up   = cost[i - 1][j];
                double left = cost[i][j - 1];
                if (diag <= up && diag <= left) {
                    i--;
                    j--;
                } else if (up <= left) {
                    i--;
                } else {
                    j--;
                }
            } else if (i > 0) {
                i--;
            } else {
                j--;
            }
        }
        return List.copyOf(path.reversed());}

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
            if (range <= 0) {continue;}
            Number aVal = (Number) a.get(f.name());
            Number bVal = (Number) b.get(f.name());
            if (aVal == null || bVal == null) {continue;}
            double diff = (aVal.doubleValue() - bVal.doubleValue()) / range;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq);
    }
}
