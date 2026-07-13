package io.casehub.neocortex.memory.cbr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DtwSimilarity {

    private DtwSimilarity() {}

    public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                    List<Map<String, FeatureValue>> caseSeq,
                                    FeatureField.TimeSeries schema) {
        return compute(query, caseSeq, schema, new WarpingConstraint.Unconstrained());
    }

    public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                    List<Map<String, FeatureValue>> caseSeq,
                                    FeatureField.TimeSeries schema,
                                    WarpingConstraint constraint) {
        return compute(query, caseSeq, schema, constraint, Double.POSITIVE_INFINITY);
    }

    public static DtwResult compute(List<Map<String, FeatureValue>> query,
                                    List<Map<String, FeatureValue>> caseSeq,
                                    FeatureField.TimeSeries schema,
                                    WarpingConstraint constraint,
                                    double abandonCostThreshold) {
        int n = query.size();
        int m = caseSeq.size();
        if (n == 0 && m == 0) {return new DtwResult(1.0, List.of());}
        if (n == 0 || m == 0) {return new DtwResult(0.0, List.of());}

        List<FeatureField.Numeric> numericFields = scorableNumericFields(schema);

        double[][] cost = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                cost[i][j] = Double.MAX_VALUE;
            }
        }
        cost[0][0] = 0.0;

        for (int i = 1; i <= n; i++) {
            int jStart = computeJStart(i, n, m, constraint);
            int jEnd   = computeJEnd(i, n, m, constraint);
            if (jStart > jEnd) {return new DtwResult(0.0, List.of());}

            double rowMin = Double.MAX_VALUE;
            for (int j = jStart; j <= jEnd; j++) {
                double dist = observationDistance(query.get(i - 1), caseSeq.get(j - 1), numericFields);
                cost[i][j] = dist + Math.min(cost[i - 1][j],
                                             Math.min(cost[i][j - 1], cost[i - 1][j - 1]));
                rowMin     = Math.min(rowMin, cost[i][j]);
            }

            if (rowMin > abandonCostThreshold) {
                return new DtwResult(0.0, List.of());
            }
        }

        double dtwDistance = cost[n][m];
        double normalized  = dtwDistance / Math.max(n, m);
        double score       = 1.0 / (1.0 + normalized);

        List<AlignmentPair> path = backtrace(cost, n, m, constraint);
        return new DtwResult(score, path);
    }


    private static List<AlignmentPair> backtrace(double[][] cost, int n, int m,
                                                 WarpingConstraint constraint) {
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
        return List.copyOf(path.reversed());
    }

    private static int computeJStart(int i, int n, int m, WarpingConstraint constraint) {
        return switch (constraint) {
            case WarpingConstraint.Unconstrained u -> 1;
            case WarpingConstraint.SakoeChibaBand sc -> {
                int w = Math.max(sc.windowSize(), Math.abs(n - m));
                yield Math.max(1, i - w);
            }
            case WarpingConstraint.ItakuraParallelogram ip -> {
                double s          = ip.maxSlope();
                int    fromOrigin = (int) Math.ceil(i / s);
                int    fromEnd    = (int) Math.ceil(m - s * (n - i));
                yield Math.max(1, Math.max(fromOrigin, fromEnd));
            }
        };
    }

    private static int computeJEnd(int i, int n, int m, WarpingConstraint constraint) {
        return switch (constraint) {
            case WarpingConstraint.Unconstrained u -> m;
            case WarpingConstraint.SakoeChibaBand sc -> {
                int w = Math.max(sc.windowSize(), Math.abs(n - m));
                yield Math.min(m, i + w);
            }
            case WarpingConstraint.ItakuraParallelogram ip -> {
                double s          = ip.maxSlope();
                int    fromOrigin = (int) Math.floor(s * i);
                int    fromEnd    = (int) Math.floor(m - (n - i) / s);
                yield Math.min(m, Math.min(fromOrigin, fromEnd));
            }
        };
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

    private static double observationDistance(Map<String, FeatureValue> a, Map<String, FeatureValue> b,
                                              List<FeatureField.Numeric> fields) {
        double sumSq = 0.0;
        for (FeatureField.Numeric f : fields) {
            double range = f.max() - f.min();
            if (range <= 0) {continue;}
            FeatureValue aVal = a.get(f.name());
            FeatureValue bVal = b.get(f.name());
            if (aVal instanceof FeatureValue.NumberVal aN && bVal instanceof FeatureValue.NumberVal bN) {
                double diff = (aN.value() - bN.value()) / range;
                sumSq += diff * diff;
            }
        }
        return Math.sqrt(sumSq);
    }
}
