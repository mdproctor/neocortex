package io.casehub.neocortex.memory.mem0.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Mem0MemoryResult(
    @JsonProperty("id")     String id,
    @JsonProperty("memory") String memory,
    @JsonProperty("event")  String event
) {}
