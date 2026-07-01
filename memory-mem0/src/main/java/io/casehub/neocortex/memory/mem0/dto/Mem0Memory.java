package io.casehub.neocortex.memory.mem0.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record Mem0Memory(
    @JsonProperty("id")         String id,
    @JsonProperty("memory")     String memory,
    @JsonProperty("metadata")   Map<String, String> metadata,
    @JsonProperty("score")      Float score,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("user_id")    String userId,
    @JsonProperty("agent_id")   String agentId,
    @JsonProperty("run_id")     String runId
) {}
