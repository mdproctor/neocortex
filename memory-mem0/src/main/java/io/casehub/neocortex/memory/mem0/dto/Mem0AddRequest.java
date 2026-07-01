package io.casehub.neocortex.memory.mem0.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mem0AddRequest(
    @JsonProperty("messages")  List<Mem0Message> messages,
    @JsonProperty("user_id")   String userId,
    @JsonProperty("agent_id")  String agentId,
    @JsonProperty("run_id")    String runId,
    @JsonProperty("infer")     boolean infer,
    @JsonProperty("metadata")  Map<String, String> metadata
) {
    public record Mem0Message(
        @JsonProperty("role")    String role,
        @JsonProperty("content") String content
    ) {}
}
