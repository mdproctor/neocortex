package io.casehub.neocortex.memory.mem0.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mem0SearchRequest(
    @JsonProperty("query")     String query,
    @JsonProperty("user_id")   String userId,
    @JsonProperty("agent_id")  String agentId,
    @JsonProperty("run_id")    String runId,
    @JsonProperty("top_k")     int topK,
    @JsonProperty("threshold") double threshold
) {}
