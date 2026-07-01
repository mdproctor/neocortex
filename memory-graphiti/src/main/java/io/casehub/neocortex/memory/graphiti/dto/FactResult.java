package io.casehub.neocortex.memory.graphiti.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record FactResult(
        @JsonProperty("uuid")        String uuid,
        @JsonProperty("name")        String name,
        @JsonProperty("fact")        String fact,
        @JsonProperty("valid_at")    Instant validAt,
        @JsonProperty("invalid_at")  Instant invalidAt,
        @JsonProperty("expired_at")  Instant expiredAt,
        @JsonProperty("created_at")  Instant createdAt
) {}
