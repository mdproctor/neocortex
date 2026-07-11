package io.casehub.neocortex.memory.cbr;

import java.util.List;

public final class EditDistanceSimilarity {

    private EditDistanceSimilarity() {}

    public static double compute(List<String> query, List<String> caseSeq) {
        int n = query.size();
        int m = caseSeq.size();
        if (n == 0 && m == 0) return 1.0;
        if (n == 0 || m == 0) return 0.0;

        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = query.get(i - 1).equals(caseSeq.get(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                            Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }

        int editDistance = dp[n][m];
        return 1.0 - ((double) editDistance / Math.max(n, m));
    }
}
