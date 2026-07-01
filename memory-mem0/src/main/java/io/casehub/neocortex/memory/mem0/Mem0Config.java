package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.memory.mem0")
public interface Mem0Config {

    /** Bearer token sent on every request. Required — startup fails if absent. */
    String apiKey();

    /**
     * Controls Mem0's LLM extraction. Default false — stores text verbatim (1:1 memoryId
     * contract). Setting true breaks store()/eraseById()/query(limit) semantics; see spec.
     */
    @WithDefault("false")
    boolean infer();

    /**
     * top_k passed to POST /search when query.since() != null. Needed because the search
     * endpoint supports top_k but since filtering is client-side; extra candidates ensure
     * the time window is filled. Default 500.
     */
    @WithDefault("500")
    int sinceSearchTopK();

    /**
     * Mem0 drops search results with score below this threshold before returning.
     * Raise to reduce noise; lower to increase recall. Mem0's default is 0.1.
     */
    @WithDefault("0.1")
    double searchThreshold();

    /**
     * Maximum number of concurrent HTTP calls during storeAll(). Bounded via Semaphore.
     * Math.max(1, ...) is applied at the call site — 0 or negative values are treated as 1.
     * Default 4: balances Mem0 server throughput against connection pool pressure.
     */
    @WithDefault("4")
    int storeAllConcurrency();
}
