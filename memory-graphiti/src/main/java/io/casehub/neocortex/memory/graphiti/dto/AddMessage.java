package io.casehub.neocortex.memory.graphiti.dto;

import io.casehub.neocortex.memory.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddMessage(
        @JsonProperty("content")          String content,
        @JsonProperty("uuid")             String uuid,
        @JsonProperty("name")             String name,
        @JsonProperty("role_type")        String roleType,
        @JsonProperty("role")             String role,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("timestamp")        Instant timestamp,
        @JsonProperty("source_description") String sourceDescription
) {}
