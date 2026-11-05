package io.casehub.neocortex.corpus.management.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "casehub.corpus-management")
public class CorpusManagementProperties {

    private final Sqlite sqlite = new Sqlite();
    private final Federation federation = new Federation();

    public Sqlite getSqlite() { return sqlite; }
    public Federation getFederation() { return federation; }

    public static class Sqlite {
        private String path;
        private int maxPoolSize = 5;
        private int busyTimeoutMs = 5000;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getBusyTimeoutMs() { return busyTimeoutMs; }
        public void setBusyTimeoutMs(int busyTimeoutMs) { this.busyTimeoutMs = busyTimeoutMs; }
    }

    public static class Federation {
        private double relevanceThreshold = 0.3;

        public double getRelevanceThreshold() { return relevanceThreshold; }
        public void setRelevanceThreshold(double relevanceThreshold) { this.relevanceThreshold = relevanceThreshold; }
    }
}
